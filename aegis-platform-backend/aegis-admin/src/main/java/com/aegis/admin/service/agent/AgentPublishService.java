package com.aegis.admin.service.agent;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentApi;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.agent.AgentSubscription;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.dto.agent.AgentApiConfigRequest;
import com.aegis.core.dto.agent.AgentCreateRequest;
import com.aegis.core.dto.agent.AgentUpdateRequest;
import com.aegis.core.dto.agent.AgentVO;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.agent.AgentType;
import com.aegis.core.enums.agent.BindingType;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.agent.MemoryStrategy;
import com.aegis.core.enums.agent.SubscriptionStatus;
import com.aegis.core.enums.api.ApiAuthType;
import com.aegis.core.enums.api.ApiResponseMode;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.common.ReviewStatus;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.util.XssSanitizer;
import com.aegis.admin.service.resource.ReviewProcessEngine;
import com.aegis.admin.infrastructure.sandbox.SandboxPoolMatcher;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.enums.monitor.PoolStatus;
import com.aegis.dal.mapper.agent.AgentApiMapper;
import com.aegis.dal.mapper.agent.AgentBindingMapper;
import com.aegis.dal.mapper.agent.AgentConfigMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.aegis.dal.mapper.agent.AgentSubscriptionMapper;
import com.aegis.dal.mapper.resource.ResourceReviewMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 智能体管理领域服务。
 *
 * <p>遵循"产品减法"重构后的生命周期：
 * <ul>
 *   <li>创建 → 统一进入 {@link AgentLifeStatus#DRAFT} 草稿态，作者本人可立即对话自用；</li>
 *   <li>发布 → 仅能通过审核闭环 {@code submitReview → REVIEWING → approveReview → PUBLISHED}，
 *       不再提供直发/激活/转共享等绕过审核的路径；</li>
 *   <li>归档 → {@code PUBLISHED → ARCHIVED}，历史会话只读；</li>
 *   <li>订阅 → 仅已发布智能体可被本租户用户订阅（落库）。</li>
 * </ul>
 *
 * <p>类型（{@link AgentType}）作为唯一主判别器，驱动治理档位、资源绑定作用域、沙箱策略与对外 API：
 * 系统智能体的"上线"= 对外 API 启用，该动作必须在审核通过后才激活（不可于创建期直开）。
 *
 * @author wang.zhen
 * @see AgentDef
 * @see AgentConfig
 * @see AgentApi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPublishService {

    private final AgentDefMapper agentDefMapper;
    private final AgentConfigMapper agentConfigMapper;
    private final AgentBindingMapper agentBindingMapper;
    private final AgentApiMapper agentApiMapper;
    private final AgentSubscriptionMapper agentSubscriptionMapper;
    private final ResourceReviewMapper resourceReviewMapper;
    private final ReviewProcessEngine reviewProcessEngine;
    private final SandboxPoolMatcher sandboxPoolMatcher;
    private final AgentSubscriptionService subscriptionService;

    /** Runtime 服务地址，用于通知模板缓存失效（须在 application.yml 或 Nacos 显式配置 aegis.runtime.base-url） */
    @org.springframework.beans.factory.annotation.Value("${aegis.runtime.base-url}")
    private String runtimeBaseUrl;

    /** WebClient 用于调用 Runtime 内部 API（懒初始化） */
    private volatile org.springframework.web.reactive.function.client.WebClient runtimeWebClient;

    private org.springframework.web.reactive.function.client.WebClient getRuntimeWebClient() {
        if (runtimeWebClient == null) {
            synchronized (this) {
                if (runtimeWebClient == null) {
                    runtimeWebClient = org.springframework.web.reactive.function.client.WebClient.builder()
                            .baseUrl(runtimeBaseUrl).build();
                }
            }
        }
        return runtimeWebClient;
    }

    /**
     * 通知 Runtime 服务失效指定智能体的模板缓存。
     */
    private void notifyTemplateInvalidation(Long agentId, String version, Long tenantId) {
        try {
            var spec = getRuntimeWebClient().delete()
                    .uri(uriBuilder -> uriBuilder.path("/api/runtime/internal/template-cache/{agentId}")
                            .queryParam("tenantId", tenantId)
                            .queryParamIfPresent("version", java.util.Optional.ofNullable(version))
                            .build(agentId));
            spec.retrieve().toBodilessEntity().subscribe(
                    resp -> log.debug("Template cache invalidation notified: agentId={}", agentId),
                    err -> log.warn("Failed to notify template cache invalidation: agentId={}, error={}", agentId, err.getMessage())
            );
        } catch (Exception e) {
            log.warn("Template cache invalidation notification error: agentId={}, error={}", agentId, e.getMessage());
        }
    }

    /**
     * 创建智能体（草稿态）。
     *
     * <p>统一进入 DRAFT；通用智能体由平台预置，用户不可创建。治理档位默认 STANDARD。
     * 系统智能体创建时一并写入一条 DISABLED 的对外 API 记录，待审核通过后再启用（杜绝创建期直开）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(AgentCreateRequest req) {
        if (req.getTenantId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (req.getAgentCode() == null || req.getAgentCode().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "智能体编码不能为空");
        }
        if (req.getAuthorUserId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "创建者ID不能为空");
        }
        // 禁止用户创建通用智能体（通用智能体由系统预置）
        if (req.getAgentType() == AgentType.UNIVERSAL) {
            throw new BusinessException(ResultCode.FORBIDDEN, "通用智能体由系统预置，用户不可创建");
        }
        // XSS 清洗：对用户输入文本字段进行 HTML 转义
        if (req.getAgentName() != null) req.setAgentName(XssSanitizer.sanitize(req.getAgentName(), 200));
        if (req.getDescription() != null) req.setDescription(XssSanitizer.sanitize(req.getDescription(), 1000));
        if (req.getIcon() != null) req.setIcon(XssSanitizer.sanitize(req.getIcon(), 500));
        if (req.getColor() != null) req.setColor(XssSanitizer.sanitize(req.getColor(), 50));
        // 编码租户内唯一
        Long exists = agentDefMapper.selectCount(new LambdaQueryWrapper<AgentDef>()
                .eq(AgentDef::getTenantId, req.getTenantId())
                .eq(AgentDef::getAgentCode, req.getAgentCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "智能体编码已存在: " + req.getAgentCode());
        }

        GovernanceTier tier = req.getGovernanceTier() != null ? req.getGovernanceTier() : GovernanceTier.STANDARD;

        // 1. 构建智能体定义（tenantId 在父类 TenantEntity，@Builder 不含父类字段，需 build 后 setter 设置）
        AgentDef def = AgentDef.builder()
                .agentCode(req.getAgentCode())
                .agentName(req.getAgentName())
                .agentType(req.getAgentType())
                .icon(req.getIcon())
                .color(req.getColor())
                .description(req.getDescription())
                .category(req.getCategory())
                .governanceTier(tier)
                .lifeStatus(AgentLifeStatus.DRAFT)
                .version("0.0.1")
                .authorUserId(req.getAuthorUserId())
                .authorDeptId(req.getAuthorDeptId())
                .subsCount(0)
                .visibility(com.aegis.core.enums.common.Visibility.TENANT)
                .lockVersion(0)
                .build();
        def.setTenantId(req.getTenantId());
        agentDefMapper.insert(def);
        log.info("Agent created: id={}, code={}, tenantId={}, type={}", def.getId(), def.getAgentCode(), def.getTenantId(), def.getAgentType());

        // 2. 构建智能体配置（使用用户提交值，未填字段使用合理默认值）
        ModelTier modelTier = parseModelTier(req.getModelTier(), ModelTier.STANDARD);
        MemoryStrategy mem = parseMemoryStrategy(req.getMemoryStrategy(), MemoryStrategy.SESSION_LEVEL);
        BigDecimal temperature = req.getTemperature() != null
                ? req.getTemperature() : BigDecimal.valueOf(0.7);
        Integer maxTurns = req.getMaxTurns() != null ? req.getMaxTurns() : 20;
        String systemPrompt = req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()
                ? req.getSystemPrompt()
                : (req.getDescription() != null ? req.getDescription() : "你是一个乐于助人的智能助手。");
        String enabledToolsJson = req.getEnabledTools() != null && !req.getEnabledTools().isEmpty()
                ? toLongJsonArray(req.getEnabledTools()) : "[]";

        AgentConfig config = AgentConfig.builder()
                .agentId(def.getId())
                .version(def.getVersion())
                .systemPrompt(systemPrompt)
                .modelTier(modelTier)
                .temperature(temperature)
                .memoryStrategy(mem)
                .maxTurns(maxTurns)
                .enabledTools(enabledToolsJson)
                .build();
        config.setTenantId(def.getTenantId());
        agentConfigMapper.insert(config);

        // 3. 系统智能体：创建一条 DISABLED 的对外 API 记录（审核通过后再启用+自动匹配沙箱池）
        if (def.getAgentType() == AgentType.SYSTEM) {
            AgentApiConfigRequest apiReq = req.getApiConfig();
            String apiName = (apiReq != null && apiReq.getApiName() != null && !apiReq.getApiName().isBlank())
                    ? apiReq.getApiName() : def.getAgentName() + " API";
            AgentApi api = AgentApi.builder()
                    .agentId(def.getId())
                    .apiName(apiName)
                    .apiPath("/api/v1/agent/" + def.getAgentCode() + "/invoke")
                    .httpMethod((apiReq != null && apiReq.getHttpMethod() != null) ? apiReq.getHttpMethod() : "POST")
                    .authType((apiReq != null && apiReq.getAuthType() != null) ? apiReq.getAuthType() : ApiAuthType.API_KEY)
                    .responseMode((apiReq != null && apiReq.getResponseMode() != null) ? apiReq.getResponseMode() : ApiResponseMode.SYNC)
                    .rateLimit((apiReq != null && apiReq.getRateLimit() != null) ? apiReq.getRateLimit() : 20)
                    .timeout((apiReq != null && apiReq.getTimeout() != null) ? apiReq.getTimeout() : 30)
                    .status(CommonStatus.DISABLED)
                    .reservedReplicas(req.getReservedReplicas() != null ? req.getReservedReplicas() : 1)
                    .version("1.0.0")
                    .requestSchema(normalizeJsonSchema(apiReq != null ? apiReq.getRequestSchema() : null))
                    .responseSchema(normalizeJsonSchema(apiReq != null ? apiReq.getResponseSchema() : null))
                    .concurrentLimit(apiReq != null ? apiReq.getConcurrentLimit() : null)
                    .validityType(apiReq != null ? apiReq.getValidityType() : null)
                    .bearerTokenMode(apiReq != null ? apiReq.getBearerTokenMode() : null)
                    .bearerJwtAlgorithm(apiReq != null ? apiReq.getBearerJwtAlgorithm() : null)
                    .bearerJwtSecret(apiReq != null ? apiReq.getBearerJwtSecret() : null)
                    .bearerTokenValue(apiReq != null ? apiReq.getBearerTokenValue() : null)
                    .bearerPassThrough(apiReq != null ? apiReq.getBearerPassThrough() : null)
                    .build();
            api.setTenantId(def.getTenantId());
            agentApiMapper.insert(api);
            log.info("System agent API record created (disabled): agentId={}, apiName={}, pool auto-match on approval", def.getId(), apiName);
        }

        // 4. 创建资源绑定（可选）
        if (req.getBindings() != null) {
            for (AgentCreateRequest.BindingRequest b : req.getBindings()) {
                AgentBinding binding = AgentBinding.builder()
                        .agentId(def.getId())
                        .agentVersion(def.getVersion())
                        .resourceType(parseResourceType(b.getResourceType()))
                        .resourceId(b.getResourceId())
                        .resourceVersion(b.getResourceVersion() != null ? b.getResourceVersion() : "latest")
                        .bindingType(parseBindingType(b.getBindingType(), BindingType.FIXED))
                        .enabled(b.getEnabled() != null ? b.getEnabled() : true)
                        .build();
                binding.setTenantId(def.getTenantId());
                agentBindingMapper.insert(binding);
            }
            log.info("Agent bindings created: agentId={}, count={}", def.getId(), req.getBindings().size());
        }

        return def.getId();
    }

    /**
     * 整体替换式更新智能体（BUG1 修复入口）。
     *
     * <p>与 {@code update(AgentDef def)} 的区别：本方法一次性承接前端编辑页提交的全部字段，
     * 在同一事务内依次处理：
     * <ol>
     *   <li>{@code agent_def} 主体字段（agentName/icon/color/description/category/governanceTier）</li>
     *   <li>{@code agent_config} 配置（systemPrompt/modelTier/temperature/memoryStrategy/maxTurns/enabledTools）</li>
     *   <li>{@code agent_binding} 资源绑定（整体替换：{@code req.bindings == null} 不修改，否则 delete + insert）</li>
     *   <li>{@code agent_api} API 发布配置（仅 SYSTEM 类型，{@code req.apiConfig != null} 时整体替换）</li>
     * </ol>
     *
     * <p>PUBLISHED 状态下自动递增 minor 版本并创建 AgentConfig 快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long agentId, Long tenantId, AgentUpdateRequest req) {
        AgentDef existing = requireAgent(agentId, tenantId);
        if (existing.getLifeStatus() == AgentLifeStatus.ARCHIVED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "智能体已归档，不可修改");
        }
        if (existing.getLifeStatus() == AgentLifeStatus.REVIEWING) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "智能体审核中，不可修改");
        }

        String targetVersion = existing.getVersion();
        boolean versionBumped = false;
        if (existing.getLifeStatus() == AgentLifeStatus.PUBLISHED) {
            targetVersion = bumpVersion(existing.getVersion(), existing.getLifeStatus());
            versionBumped = true;
        }

        // ========== 1. 更新 agent_def 主体字段 ==========
        AgentDef def = AgentDef.builder()
                .agentName(req.getAgentName() != null ? XssSanitizer.sanitize(req.getAgentName(), 200) : existing.getAgentName())
                .icon(req.getIcon() != null ? XssSanitizer.sanitize(req.getIcon(), 500) : existing.getIcon())
                .color(req.getColor() != null ? XssSanitizer.sanitize(req.getColor(), 50) : existing.getColor())
                .description(req.getDescription() != null ? XssSanitizer.sanitize(req.getDescription(), 1000) : existing.getDescription())
                .category(req.getCategory() != null ? req.getCategory() : existing.getCategory())
                .governanceTier(req.getGovernanceTier() != null ? req.getGovernanceTier() : existing.getGovernanceTier())
                .lockVersion(existing.getLockVersion())
                .build();
        // Lombok @TableId 字段不纳入 Builder,需显式 set
        def.setId(agentId);
        // 不允许修改编码 / 创建者 / 类型 / 状态 / 可见性
        def.setAgentCode(existing.getAgentCode());
        def.setAuthorUserId(existing.getAuthorUserId());
        def.setAuthorDeptId(existing.getAuthorDeptId());
        def.setAgentType(existing.getAgentType());
        def.setLifeStatus(existing.getLifeStatus());
        def.setVisibility(com.aegis.core.enums.common.Visibility.TENANT);
        def.setVersion(targetVersion);
        agentDefMapper.updateById(def);
        log.info("Agent def updated: id={}, version={}", agentId, targetVersion);

        // ========== 2. 更新 agent_config ==========
        if (req.getSystemPrompt() != null || req.getModelTier() != null
                || req.getTemperature() != null || req.getMemoryStrategy() != null
                || req.getMaxTurns() != null || req.getEnabledTools() != null) {
            AgentConfig currentCfg = agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                    .eq(AgentConfig::getAgentId, agentId)
                    .eq(AgentConfig::getVersion, existing.getVersion())
                    .last("LIMIT 1"));

            AgentConfig cfgToSave;
            if (versionBumped) {
                // PUBLISHED → 新版本：以当前版本 config 为底，覆盖新字段，insert 新快照
                cfgToSave = (currentCfg != null) ? currentCfg : new AgentConfig();
                cfgToSave.setId(null);
                cfgToSave.setVersion(targetVersion);
            } else {
                // DRAFT/REJECTED → 直接覆盖当前版本 config（不存在则新建）
                if (currentCfg != null) {
                    cfgToSave = currentCfg;
                } else {
                    cfgToSave = new AgentConfig();
                    cfgToSave.setAgentId(agentId);
                    cfgToSave.setVersion(targetVersion);
                    cfgToSave.setTenantId(tenantId);
                }
            }

            if (req.getSystemPrompt() != null) cfgToSave.setSystemPrompt(XssSanitizer.sanitize(req.getSystemPrompt(), 50000));
            if (req.getModelTier() != null) cfgToSave.setModelTier(com.aegis.core.enums.model.ModelTier.valueOf(req.getModelTier()));
            if (req.getTemperature() != null) cfgToSave.setTemperature(req.getTemperature());
            if (req.getMemoryStrategy() != null) cfgToSave.setMemoryStrategy(com.aegis.core.enums.agent.MemoryStrategy.valueOf(req.getMemoryStrategy()));
            if (req.getMaxTurns() != null) cfgToSave.setMaxTurns(req.getMaxTurns());
            if (req.getEnabledTools() != null) {
                if (req.getEnabledTools().isEmpty()) {
                    cfgToSave.setEnabledTools("[]");
                } else {
                    try {
                        cfgToSave.setEnabledTools(com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                .build().writeValueAsString(req.getEnabledTools()));
                    } catch (Exception ex) {
                        throw new BusinessException(ResultCode.PARAM_ERROR, "enabledTools 序列化失败");
                    }
                }
            }

            if (versionBumped || currentCfg == null) {
                agentConfigMapper.insert(cfgToSave);
            } else {
                agentConfigMapper.updateById(cfgToSave);
            }
            log.info("Agent config updated: agentId={}, version={}", agentId, targetVersion);
        }

        // ========== 3. 整体替换 agent_binding ==========
        if (req.getBindings() != null) {
            // 物理删除旧绑定（绕过 @TableLogic 逻辑删除）
            // 原因：逻辑删除仅 SET deleted=1，旧记录仍在表中，
            // 新 INSERT 会因唯一索引 uk_agent_binding_resource 不含 deleted 字段而冲突
            agentBindingMapper.physicalDeleteByAgent(tenantId, agentId);
            // 新绑定批量插入
            for (AgentCreateRequest.BindingRequest b : req.getBindings()) {
                AgentBinding binding = AgentBinding.builder()
                        .agentId(agentId)
                        .agentVersion(targetVersion)
                        .resourceType(parseResourceType(b.getResourceType()))
                        .resourceId(b.getResourceId())
                        .resourceVersion(b.getResourceVersion() != null ? b.getResourceVersion() : "latest")
                        .bindingType(parseBindingType(b.getBindingType(), BindingType.FIXED))
                        .enabled(b.getEnabled() != null ? b.getEnabled() : true)
                        .build();
                binding.setTenantId(tenantId);
                agentBindingMapper.insert(binding);
            }
            log.info("Agent bindings replaced: agentId={}, count={}", agentId, req.getBindings().size());
        }

        // ========== 4. 更新 agent_api（仅 SYSTEM 类型） ==========
        if (existing.getAgentType() == AgentType.SYSTEM && req.getApiConfig() != null) {
            AgentApiConfigRequest apiReq = req.getApiConfig();
            AgentApi current = agentApiMapper.selectOne(new LambdaQueryWrapper<AgentApi>()
                    .eq(AgentApi::getAgentId, agentId)
                    .last("LIMIT 1"));
            if (current == null) {
                // 补建缺省值：apiName/apiPath 由 def 派生，避免 apiReq 仅传部分字段时插出残缺记录
                current = AgentApi.builder()
                        .agentId(agentId)
                        .apiName(def.getAgentName() + " API")
                        .apiPath("/api/v1/agent/" + def.getAgentCode() + "/invoke")
                        .httpMethod("POST")
                        .authType(ApiAuthType.API_KEY)
                        .responseMode(ApiResponseMode.SYNC)
                        .rateLimit(20)
                        .timeout(30)
                        .status(CommonStatus.DISABLED)
                        .reservedReplicas(1)
                        .version("1.0.0")
                        .build();
                current.setTenantId(tenantId);
            }
            if (apiReq.getApiName() != null) current.setApiName(apiReq.getApiName());
            if (apiReq.getHttpMethod() != null) current.setHttpMethod(apiReq.getHttpMethod());
            if (apiReq.getResponseMode() != null) current.setResponseMode(apiReq.getResponseMode());
            if (apiReq.getTimeout() != null) current.setTimeout(apiReq.getTimeout());
            if (apiReq.getRateLimit() != null) current.setRateLimit(apiReq.getRateLimit());
            if (apiReq.getConcurrentLimit() != null) current.setConcurrentLimit(apiReq.getConcurrentLimit());
            if (apiReq.getAuthType() != null) current.setAuthType(apiReq.getAuthType());
            if (apiReq.getValidityType() != null) current.setValidityType(apiReq.getValidityType());
            if (apiReq.getRequestSchema() != null) current.setRequestSchema(normalizeJsonSchema(apiReq.getRequestSchema()));
            if (apiReq.getResponseSchema() != null) current.setResponseSchema(normalizeJsonSchema(apiReq.getResponseSchema()));
            if (apiReq.getBearerTokenMode() != null) current.setBearerTokenMode(apiReq.getBearerTokenMode());
            if (apiReq.getBearerTokenValue() != null) current.setBearerTokenValue(apiReq.getBearerTokenValue());
            if (apiReq.getBearerJwtSecret() != null) current.setBearerJwtSecret(apiReq.getBearerJwtSecret());
            if (apiReq.getBearerJwtAlgorithm() != null) current.setBearerJwtAlgorithm(apiReq.getBearerJwtAlgorithm());
            if (apiReq.getBearerIntrospectionUrl() != null) current.setBearerIntrospectionUrl(apiReq.getBearerIntrospectionUrl());
            if (apiReq.getBearerPassThrough() != null) current.setBearerPassThrough(apiReq.getBearerPassThrough());

            if (current.getId() == null) {
                agentApiMapper.insert(current);
            } else {
                agentApiMapper.updateById(current);
            }
            log.info("Agent apiConfig updated: agentId={}, apiName={}", agentId, current.getApiName());
        }

        // 失效 runtime 模板缓存
        notifyTemplateInvalidation(agentId, existing.getVersion(), tenantId);
        if (versionBumped) {
            log.info("Agent fully updated with new version: id={}, version={}", agentId, targetVersion);
        } else {
            log.info("Agent fully updated: id={}", agentId);
        }
    }

    /**
     * 更新智能体定义。
     *
     * <p>DRAFT/REJECTED：直接更新。PUBLISHED：允许更新但自动递增版本，创建新版本配置快照。ARCHIVED：不可修改。
     *
     * @deprecated 请改用 {@link #update(Long, Long, AgentUpdateRequest)} 以同时处理 bindings / config / apiConfig 整体替换。
     */
    @Deprecated
    @Transactional(rollbackFor = Exception.class)
    public void update(AgentDef def) {
        AgentDef existing = requireAgent(def.getId(), def.getTenantId());
        if (existing.getLifeStatus() == AgentLifeStatus.ARCHIVED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "智能体已归档，不可修改: " + existing.getLifeStatus());
        }
        // REVIEWING 状态阻断编辑——审核中的智能体不可修改，防止审核与编辑竞态
        if (existing.getLifeStatus() == AgentLifeStatus.REVIEWING) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "智能体审核中，不可修改。请先撤回审核申请或等待审核完成。");
        }
        // 不允许修改编码与创建者
        def.setAgentCode(existing.getAgentCode());
        def.setAuthorUserId(existing.getAuthorUserId());
        def.setAuthorDeptId(existing.getAuthorDeptId());
        // 乐观锁：从数据库当前值回填，确保 MyBatis-Plus @Version 生效
        def.setLockVersion(existing.getLockVersion());
        // 租户隔离：强制本租户可见，禁止通过更新绕过限制
        def.setVisibility(com.aegis.core.enums.common.Visibility.TENANT);
        // 禁止通过更新改为通用智能体
        if (def.getAgentType() == AgentType.UNIVERSAL) {
            def.setAgentType(AgentType.APPLICATION);
        }
        // XSS 清洗：对用户输入文本字段进行 HTML 转义
        if (def.getAgentName() != null) def.setAgentName(XssSanitizer.sanitize(def.getAgentName(), 200));
        if (def.getDescription() != null) def.setDescription(XssSanitizer.sanitize(def.getDescription(), 1000));
        if (def.getIcon() != null) def.setIcon(XssSanitizer.sanitize(def.getIcon(), 500));
        if (def.getColor() != null) def.setColor(XssSanitizer.sanitize(def.getColor(), 50));

        // PUBLISHED 状态更新：递增版本 + 创建新版本配置快照
        if (existing.getLifeStatus() == AgentLifeStatus.PUBLISHED) {
            String newVersion = bumpVersion(existing.getVersion(), existing.getLifeStatus());
            AgentConfig currentCfg = agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                    .eq(AgentConfig::getAgentId, def.getId())
                    .eq(AgentConfig::getVersion, existing.getVersion())
                    .last("LIMIT 1"));
            if (currentCfg != null) {
                currentCfg.setId(null);
                currentCfg.setVersion(newVersion);
                agentConfigMapper.insert(currentCfg);
            }
            def.setVersion(newVersion);
            def.setLifeStatus(AgentLifeStatus.PUBLISHED);
            agentDefMapper.updateById(def);
            notifyTemplateInvalidation(def.getId(), null, def.getTenantId());
            log.info("Agent updated with new version: id={}, version={}", def.getId(), newVersion);
        } else {
            agentDefMapper.updateById(def);
            log.info("Agent updated: id={}", def.getId());
        }
    }

    /**
     * 更新智能体配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(AgentConfig config) {
        AgentDef existing = requireAgent(config.getAgentId(), config.getTenantId());
        if (existing.getLifeStatus() == AgentLifeStatus.ARCHIVED) {
            throw new BusinessException(ResultCode.CONFLICT, "智能体已归档，不可修改配置");
        }

        if (existing.getLifeStatus() == AgentLifeStatus.PUBLISHED) {
            String newVersion = bumpVersion(existing.getVersion(), existing.getLifeStatus());
            config.setId(null);
            config.setAgentId(existing.getId());
            config.setVersion(newVersion);
            config.setTenantId(existing.getTenantId());
            agentConfigMapper.insert(config);
            agentDefMapper.update(null, new LambdaUpdateWrapper<AgentDef>()
                    .eq(AgentDef::getId, existing.getId())
                    .set(AgentDef::getVersion, newVersion));
            notifyTemplateInvalidation(config.getAgentId(), null, config.getTenantId());
            log.info("AgentConfig updated with new version: agentId={}, newVersion={}", config.getAgentId(), newVersion);
        } else {
            AgentConfig current = agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                    .eq(AgentConfig::getAgentId, config.getAgentId())
                    .eq(AgentConfig::getVersion, existing.getVersion())
                    .last("LIMIT 1"));
            if (current == null) {
                config.setAgentId(existing.getId());
                config.setVersion(existing.getVersion());
                agentConfigMapper.insert(config);
            } else {
                config.setId(current.getId());
                config.setVersion(current.getVersion());
                agentConfigMapper.updateById(config);
            }
            log.info("AgentConfig updated: agentId={}, version={}", config.getAgentId(), config.getVersion());
            notifyTemplateInvalidation(config.getAgentId(), existing.getVersion(), config.getTenantId());
        }
    }

    /**
     * 归档下线智能体（PUBLISHED → ARCHIVED）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long tenantId, Long agentId) {
        AgentDef existing = requireAgent(agentId, tenantId);
        if (existing.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT, "仅已发布智能体可归档，当前状态: " + existing.getLifeStatus());
        }
        agentDefMapper.update(null, new LambdaUpdateWrapper<AgentDef>()
                .eq(AgentDef::getId, agentId)
                .set(AgentDef::getLifeStatus, AgentLifeStatus.ARCHIVED)
                .set(AgentDef::getArchivedTime, LocalDateTime.now()));
        log.info("Agent archived: id={}", agentId);
    }

    /**
     * 删除智能体（DRAFT / REJECTED 可删除，PUBLISHED / ARCHIVED 不可删除）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long tenantId, Long agentId) {
        AgentDef existing = requireAgent(agentId, tenantId);
        if (existing.getLifeStatus() != AgentLifeStatus.DRAFT
                && existing.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "仅草稿/已拒绝状态智能体可删除，当前状态: " + existing.getLifeStatus());
        }
        agentDefMapper.deleteById(agentId);
        agentConfigMapper.delete(new LambdaQueryWrapper<AgentConfig>().eq(AgentConfig::getAgentId, agentId));
        agentBindingMapper.delete(new LambdaQueryWrapper<AgentBinding>().eq(AgentBinding::getAgentId, agentId));
        agentApiMapper.delete(new LambdaQueryWrapper<AgentApi>().eq(AgentApi::getAgentId, agentId));
        log.info("Agent deleted: id={}, previousStatus={}", agentId, existing.getLifeStatus());
    }

    /**
     * 查询智能体详情（含配置与资源绑定）。
     *
     * @param tenantId 租户ID
     * @param agentId  智能体ID
     * @param userId   当前用户ID（可选，传入时填充 subscribed 字段）
     */
    public AgentVO getDetail(Long tenantId, Long agentId, Long userId) {
        AgentDef def = requireAgent(agentId, tenantId);
        AgentConfig cfg = agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getAgentId, agentId)
                .eq(AgentConfig::getVersion, def.getVersion())
                .last("LIMIT 1"));
        List<AgentBinding> bindings = agentBindingMapper.selectList(new LambdaQueryWrapper<AgentBinding>()
                .eq(AgentBinding::getAgentId, agentId)
                .eq(AgentBinding::getAgentVersion, def.getVersion()));

        AgentVO.AgentVOBuilder voBuilder = AgentVO.builder()
                .id(def.getId())
                .tenantId(def.getTenantId())
                .agentCode(def.getAgentCode())
                .agentName(def.getAgentName())
                .agentType(def.getAgentType())
                .icon(def.getIcon())
                .color(def.getColor())
                .description(def.getDescription())
                .category(def.getCategory())
                .governanceTier(def.getGovernanceTier())
                .lifeStatus(def.getLifeStatus())
                .version(def.getVersion())
                .authorUserId(def.getAuthorUserId())
                .subsCount(def.getSubsCount())
                .publishedTime(def.getPublishedTime())
                .createTime(def.getCreateTime());

        if (cfg != null) {
            voBuilder.systemPrompt(cfg.getSystemPrompt())
                    .modelTier(cfg.getModelTier() != null ? cfg.getModelTier().name() : null)
                    .temperature(cfg.getTemperature())
                    .maxTurns(cfg.getMaxTurns())
                    .memoryStrategy(cfg.getMemoryStrategy() != null ? cfg.getMemoryStrategy().name() : null)
                    .enabledTools(cfg.getEnabledTools());
        }

        if (bindings != null && !bindings.isEmpty()) {
            List<AgentVO.BindingVO> bindingVOs = bindings.stream()
                    .map(b -> AgentVO.BindingVO.builder()
                            .id(b.getId())
                            .resourceType(b.getResourceType() != null ? b.getResourceType().name() : null)
                            .resourceId(b.getResourceId())
                            .resourceVersion(b.getResourceVersion())
                            .bindingType(b.getBindingType() != null ? b.getBindingType().name() : null)
                            .enabled(b.getEnabled())
                            .build())
                    .toList();
            voBuilder.bindings(bindingVOs);
        }

        // 填充订阅状态（仅当传入 userId 时）
        if (userId != null) {
            voBuilder.subscribed(subscriptionService.isSubscribed(tenantId, agentId, userId));
        }

        return voBuilder.build();
    }

    /**
     * 查询智能体配置。
     */
    public AgentConfig getConfig(Long tenantId, Long agentId, String version) {
        requireAgent(agentId, tenantId);
        return agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getAgentId, agentId)
                .eq(version != null, AgentConfig::getVersion, version)
                .orderByDesc(AgentConfig::getVersion)
                .last("LIMIT 1"));
    }

    /**
     * 分页查询智能体（排除通用智能体，通用智能体由平台预置单例常驻）。
     */
    public Page<AgentDef> page(Long tenantId, AgentLifeStatus lifeStatus,
                              String keyword, int page, int size) {
        Page<AgentDef> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<AgentDef> wrapper = new LambdaQueryWrapper<AgentDef>()
                .eq(tenantId != null, AgentDef::getTenantId, tenantId)
                .eq(lifeStatus != null, AgentDef::getLifeStatus, lifeStatus)
                .ne(AgentDef::getAgentType, AgentType.UNIVERSAL)
                .like(keyword != null && !keyword.isEmpty(), AgentDef::getAgentName, keyword)
                .orderByDesc(AgentDef::getCreateTime);
        return agentDefMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 智能体统计（各生命状态数量）。
     */
    public Map<String, Object> stats(Long tenantId) {
        Map<String, Object> stats = new HashMap<>();
        long draftCount = agentDefMapper.selectCount(new LambdaQueryWrapper<AgentDef>()
                .eq(AgentDef::getLifeStatus, AgentLifeStatus.DRAFT)
                .eq(tenantId != null, AgentDef::getTenantId, tenantId)
                .ne(AgentDef::getAgentType, AgentType.UNIVERSAL));
        long publishedCount = agentDefMapper.selectCount(new LambdaQueryWrapper<AgentDef>()
                .eq(AgentDef::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .eq(tenantId != null, AgentDef::getTenantId, tenantId)
                .ne(AgentDef::getAgentType, AgentType.UNIVERSAL));
        long archivedCount = agentDefMapper.selectCount(new LambdaQueryWrapper<AgentDef>()
                .eq(AgentDef::getLifeStatus, AgentLifeStatus.ARCHIVED)
                .eq(tenantId != null, AgentDef::getTenantId, tenantId)
                .ne(AgentDef::getAgentType, AgentType.UNIVERSAL));
        stats.put("draft", draftCount);
        stats.put("published", publishedCount);
        stats.put("archived", archivedCount);
        stats.put("total", draftCount + publishedCount + archivedCount);
        return stats;
    }

    /**
     * 查询当前租户的通用智能体（平台预置，每租户唯一）。
     *
     * <p>通用智能体由 {@link com.aegis.admin.infrastructure.startup.TenantBootstrapService}
     * 在应用启动时自动创建，用户不可创建/编辑/删除。</p>
     *
     * @param tenantId 租户ID
     * @return 通用智能体（可能为 null，若 bootstrap 未执行或租户未初始化）
     */
    public AgentVO getUniversalAgent(Long tenantId) {
        AgentDef def = agentDefMapper.selectOne(new LambdaQueryWrapper<AgentDef>()
                .eq(AgentDef::getTenantId, tenantId)
                .eq(AgentDef::getAgentType, AgentType.UNIVERSAL)
                .last("LIMIT 1"));
        if (def == null) {
            log.warn("Universal agent not found for tenantId={}", tenantId);
            return null;
        }
        return AgentVO.builder()
                .id(def.getId()).tenantId(def.getTenantId()).agentCode(def.getAgentCode())
                .agentName(def.getAgentName()).agentType(def.getAgentType())
                .icon(def.getIcon()).color(def.getColor()).description(def.getDescription())
                .category(def.getCategory()).governanceTier(def.getGovernanceTier())
                .lifeStatus(def.getLifeStatus()).version(def.getVersion())
                .authorUserId(def.getAuthorUserId()).subsCount(def.getSubsCount())
                .publishedTime(def.getPublishedTime()).createTime(def.getCreateTime())
                .build();
    }

    // ============ 审核流程 ============

    /**
     * 提交审核（DRAFT/REJECTED -> REVIEWING）。
     */
    public Long submitReview(Long tenantId, Long agentId, Long userId) {
        AgentDef agent = requireAgent(agentId, tenantId);
        if (!agent.getAuthorUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅创建者可提交审核");
        }
        if (agent.getLifeStatus() != AgentLifeStatus.DRAFT
                && agent.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "智能体当前状态不可提交审核: " + agent.getLifeStatus());
        }
        return reviewProcessEngine.submit(tenantId, ResourceType.AGENT.name(), agentId);
    }

    /**
     * 审核通过（REVIEWING -> PUBLISHED）。
     *
     * <p>按类型分派解锁动作：
     * <ul>
     *   <li>应用智能体 → 市场可见、允许订阅；</li>
     *   <li>系统智能体 → 自动匹配沙箱池 + 启用对外 API（status=NORMAL）；</li>
     *   <li>通用智能体 → 直接发布。</li>
     * </ul>
     */
    public void approveReview(Long agentId, Long approverId, Long reviewId) {
        if (reviewId == null) {
            ResourceReview review = findPendingReview(agentId);
            reviewId = review.getId();
        }

        // 在审核通过前读取当前状态，判断是否为首次发布（DRAFT → PUBLISHED）
        AgentDef def = agentDefMapper.selectById(agentId);
        boolean isFirstPublish = def != null && def.getLifeStatus() == AgentLifeStatus.DRAFT;
        String oldVersion = def != null ? def.getVersion() : null;

        reviewProcessEngine.approve(reviewId, approverId);

        // 首次发布版本号递增——DRAFT 状态版本为 0.0.x，审核通过时递增为 1.0.0
        if (isFirstPublish) {
            String newVersion = bumpVersion(oldVersion, AgentLifeStatus.DRAFT);
            agentDefMapper.update(null, new LambdaUpdateWrapper<AgentDef>()
                    .eq(AgentDef::getId, agentId)
                    .set(AgentDef::getVersion, newVersion)
                    .set(AgentDef::getPublishedTime, LocalDateTime.now()));
            log.info("P1-4: 首次发布版本递增: agentId={}, oldVersion={}, newVersion={}",
                    agentId, oldVersion, newVersion);
        }

        // 执行类型特定副作用（SYSTEM: 沙箱匹配+API启用+APIKey生成）
        handlePostApprovalSystemEffects(agentId);
    }

    /**
     * SYSTEM 智能体审核通过后副作用：沙箱池匹配 + API 启用 + API Key 生成。
     *
     * <p>从 {@code approveReview()} 抽取为独立公开方法，使 {@code ReviewController}
     * 的通用审核路径也能触发 SYSTEM 特定副作用，修复 BUG-1（通用审核路径绕过 SYSTEM 副作用）。
     *
     * <p>非 SYSTEM 类型智能体调用此方法为空操作。
     *
     * @param agentId 智能体ID
     */
    public void handlePostApprovalSystemEffects(Long agentId) {
        AgentDef def = agentDefMapper.selectById(agentId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "智能体不存在: " + agentId);
        }

        if (def.getAgentType() == AgentType.SYSTEM) {
            SandboxPool matchedPool = sandboxPoolMatcher.match(
                    def.getTenantId(), def.getGovernanceTier(), null);
            AgentApi api = ensureSystemAgentApi(def, matchedPool);
            log.info("System agent approved with sandbox+API: agentId={}, apiId={}, pool={}, tier={}",
                    agentId, api != null ? api.getId() : null,
                    matchedPool != null ? matchedPool.getPoolCode() : "N/A", def.getGovernanceTier());
        }
    }

    /**
     * 幂等初始化/修复系统智能体的对外 API 配置（供管理端手动触发）。
     *
     * <p>场景：创建/审核链路因异常中断或历史数据缺失，导致"智能体已发布但 agent_api 无有效记录"，
     * 前端 API 管理页显示"API 未配置"。本方法提供自愈入口：
     * <ul>
     *   <li>已有有效记录 -> 启用并补齐沙箱池/API Key（幂等）；</li>
     *   <li>仅有逻辑删除残留行 -> 恢复复用（规避 uk_agent_api_path 唯一键冲突）；</li>
     *   <li>完全无记录 -> 按默认值补建（NORMAL + API Key + 沙箱池匹配）。</li>
     * </ul>
     *
     * @param agentId 智能体ID
     * @return 保障可用后的 AgentApi
     */
    public AgentApi initSystemAgentApi(Long agentId) {
        AgentDef def = agentDefMapper.selectById(agentId);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "智能体不存在: " + agentId);
        }
        if (def.getAgentType() != AgentType.SYSTEM) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "仅系统智能体（SYSTEM）支持 API 发布，当前类型: " + def.getAgentType());
        }
        SandboxPool matchedPool = sandboxPoolMatcher.match(
                def.getTenantId(), def.getGovernanceTier(), null);
        AgentApi api = ensureSystemAgentApi(def, matchedPool);
        log.info("System agent API init/repair: agentId={}, apiId={}, status={}, pool={}",
                agentId, api.getId(), api.getStatus(),
                matchedPool != null ? matchedPool.getPoolCode() : "N/A");
        return api;
    }

    /**
     * 保障 SYSTEM 智能体的 agent_api 记录可用（启用 + 沙箱池 + API Key），缺失则补建。
     *
     * <p>三层防御：
     * <ol>
     *   <li>存在有效记录（deleted=0）-> 启用 + 匹配沙箱池 + 补 API Key；</li>
     *   <li>仅存在逻辑删除残留行（deleted=1，删除智能体遗留）-> 恢复复用该行，
     *       规避 {@code uk_agent_api_path(tenant_id, api_path)} 唯一键冲突导致的补建失败；</li>
     *   <li>完全无记录 -> 按默认值补建一条 NORMAL 记录（含 API Key 与沙箱池）。</li>
     * </ol>
     *
     * @param def         智能体定义（含 agentCode/agentName/tenantId）
     * @param matchedPool 已匹配的沙箱池（可为 null，置 PENDING）
     * @return 保障后的 AgentApi
     */
    private AgentApi ensureSystemAgentApi(AgentDef def, SandboxPool matchedPool) {
        Long agentId = def.getId();

        // 1. 有效记录：启用 + 沙箱池 + API Key
        AgentApi api = agentApiMapper.selectOne(new LambdaQueryWrapper<AgentApi>()
                .eq(AgentApi::getAgentId, agentId)
                .last("LIMIT 1"));
        if (api != null) {
            api.setStatus(CommonStatus.NORMAL);
            applyPool(api, matchedPool);
            if (api.getApiKey() == null || api.getApiKey().isEmpty()) {
                api.setApiKey(generateApiKey());
            }
            agentApiMapper.updateById(api);
            return api;
        }

        // 2. 逻辑删除残留行：恢复复用（规避唯一键冲突）
        AgentApi legacy = agentApiMapper.selectLatestIncludeDeleted(agentId);
        if (legacy != null) {
            agentApiMapper.reviveById(legacy.getId(), CommonStatus.NORMAL.name());
            // revive 已置 deleted=0/status=NORMAL，此处补齐业务字段
            legacy.setStatus(CommonStatus.NORMAL);
            applyPool(legacy, matchedPool);
            if (legacy.getApiKey() == null || legacy.getApiKey().isEmpty()) {
                legacy.setApiKey(generateApiKey());
            }
            if (legacy.getApiName() == null || legacy.getApiName().isBlank()) {
                legacy.setApiName(def.getAgentName() + " API");
            }
            agentApiMapper.updateById(legacy);
            log.info("Revived legacy AgentApi record for system agent: agentId={}, apiId={}",
                    agentId, legacy.getId());
            return legacy;
        }

        // 3. 完全无记录：按默认值补建
        AgentApi created = AgentApi.builder()
                .agentId(agentId)
                .apiName(def.getAgentName() + " API")
                .apiPath("/api/v1/agent/" + def.getAgentCode() + "/invoke")
                .httpMethod("POST")
                .authType(ApiAuthType.API_KEY)
                .responseMode(ApiResponseMode.SYNC)
                .rateLimit(20)
                .timeout(30)
                .status(CommonStatus.NORMAL)
                .reservedReplicas(1)
                .version("1.0.0")
                .apiKey(generateApiKey())
                .build();
        created.setTenantId(def.getTenantId());
        applyPool(created, matchedPool);
        agentApiMapper.insert(created);
        log.info("Auto-created AgentApi for system agent: agentId={}, pool={}",
                agentId, matchedPool != null ? matchedPool.getPoolCode() : "N/A");
        return created;
    }

    /** 沙箱池信息落到 API 记录（无匹配池时置 PENDING，不阻断启用） */
    private void applyPool(AgentApi api, SandboxPool matchedPool) {
        if (matchedPool != null) {
            api.setDeploymentPoolCode(matchedPool.getPoolCode());
            api.setPoolAllocateStatus("ALLOCATED");
            api.setAllocateTime(LocalDateTime.now());
        } else {
            api.setPoolAllocateStatus("PENDING");
        }
    }

    /** 生成 API Key（aegis_ 前缀 UUID） */
    private String generateApiKey() {
        return "aegis_" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 审核驳回（REVIEWING -> REJECTED）。
     */
    public void rejectReview(Long agentId, Long approverId, String reason, Long reviewId) {
        if (reviewId == null) {
            ResourceReview review = findPendingReview(agentId);
            reviewId = review.getId();
        }
        reviewProcessEngine.reject(reviewId, approverId, reason);
    }

    /**
     * 查询智能体审核历史。
     */
    public List<ResourceReview> listReviews(Long agentId) {
        return resourceReviewMapper.selectList(new LambdaQueryWrapper<ResourceReview>()
                .eq(ResourceReview::getResourceType, ResourceType.AGENT)
                .eq(ResourceReview::getResourceId, agentId)
                .orderByDesc(ResourceReview::getSubmitTime));
    }

    /**
     * 查找智能体最近的待审核审核单。
     */
    private ResourceReview findPendingReview(Long agentId) {
        ResourceReview review = resourceReviewMapper.selectOne(new LambdaQueryWrapper<ResourceReview>()
                .eq(ResourceReview::getResourceType, ResourceType.AGENT)
                .eq(ResourceReview::getResourceId, agentId)
                .eq(ResourceReview::getReviewStatus, ReviewStatus.PENDING)
                .orderByDesc(ResourceReview::getSubmitTime)
                .last("LIMIT 1"));
        if (review == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "未找到待审核的审核单");
        }
        return review;
    }

    // ============ 内部方法 ============

    private AgentDef requireAgent(Long agentId, Long tenantId) {
        AgentDef def = agentDefMapper.selectById(agentId);
        if (def == null) {
            log.warn("Agent not found: agentId={}, tenantId={}", agentId, tenantId);
            throw new BusinessException(ResultCode.NOT_FOUND, "智能体不存在: " + agentId);
        }
        if (tenantId != null && def.getTenantId() != null && !tenantId.equals(def.getTenantId())) {
            log.warn("Tenant mismatch: agentId={}, expectedTenant={}, actualTenant={}",
                    agentId, tenantId, def.getTenantId());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该智能体");
        }
        return def;
    }

    /**
     * 版本号递增。
     * 草稿版本 0.0.x → 首次发布 1.0.0
     * 已发布版本 x.y.z → 重新发布 x.(y+1).0
     */
    private String bumpVersion(String currentVersion, AgentLifeStatus currentStatus) {
        if (currentStatus == AgentLifeStatus.DRAFT) {
            return "1.0.0";
        }
        try {
            String[] parts = currentVersion.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major + "." + (minor + 1) + ".0";
            }
        } catch (Exception e) {
            log.warn("Invalid version format, fallback: {}", currentVersion);
        }
        return currentVersion;
    }

    private ModelTier parseModelTier(String value, ModelTier defaultTier) {
        if (value == null || value.isEmpty()) return defaultTier;
        try {
            return ModelTier.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid ModelTier value: {}, fallback to {}", value, defaultTier);
            return defaultTier;
        }
    }

    private MemoryStrategy parseMemoryStrategy(String value, MemoryStrategy defaultStrategy) {
        if (value == null || value.isEmpty()) return defaultStrategy;
        try {
            return MemoryStrategy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid MemoryStrategy value: {}, fallback to {}", value, defaultStrategy);
            return defaultStrategy;
        }
    }

    private ResourceType parseResourceType(String value) {
        if (value == null || value.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "资源类型不能为空");
        }
        // 向后兼容：旧的 "MCP" 类型映射为 "MCP_SERVICE"
        if ("MCP".equalsIgnoreCase(value)) {
            return ResourceType.MCP_SERVICE;
        }
        try {
            return ResourceType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的资源类型: " + value);
        }
    }

    private BindingType parseBindingType(String value, BindingType defaultType) {
        if (value == null || value.isEmpty()) return defaultType;
        try {
            return BindingType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid BindingType value: {}, fallback to {}", value, defaultType);
            return defaultType;
        }
    }

    private String toLongJsonArray(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 归一化 JSON schema 字符串：空串/纯空白 → null（MySQL JSON 列不接受空串，仅接受 NULL 或合法 JSON）。
     *
     * <p>agent_api.request_schema / response_schema 为 MySQL JSON 类型，传入空串会触发
     * {@code MysqlDataTruncation: Invalid JSON text: "The document is empty."}。
     * 前端创建系统智能体时若未填写 schema，DTO 字段为空串而非 null，此处统一兜底。
     */
    private String normalizeJsonSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        return schema.trim();
    }
}
