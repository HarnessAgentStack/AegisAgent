package com.aegis.runtime.service.policy;

import com.aegis.core.domain.security.OutboundPolicy;
import com.aegis.core.domain.security.SensitiveWord;
import com.aegis.core.domain.security.ToolPolicy;
import com.aegis.core.dto.security.PolicyDecision;
import com.aegis.core.dto.security.SecurityPolicyContext;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.resource.ToolPolicyAction;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.core.enums.security.OutboundPolicyType;
import com.aegis.core.enums.security.SensitiveAction;
import com.aegis.core.domain.resource.Tool;
import com.aegis.dal.mapper.resource.ToolMapper;
import com.aegis.dal.mapper.security.OutboundPolicyMapper;
import com.aegis.dal.mapper.security.SensitiveWordMapper;
import com.aegis.dal.mapper.security.ToolPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Aegis 统一安全策略引擎（v4.0 新增）。
 *
 * <p>整合原 {@code SecurityPolicyEngine}、{@code OutboundPolicyService}、{@code DataMaskService}
 * 三个分散引擎的能力，提供统一的策略评估入口。所有运行时安全中间件与工具层均通过本引擎
 * 完成策略决策，支持 5 种评估维度：
 * <ul>
 *   <li>{@link #evaluateToolPolicy} — 工具调用策略（资源级别 × 治理档位联动）</li>
 *   <li>{@link #evaluateContentPolicy} — 内容安全策略（敏感词 BLOCK/REPLACE/MASK）</li>
 *   <li>{@link #evaluateOutboundPolicy} — 出站访问策略（白名单域名 + 黑名单 IP + SSRF）</li>
 *   <li>{@link #evaluateModelPolicy} — 模型路由策略（L4 强制本地/加密模型）</li>
 *   <li>{@link #evaluateKbRetrievePolicy} — KB 检索策略（KB 安全等级 gate）</li>
 * </ul>
 *
 * <h3>资源安全等级直映访问行为（v4.3）</h3>
 * <pre>
 * 资源安全等级 | 访问控制行为
 * L1 公开      | ALLOW  （直接放行执行）
 * L2 内部      | ALLOW  （直接放行执行）
 * L3 机密      | ASK    （执行前需审批）
 * L4 绝密      | REJECT （禁止访问）
 * </pre>
 * 语义澄清：治理档位（STANDARD/ENHANCED/STRICT）仅决定沙箱环境分配的隔离强度，
 * 与资源访问权限无关；资源安全等级决定智能体执行资源（工具/知识库）前是否
 * 放行、审批或拒绝。sec_tool_policy 显式配置优先于等级直映。
 *
 * @author wang.zhen
 * @see PolicyDecision
 * @see SecurityPolicyContext
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisSecurityPolicyEngine {

    private final ToolPolicyMapper toolPolicyMapper;
    private final OutboundPolicyMapper outboundPolicyMapper;
    private final SensitiveWordMapper sensitiveWordMapper;
    private final DataMaskService dataMaskService;
    private final ToolMapper toolMapper;
    /** P1-1：策略二级缓存（Caffeine 300s + Redis 300s），cache-aside 减少 ~40 次/会话 SQL */
    private final SecurityPolicyCache policyCache;

    /** P2-2：SSRF DNS 解析缓存（60s TTL），避免每次出站策略评估裸 InetAddress.getAllByName */
    private final com.github.benmanes.caffeine.cache.Cache<String, java.net.InetAddress[]> dnsCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(2000)
                    .expireAfterWrite(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

    /**
     * 评估工具调用策略。
     *
     * <p>决策链路：
     * <ol>
     *   <li>加载 sec_tool_policy（per-instance 优先 → per-type 兜底）</li>
     *   <li>读取工具自身 securityLevel</li>
     *   <li>治理档位 × 资源级别联动矩阵叠加</li>
     *   <li>输出 ALLOW / ASK / REJECT / AUDIT_ONLY</li>
     * </ol>
     *
     * @param ctx 策略上下文
     * @return 策略决策
     */
    public PolicyDecision evaluateToolPolicy(SecurityPolicyContext ctx) {
        if (ctx == null || ctx.getAction() != SecurityPolicyContext.Action.TOOL_CALL) {
            return PolicyDecision.allow();
        }

        // 身份级豁免：SECURITY_OFFICER / 平台管理员绕过资源访问策略
        if (isPrivilegedRole(ctx.getUserRoles())) {
            log.debug("工具策略身份豁免: userId={}, roles={}", ctx.getUserId(), ctx.getUserRoles());
            return PolicyDecision.allow();
        }

        Long tenantId = ctx.getTenantId();
        String toolCode = ctx.getResourceCode();
        SecurityLevel toolLevel = ctx.getResourceLevel();
        Long resourceId = ctx.getResourceId();
        ResourceType resourceType = ctx.getResourceType();

        // 1. 加载工具策略
        ToolPolicy policy = loadToolPolicy(tenantId, toolCode, resourceId);

        // 2. per-instance 策略命中 → 直接返回（显式配置优先；
        //    v4.3：治理档位不再叠加资源访问决策）
        if (policy != null && policy.getEnabled()) {
            return mapToolPolicyAction(policy, resourceType, resourceId);
        }

        // 3. 无策略配置 → 资源等级直映（v4.3：档位不再参与资源访问决策）
        return evaluateByResourceLevel(toolLevel, resourceType, resourceId);
    }

    /**
     * 评估内容安全策略（敏感词）。
     *
     * @param ctx 策略上下文
     * @return 策略决策（ALLOW / REJECT / MASK）
     */
    public PolicyDecision evaluateContentPolicy(SecurityPolicyContext ctx) {
        if (ctx == null || ctx.getContent() == null || ctx.getContent().isEmpty()) {
            return PolicyDecision.allow();
        }

        Long tenantId = ctx.getTenantId();
        List<SensitiveWord> words = loadCachedSensitiveWords(tenantId);
        if (words == null || words.isEmpty()) {
            return PolicyDecision.allow();
        }

        String content = ctx.getContent();
        String lowerContent = content.toLowerCase();

        // BLOCK 检查
        for (SensitiveWord word : words) {
            if (word.getAction() == SensitiveAction.BLOCK
                    && word.getWord() != null && !word.getWord().isEmpty()
                    && lowerContent.contains(word.getWord().toLowerCase())) {
                return PolicyDecision.reject(
                        "内容包含敏感词 [BLOCK]: " + word.getWord(),
                        null, word.getId() != null ? String.valueOf(word.getId()) : null,
                        ctx.getResourceType() != null ? ctx.getResourceType().name() : null,
                        ctx.getResourceId());
            }
        }

        // REPLACE → MASK（敏感词先于脱敏规则，避免双重替换：脱敏正则不会匹配 "***"）
        for (SensitiveWord word : words) {
            if (word.getAction() == SensitiveAction.REPLACE
                    && word.getWord() != null && !word.getWord().isEmpty()
                    && lowerContent.contains(word.getWord().toLowerCase())) {
                String masked = content.replace(word.getWord(), "***");
                // P0-4：敏感词替换后追加按 sec_mask_rule 正则二次脱敏（身份证/手机号等结构化敏感数据）
                String finalMasked = dataMaskService.mask(masked, tenantId);
                return PolicyDecision.mask(finalMasked, null,
                        word.getId() != null ? String.valueOf(word.getId()) : null);
            }
        }

        // P0-4：无敏感词命中时，单独应用脱敏规则（结构化敏感数据遮蔽）
        String ruleMasked = dataMaskService.mask(content, tenantId);
        if (ruleMasked != null && !ruleMasked.equals(content)) {
            return PolicyDecision.mask(ruleMasked, null, "mask-rules");
        }

        return PolicyDecision.allow();
    }

    /**
     * 评估附件解析内容的内容安全策略（降级脱敏语义，2026-09-01 用户确认）。
     *
     * <p>附件文本（PDF/Word 解析产物）常包含安全合规术语（如技术规格书中
     * "降低被黑客攻击的风险"），若按用户手输的 BLOCK 拦截语义处理，
     * 正常文档解析任务会被整条误拦。</p>
     *
     * <p>降级语义：所有命中词（含 BLOCK）一律替换脱敏后放行——
     * 敏感词不透传给 LLM（保留安全底线），文档处理不中断（保留可用性）。
     * 逐词替换全部命中项（非首个命中即返回）。</p>
     *
     * @param ctx 策略上下文（content 为附件区文本）
     * @return 策略决策（命中时为 MASK + maskedContent，未命中为 ALLOW）
     */
    public PolicyDecision evaluateAttachmentContentPolicy(SecurityPolicyContext ctx) {
        if (ctx == null || ctx.getContent() == null || ctx.getContent().isEmpty()) {
            return PolicyDecision.allow();
        }

        Long tenantId = ctx.getTenantId();
        List<SensitiveWord> words = loadCachedSensitiveWords(tenantId);
        if (words == null || words.isEmpty()) {
            return PolicyDecision.allow();
        }

        String content = ctx.getContent();
        String masked = content;
        boolean hit = false;

        // 逐词替换全部命中项：BLOCK → ***（降级），REPLACE → replaceText（原语义）
        for (SensitiveWord word : words) {
            if (word.getWord() == null || word.getWord().isEmpty()) {
                continue;
            }
            if (word.getAction() != SensitiveAction.BLOCK
                    && word.getAction() != SensitiveAction.REPLACE) {
                continue;
            }
            if (masked.toLowerCase().contains(word.getWord().toLowerCase())) {
                String replacement = word.getAction() == SensitiveAction.REPLACE
                        && word.getReplaceText() != null ? word.getReplaceText() : "***";
                masked = masked.replace(word.getWord(), replacement);
                hit = true;
            }
        }

        // 叠加 sec_mask_rule 正则脱敏（身份证/手机号等结构化数据，与主流程一致）
        String finalMasked = dataMaskService.mask(masked, tenantId);
        if (finalMasked != null && !finalMasked.equals(masked)) {
            masked = finalMasked;
            hit = true;
        }

        if (hit) {
            log.info("附件内容降级脱敏: tenantId={}, hitWords 已替换, length={}",
                    tenantId, masked.length());
            return PolicyDecision.mask(masked, null, "attachment-degrade");
        }
        return PolicyDecision.allow();
    }

    /**
     * 评估出站访问策略。
     *
     * <p>校验 URL 是否符合白名单域名 / 黑名单 IP / SSRF 防护规则。
     *
     * @param ctx 策略上下文（content 为 URL）
     * @return 策略决策
     */
    public PolicyDecision evaluateOutboundPolicy(SecurityPolicyContext ctx) {
        if (ctx == null || ctx.getContent() == null || ctx.getContent().isEmpty()) {
            return PolicyDecision.reject("空 URL 拒绝访问", null, null,
                    ctx.getResourceType() != null ? ctx.getResourceType().name() : null,
                    ctx.getResourceId());
        }

        String url = ctx.getContent();
        Long tenantId = ctx.getTenantId();
        Long agentId = ctx.getAgentId();
        ResourceType resourceType = ctx.getResourceType();

        // MCP 服务为系统级预注册可信资源，其端点（含 localhost/内网）通过管理员审核，跳过 SSRF 防护
        boolean trustedMcpService = resourceType == ResourceType.MCP_SERVICE;

        // 1. URL 基本校验
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return PolicyDecision.reject("非 http/https 协议拒绝访问: " + scheme,
                        null, "ssrf-scheme", "NETWORK", null);
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return PolicyDecision.reject("空 host 拒绝访问",
                        null, "ssrf-host", "NETWORK", null);
            }

            // 2. SSRF 防护：DNS 解析 + 私有/回环段校验（可信 MCP 服务跳过）
            if (!trustedMcpService) {
                try {
                    InetAddress[] addresses = resolveDnsWithCache(host);
                    for (InetAddress addr : addresses) {
                        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                                || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                                || addr.isMulticastAddress()) {
                            return PolicyDecision.reject(
                                    "SSRF 防护：禁止访问内网/回环地址: " + addr.getHostAddress(),
                                    null, "ssrf-internal", "NETWORK", null);
                        }
                    }
                } catch (Exception e) {
                    return PolicyDecision.reject("DNS 解析失败: " + host,
                            null, "ssrf-dns", "NETWORK", null);
                }
            }

            // 3. 可信 MCP 服务跳过白名单/黑名单策略匹配
            if (trustedMcpService) {
                return PolicyDecision.allow(null, "mcp-trusted:bypass-whitelist", "NETWORK", null);
            }

            // 4. 出站策略匹配（P1-1：cache-aside）
            List<OutboundPolicy> policies = loadCachedOutboundPolicies(tenantId);

            if (policies == null || policies.isEmpty()) {
                return PolicyDecision.auditOnly("无出站策略配置，审计放行", null, "no-policy");
            }

            boolean hasWhitelist = false;
            for (OutboundPolicy policy : policies) {
                if (!isApplicable(policy, agentId)) continue;
                if (isExpired(policy)) continue;

                if (policy.getPolicyType() == OutboundPolicyType.WHITELIST_DOMAIN) {
                    hasWhitelist = true;
                    if (matchesDomain(host, policy.getDomain())) {
                        return PolicyDecision.allow(policy.getId(),
                                "whitelist:" + policy.getDomain(), "NETWORK", null);
                    }
                } else if (policy.getPolicyType() == OutboundPolicyType.BLACKLIST_IP) {
                    if (matchesIp(host, policy.getIpCidr())) {
                        return PolicyDecision.reject("命中黑名单 IP/CIDR: " + policy.getIpCidr(),
                                policy.getId(), "blacklist:" + policy.getIpCidr(), "NETWORK", null);
                    }
                }
            }

            if (hasWhitelist) {
                return PolicyDecision.reject("访问不在白名单域名内: " + host,
                        null, "not-in-whitelist", "NETWORK", null);
            }

            return PolicyDecision.allow(null, null, "NETWORK", null);

        } catch (Exception e) {
            return PolicyDecision.reject("URL 解析异常: " + e.getMessage(),
                    null, "url-parse-error", "NETWORK", null);
        }
    }

    /**
     * 评估模型路由策略。
     *
     * <p>STRICT 档位 + L4 资源 → 强制本地/加密模型。
     *
     * @param ctx 策略上下文
     * @return 策略决策
     */
    public PolicyDecision evaluateModelPolicy(SecurityPolicyContext ctx) {
        if (ctx == null) return PolicyDecision.allow();

        GovernanceTier tier = ctx.getGovernanceTier();
        SecurityLevel level = ctx.getResourceLevel();

        if (tier == GovernanceTier.STRICT && level == SecurityLevel.L4) {
            return PolicyDecision.routeLocal("local-encrypted",
                    "STRICT 档位 + L4 资源，强制本地加密模型");
        }

        return PolicyDecision.allow();
    }

    /**
     * 评估 KB 检索策略。
     *
     * <p>按 KB 自身 securityLevel 与智能体治理档位联动矩阵评估。
     *
     * @param ctx 策略上下文
     * @return 策略决策
     */
    public PolicyDecision evaluateKbRetrievePolicy(SecurityPolicyContext ctx) {
        if (ctx == null) return PolicyDecision.allow();

        // 身份级豁免：SECURITY_OFFICER / 平台管理员可访问任意密级 KB
        if (isPrivilegedRole(ctx.getUserRoles())) {
            log.debug("KB 策略身份豁免: userId={}, roles={}", ctx.getUserId(), ctx.getUserRoles());
            return PolicyDecision.allow();
        }

        SecurityLevel kbLevel = ctx.getResourceLevel();

        // v4.3：KB 检索行为由知识库安全等级直映（L1/L2 放行，L3 需审批，L4 拒绝），
        // 治理档位仅决定沙箱隔离强度，不再参与资源访问决策
        return evaluateByResourceLevel(kbLevel,
                ctx.getResourceType(), ctx.getResourceId());
    }

    // ==================== 内部方法 ====================

    /** 特权角色：可豁免资源访问策略，访问任意密级资源 */
    private static final java.util.Set<String> PRIVILEGED_ROLES =
            java.util.Set.of("SECURITY_OFFICER", "PLATFORM_ADMIN", "SUPER_ADMIN");

    /**
     * 判断角色列表是否含特权角色（身份级策略豁免）。
     *
     * @param roles 用户角色编码列表
     * @return true 表示含特权角色，豁免资源访问策略
     */
    private boolean isPrivilegedRole(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        return roles.stream().anyMatch(PRIVILEGED_ROLES::contains);
    }

    /**
     * 加载工具策略（per-instance 优先 → per-type 兜底）。
     *
     * <p>修复 E-01：原先用 toolCode（工具名如 http_request）匹配 sec_tool_policy.tool_type
     * （工具类别如 READONLY），永远不可能命中。改为通过 resourceId 查 res_tool 拿 toolType，
     * 再与 sec_tool_policy.tool_type 枚举匹配。
     */
    private ToolPolicy loadToolPolicy(Long tenantId, String toolCode, Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        // 通过工具 ID 查 res_tool 拿 toolType（READONLY / WRITE / CODE_EXEC 等）
        Tool tool = toolMapper.selectById(resourceId);
        if (tool == null || tool.getToolType() == null) {
            log.debug("工具 {} 未找到或 toolType 为空，跳过策略匹配", resourceId);
            return null;
        }
        String toolTypeStr = tool.getToolType().name();

        List<ToolPolicy> all = loadCachedToolPolicies(tenantId);
        for (ToolPolicy p : all) {
            if (toolTypeStr.equals(p.getToolType()) && Boolean.TRUE.equals(p.getEnabled())) {
                log.debug("命中工具策略: toolCode={}, toolType={}, action={}", toolCode, toolTypeStr, p.getAction());
                return p;
            }
        }
        return null;
    }

    /**
     * P1-1：按租户整表缓存 sec_tool_policy（仅 enabled），miss 查 DB 回填。
     */
    private List<ToolPolicy> loadCachedToolPolicies(Long tenantId) {
        if (tenantId == null) {
            return java.util.Collections.emptyList();
        }
        String cached = policyCache.get(tenantId, "TOOL");
        if (cached != null) {
            List<ToolPolicy> parsed = com.alibaba.fastjson2.JSON.parseArray(cached, ToolPolicy.class);
            if (parsed != null) {
                return parsed;
            }
        }
        List<ToolPolicy> fromDb = toolPolicyMapper.selectList(
                new LambdaQueryWrapper<ToolPolicy>()
                        .eq(ToolPolicy::getTenantId, tenantId)
                        .eq(ToolPolicy::getEnabled, true));
        if (fromDb == null) {
            fromDb = java.util.Collections.emptyList();
        }
        policyCache.set(tenantId, "TOOL", com.alibaba.fastjson2.JSON.toJSONString(fromDb));
        return fromDb;
    }

    /**
     * P1-1：按租户整表缓存 sec_sensitive_word（仅 enabled），miss 查 DB 回填。
     */
    private List<SensitiveWord> loadCachedSensitiveWords(Long tenantId) {
        if (tenantId == null) {
            return java.util.Collections.emptyList();
        }
        String cached = policyCache.get(tenantId, "CONTENT");
        if (cached != null) {
            List<SensitiveWord> parsed = com.alibaba.fastjson2.JSON.parseArray(cached, SensitiveWord.class);
            if (parsed != null) {
                return parsed;
            }
        }
        List<SensitiveWord> fromDb = sensitiveWordMapper.selectList(
                new LambdaQueryWrapper<SensitiveWord>()
                        .eq(SensitiveWord::getTenantId, tenantId)
                        .eq(SensitiveWord::getEnabled, true));
        if (fromDb == null) {
            fromDb = java.util.Collections.emptyList();
        }
        policyCache.set(tenantId, "CONTENT", com.alibaba.fastjson2.JSON.toJSONString(fromDb));
        return fromDb;
    }

    /**
     * P1-1：按租户整表缓存 sec_outbound_policy（仅 enabled），miss 查 DB 回填。
     */
    private List<OutboundPolicy> loadCachedOutboundPolicies(Long tenantId) {
        if (tenantId == null) {
            return java.util.Collections.emptyList();
        }
        String cached = policyCache.get(tenantId, "OUTBOUND");
        if (cached != null) {
            List<OutboundPolicy> parsed = com.alibaba.fastjson2.JSON.parseArray(cached, OutboundPolicy.class);
            if (parsed != null) {
                return parsed;
            }
        }
        List<OutboundPolicy> fromDb = outboundPolicyMapper.selectList(
                new LambdaQueryWrapper<OutboundPolicy>()
                        .eq(OutboundPolicy::getTenantId, tenantId)
                        .eq(OutboundPolicy::getEnabled, true));
        if (fromDb == null) {
            fromDb = java.util.Collections.emptyList();
        }
        policyCache.set(tenantId, "OUTBOUND", com.alibaba.fastjson2.JSON.toJSONString(fromDb));
        return fromDb;
    }

    /**
     * P2-2：带缓存的 DNS 解析（60s TTL）。
     *
     * <p>SSRF 防护每次出站策略评估都裸调 InetAddress.getAllByName，高频出站场景 DNS 压力大。
     * 缓存解析结果 60s（安全与实时 DNS 之间折中——SSRF 防护主要拦截内网/回环段，
     * 60s 内 DNS TTL 变化对防护有效性影响可忽略，且 rebind 攻击需配合极短 TTL 绕过）。
     * 解析失败不缓存（保留每次尝试）。
     */
    private java.net.InetAddress[] resolveDnsWithCache(String host) throws java.net.UnknownHostException {
        java.net.InetAddress[] cached = dnsCache.getIfPresent(host);
        if (cached != null) {
            return cached;
        }
        java.net.InetAddress[] resolved = InetAddress.getAllByName(host);
        dnsCache.put(host, resolved);
        return resolved;
    }

    /**
     * 映射工具策略 Action 到 PolicyDecision。
     */
    private PolicyDecision mapToolPolicyAction(ToolPolicy policy,
                                               ResourceType resourceType, Long resourceId) {
        ToolPolicyAction action = policy.getAction();
        if (action == null) return PolicyDecision.allow();

        return switch (action) {
            case ALLOW -> PolicyDecision.allow(policy.getId(), null,
                    resourceType != null ? resourceType.name() : null, resourceId);
            case APPROVE -> PolicyDecision.ask(
                    "工具调用需审批: " + policy.getDescription(),
                    policy.getId(), null,
                    resourceType != null ? resourceType.name() : null, resourceId,
                    SecurityLevel.fromLevel(policy.getSecurityLevel()));
            case REJECT -> PolicyDecision.reject(
                    "工具被策略拒绝: " + policy.getDescription(),
                    policy.getId(), null,
                    resourceType != null ? resourceType.name() : null, resourceId);
            default -> PolicyDecision.allow();
        };
    }

    /**
     * 资源安全等级直映访问行为（v4.3 重构，替代原"档位 × 等级"矩阵）。
     *
     * <p><b>语义澄清</b>：治理档位（STANDARD/ENHANCED/STRICT）只决定沙箱环境
     * 分配的隔离强度，与资源访问权限无关；资源安全等级决定智能体执行资源前的
     * 访问控制行为：
     * <ul>
     *   <li>L1 公开 / L2 内部 → ALLOW（直接放行执行）</li>
     *   <li>L3 机密 → ASK（执行前需审批）</li>
     *   <li>L4 绝密 → REJECT（禁止访问）</li>
     * </ul>
     */
    private PolicyDecision evaluateByResourceLevel(SecurityLevel resourceLevel,
                                                   ResourceType resourceType, Long resourceId) {
        if (resourceLevel == null) resourceLevel = SecurityLevel.L1;

        String resTypeName = resourceType != null ? resourceType.name() : null;

        return switch (resourceLevel) {
            case L1, L2 -> PolicyDecision.allow(null, "resource-level:allow", resTypeName, resourceId);
            case L3 -> PolicyDecision.ask(
                    "机密资源(L3)，执行前需审批",
                    null, "resource-level:ask", resTypeName, resourceId, resourceLevel);
            case L4 -> PolicyDecision.reject(
                    "绝密资源(L4)，禁止访问",
                    null, "resource-level:reject", resTypeName, resourceId);
        };
    }

    /**
     * 策略是否适用于当前智能体。
     */
    private boolean isApplicable(OutboundPolicy policy, Long agentId) {
        if (policy.getApplicableScope() == null) return true;
        String scopeConfig = policy.getScopeConfig();
        if (scopeConfig == null || scopeConfig.isEmpty()) return true;
        if (agentId == null) return true;
        return scopeConfig.contains(String.valueOf(agentId));
    }

    /**
     * 策略是否过期。
     */
    private boolean isExpired(OutboundPolicy policy) {
        if (policy.getValidHours() == null || policy.getValidHours() <= 0) return false;
        LocalDateTime createTime = policy.getCreateTime();
        if (createTime == null) return false;
        return LocalDateTime.now().isAfter(createTime.plusHours(policy.getValidHours()));
    }

    /**
     * 域名匹配（支持通配符 *.example.com）。
     */
    private boolean matchesDomain(String host, String domain) {
        if (domain == null || domain.isEmpty()) return false;
        if (domain.startsWith("*.")) {
            String suffix = domain.substring(2);
            return host.equals(suffix) || host.endsWith("." + suffix);
        }
        return host.equals(domain);
    }

    /**
     * IP/CIDR 匹配。
     */
    private boolean matchesIp(String host, String ipCidr) {
        if (ipCidr == null || ipCidr.isEmpty()) return false;
        String network = ipCidr.contains("/") ? ipCidr.split("/")[0] : ipCidr;
        try {
            InetAddress networkAddr = InetAddress.getByName(network);
            InetAddress[] hostAddrs = InetAddress.getAllByName(host);
            for (InetAddress hostAddr : hostAddrs) {
                if (!ipCidr.contains("/")) {
                    if (hostAddr.equals(networkAddr)) return true;
                    continue;
                }
                int prefixBits = Integer.parseInt(ipCidr.split("/")[1]);
                if (isInCidr(hostAddr, networkAddr, prefixBits)) return true;
            }
        } catch (Exception e) {
            log.debug("matchesIp 解析失败: host={}, ipCidr={}", host, ipCidr);
        }
        return false;
    }

    private boolean isInCidr(InetAddress hostAddr, InetAddress networkAddr, int prefixBits) {
        byte[] hostBytes = hostAddr.getAddress();
        byte[] netBytes = networkAddr.getAddress();
        if (hostBytes.length != netBytes.length) return false;
        int fullBytes = prefixBits / 8;
        int remainderBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (hostBytes[i] != netBytes[i]) return false;
        }
        if (remainderBits > 0 && fullBytes < netBytes.length) {
            int mask = 0xFF << (8 - remainderBits);
            if ((hostBytes[fullBytes] & mask) != (netBytes[fullBytes] & mask)) return false;
        }
        return true;
    }
}
