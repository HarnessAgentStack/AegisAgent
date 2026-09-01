package com.aegis.runtime.integration.pool;

import com.aegis.runtime.integration.config.RuntimeProperties;
import com.aegis.dal.mapper.agent.AgentBindingMapper;
import com.aegis.dal.mapper.agent.AgentConfigMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.aegis.dal.mapper.agent.AgentSubscriptionMapper;
import com.aegis.dal.mapper.resource.ToolMapper;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.agent.AgentSubscription;
import com.aegis.core.enums.agent.AgentType;
import com.aegis.core.enums.agent.BindingType;
import com.aegis.core.enums.agent.SubscriptionStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体模板池管理器（Layer 1）。
 *
 * <p>基于 Caffeine 缓存实现智能体运行时模板的池化复用，避免每次会话重复初始化。
 * 模板按 (agentId, version, tenantId) 三元组缓存，版本变更触发新模板加载。
 *
 * <h3>两级池化模型</h3>
 * <ul>
 *   <li>Layer 1（本类）：运行时模板池，Caffeine 缓存，跨会话复用</li>
 *   <li>Layer 2（{@code TaskExecutionService} 派生）：会话级执行上下文，从模板派生</li>
 * </ul>
 *
 * <h3>失效机制</h3>
 * <ul>
 *   <li>自动失效：expireAfterWrite=idle-evict-minutes（P2-8：保证最坏陈旧 ≤ TTL，热缓存不再"永不自动过期"）</li>
 *   <li>手动失效：{@link #invalidateTemplate(Long, String, Long)} 由配置变更触发</li>
 *   <li>容量上限：maximumSize=5000，超出按 LRU 淘汰</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPoolManager {

    private final AgentDefMapper agentDefMapper;
    private final AgentConfigMapper agentConfigMapper;
    private final AgentBindingMapper agentBindingMapper;
    private final ToolMapper toolMapper;
    private final SkillMapper skillMapper;
    private final AgentSubscriptionMapper agentSubscriptionMapper;
    private final RuntimeProperties runtimeProperties;

    /** 模板缓存：key = agentId:version:tenantId */
    private Cache<String, AgentRuntimeTemplate> templateCache;

    // 版本解析缓存（key = agentId:tenantId → version），避免 version=null 时每次查 DB
    private Cache<String, String> versionCache;

    // 动态绑定查询结果缓存（key = userId:agentId → 动态绑定列表），TTL 60 秒
    private final java.util.concurrent.ConcurrentHashMap<String, DynamicBindingCacheEntry> dynamicBindingCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 动态绑定缓存 TTL（毫秒） */
    private static final long DYNAMIC_BINDING_TTL_MS = 60_000L;

    /** 动态绑定缓存条目 */
    private static class DynamicBindingCacheEntry {
        final List<AgentBinding> bindings;
        final long expireAt;
        DynamicBindingCacheEntry(List<AgentBinding> bindings, long expireAt) {
            this.bindings = bindings;
            this.expireAt = expireAt;
        }
    }

    @PostConstruct
    public void init() {
        int maxSize = runtimeProperties.getAgentPool().getMaxPerTenant() * 50;
        this.templateCache = Caffeine.newBuilder()
                .maximumSize(Math.max(maxSize, 5000))
                // P2-8：expireAfterWrite（而非 expireAfterAccess）——保证最坏陈旧 ≤ TTL。
                // expireAfterAccess 衡量"访问距今"（热缓存永不自动过期），
                // expireAfterWrite 衡量"数据距今"（加载后 TTL 必过期重建）。
                .expireAfterWrite(Duration.ofMinutes(runtimeProperties.getAgentPool().getIdleEvictMinutes()))
                .recordStats()
                .removalListener((key, value, cause) ->
                        log.info("AgentRuntimeTemplate evicted: key={}, cause={}", key, cause))
                .build();
        // 版本缓存，短 TTL 30 秒，避免 version=null 时反复查 DB
        this.versionCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build();
        log.info("AgentPoolManager initialized: maxPerTenant={}, maxSize={}, idleEvictMinutes={}",
                runtimeProperties.getAgentPool().getMaxPerTenant(),
                maxSize,
                runtimeProperties.getAgentPool().getIdleEvictMinutes());
    }

    /**
     * 获取或加载 Layer 1 模板。
     *
     * <p>缓存命中则直接返回并更新访问时间；未命中则从 DB 加载定义/配置/绑定，初始化后缓存。
     *
     * @param agentId  智能体ID
     * @param version  智能体版本（可为 null，则取最新版本）
     * @param tenantId 租户ID
     * @return 智能体运行时模板
     */
    public AgentRuntimeTemplate getTemplate(Long agentId, String version, Long tenantId) {
        return getTemplate(agentId, version, tenantId, null);
    }

    /**
     * 获取或加载 Layer 1 模板（支持动态资源加载）。
     *
     * <p>对于 UNIVERSAL 智能体，在加载固定绑定后额外加载用户订阅的动态资源绑定。
     *
     * @param agentId  智能体ID
     * @param version  智能体版本（可为 null，则取最新版本）
     * @param tenantId 租户ID
     * @param userId   用户ID（UNIVERSAL 智能体用于动态加载订阅资源，可为 null）
     * @return 智能体运行时模板
     */
    public AgentRuntimeTemplate getTemplate(Long agentId, String version, Long tenantId, Long userId) {
        String resolvedVersion = resolveVersion(agentId, version, tenantId);
        String cacheKey = buildCacheKey(agentId, resolvedVersion, tenantId);
        AgentRuntimeTemplate template = templateCache.asMap().computeIfAbsent(cacheKey, k -> loadTemplate(agentId, resolvedVersion, tenantId));
        if (template != null) {
            template.touch();
        }

        // UNIVERSAL 智能体：动态合并用户订阅资源
        if (template != null && userId != null
                && template.getAgentDef() != null
                && template.getAgentDef().getAgentType() == AgentType.UNIVERSAL) {
            template = mergeDynamicBindings(template, userId, tenantId);
        }

        return template;
    }

    /**
     * 按租户+版本失效模板缓存。
     *
     * <p>由 Nacos 配置变更通知或管理平面变更触发。
     *
     * @param agentId  智能体ID
     * @param version  版本号（为 null 则失效该 agent 所有版本）
     * @param tenantId 租户ID
     */
    public void invalidateTemplate(Long agentId, String version, Long tenantId) {
        // 同时失效版本缓存，确保配置变更后能重新解析版本
        versionCache.invalidate(agentId + ":" + tenantId);
        if (version == null) {
            // 失效该 agent 所有版本
            String prefix = agentId + ":";
            templateCache.asMap().keySet().stream()
                    .filter(k -> k.startsWith(prefix) && k.endsWith(":" + tenantId))
                    .forEach(templateCache::invalidate);
        } else {
            templateCache.invalidate(buildCacheKey(agentId, version, tenantId));
        }
        log.info("AgentRuntimeTemplate invalidated: agentId={}, version={}, tenantId={}", agentId, version, tenantId);
    }

    /**
     * 获取缓存统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("size", templateCache.asMap().size());
        stats.put("hitRate", templateCache.stats().hitRate());
        stats.put("hitCount", templateCache.stats().hitCount());
        stats.put("missCount", templateCache.stats().missCount());
        stats.put("evictionCount", templateCache.stats().evictionCount());
        return stats;
    }

    // ============ 内部方法 ============

    private String resolveVersion(Long agentId, String version, Long tenantId) {
        if (version != null && !version.isEmpty()) {
            return version;
        }
        // version=null 时优先走版本缓存（TTL 30 秒），避免每次查 DB
        String cacheKey = agentId + ":" + tenantId;
        return versionCache.get(cacheKey, k -> resolveVersionFromDb(agentId, tenantId));
    }

    /**
     * 从 DB 解析智能体最新版本（含租户隔离校验），结果会被版本缓存复用。
     */
    private String resolveVersionFromDb(Long agentId, Long tenantId) {
        Long ctxTenantId = com.aegis.core.common.tenant.TenantContextHolder.getTenantId();
        log.info("resolveVersion: agentId={}, tenantId={}, ThreadLocal tenantId={}", agentId, tenantId, ctxTenantId);
        AgentDef def = agentDefMapper.selectById(agentId);
        if (def == null) {
            throw new IllegalArgumentException("智能体不存在: agentId=" + agentId
                    + ", tenantId=" + tenantId + ", ctxTenantId=" + ctxTenantId);
        }
        if (def.getTenantId() == null) {
            throw new IllegalArgumentException("智能体租户ID为空（数据异常）: agentId=" + agentId);
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("请求缺少租户上下文: agentId=" + agentId);
        }
        if (def.getTenantId().longValue() != tenantId.longValue()) {
            throw new IllegalArgumentException("租户隔离校验失败: agentId=" + agentId
                    + ", agentTenantId=" + def.getTenantId() + ", requestTenantId=" + tenantId);
        }
        return def.getVersion();
    }

    private AgentRuntimeTemplate loadTemplate(Long agentId, String version, Long tenantId) {
        log.info("Loading AgentRuntimeTemplate from DB: agentId={}, version={}, tenantId={}", agentId, version, tenantId);

        // 1. 加载智能体定义（带租户过滤）
        AgentDef agentDef = agentDefMapper.selectById(agentId);
        if (agentDef == null || (tenantId != null && !tenantId.equals(agentDef.getTenantId()))) {
            throw new IllegalArgumentException("智能体不存在或租户隔离校验失败: agentId=" + agentId);
        }

        // 2. 加载智能体配置（按版本）
        AgentConfig agentConfig = agentConfigMapper.selectOne(
                new LambdaQueryWrapper<AgentConfig>()
                        .eq(AgentConfig::getAgentId, agentId)
                        .eq(AgentConfig::getVersion, version)
                        .last("LIMIT 1"));
        if (agentConfig == null) {
            log.warn("AgentConfig not found, use default: agentId={}, version={}", agentId, version);
            agentConfig = buildDefaultConfig(agentId, version, tenantId);
        }

        // 3. 加载资源绑定（P2-9 单一真相源口径：agentId + agentVersion + enabled=true）
        //    模板绑定从此同时是「指纹输入」与「工具注册输入」的唯一来源——
        //    disable 的绑定既不进指纹也不进注册，消除口径分裂（D4）。
        List<AgentBinding> bindings = agentBindingMapper.selectList(
                new LambdaQueryWrapper<AgentBinding>()
                        .eq(AgentBinding::getAgentId, agentId)
                        .eq(AgentBinding::getAgentVersion, version)
                        .eq(AgentBinding::getEnabled, true));
        if (bindings == null) {
            bindings = Collections.emptyList();
        }

        // B2-5: 加载工具注册表（从 bindings 中解析 TOOL 类型资源）
        Map<String, Object> toolRegistry = new HashMap<>();
        List<Long> skillRefs = new ArrayList<>();
        for (AgentBinding binding : bindings) {
            if (binding.getResourceType() == com.aegis.core.enums.resource.ResourceType.TOOL && binding.getResourceId() != null) {
                com.aegis.core.domain.resource.Tool tool = toolMapper.selectById(binding.getResourceId());
                if (tool != null) {
                    toolRegistry.put(String.valueOf(tool.getId()), tool);
                }
            } else if (binding.getResourceType() == com.aegis.core.enums.resource.ResourceType.SKILL && binding.getResourceId() != null) {
                skillRefs.add(binding.getResourceId());
            }
        }

        // 4. 构造模板
        AgentRuntimeTemplate template = AgentRuntimeTemplate.builder()
                .agentId(agentId)
                .version(version)
                .tenantId(tenantId)
                .agentDef(agentDef)
                .agentConfig(agentConfig)
                .bindings(bindings)
                .toolRegistry(toolRegistry)
                .skillRefs(skillRefs)
                .modelRoutes(Collections.emptyMap())
                .createdAt(System.currentTimeMillis())
                .build();
        template.touch();

        log.info("AgentRuntimeTemplate loaded: agentId={}, version={}, bindings={}",
                agentId, version, bindings.size());
        return template;
    }

    private AgentConfig buildDefaultConfig(Long agentId, String version, Long tenantId) {
        AgentConfig config = new AgentConfig();
        config.setTenantId(tenantId);
        config.setAgentId(agentId);
        config.setVersion(version);
        config.setSystemPrompt(
                "你是一个乐于助人的智能助手。\n\n"
                + "## 工具使用守则\n"
                + "1. 专用工具优先: 有专用工具覆盖的场景(天气/股票/汇率/翻译/计算),必须用专用工具。\n"
                + "2. web_search 兜底: 无专用工具覆盖的实时信息查询,用 web_search。\n"
                + "3. http_request 最后手段: 仅在以上都不适用时使用,需说明原因。\n"
                + "4. 调用前简述理由: thinking 中用 ≤30 字说明选择哪个工具及原因。");
        config.setModelTier(com.aegis.core.enums.model.ModelTier.STANDARD);
        config.setTemperature(java.math.BigDecimal.valueOf(0.7));
        config.setMemoryStrategy(com.aegis.core.enums.agent.MemoryStrategy.SESSION_LEVEL);
        config.setMaxTurns(20);
        config.setEnabledTools("[]");
        return config;
    }

    /**
     * 合并动态绑定：查询用户订阅的智能体，将订阅智能体的资源绑定合并为动态绑定。
     */
    private AgentRuntimeTemplate mergeDynamicBindings(AgentRuntimeTemplate template, Long userId, Long tenantId) {
        // 动态绑定查询结果缓存（key=userId:agentId, TTL 60 秒），避免每次查 DB
        String cacheKey = userId + ":" + template.getAgentId();
        List<AgentBinding> dynamicBindings;
        DynamicBindingCacheEntry cached = dynamicBindingCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expireAt) {
            dynamicBindings = cached.bindings;
        } else {
            dynamicBindings = loadDynamicBindings(template, userId, tenantId);
            dynamicBindingCache.put(cacheKey, new DynamicBindingCacheEntry(dynamicBindings,
                    System.currentTimeMillis() + DYNAMIC_BINDING_TTL_MS));
        }

        if (dynamicBindings.isEmpty()) {
            return template;
        }

        // 合并固定 + 动态绑定
        List<AgentBinding> allBindings = new ArrayList<>(template.getBindings());
        allBindings.addAll(dynamicBindings);

        // 返回新模板（不修改缓存中的模板）
        AgentRuntimeTemplate merged = AgentRuntimeTemplate.builder()
                .agentId(template.getAgentId())
                .version(template.getVersion())
                .tenantId(template.getTenantId())
                .agentDef(template.getAgentDef())
                .agentConfig(template.getAgentConfig())
                .bindings(allBindings)
                .toolRegistry(template.getToolRegistry())
                .skillRefs(template.getSkillRefs())
                .modelRoutes(template.getModelRoutes())
                .createdAt(template.getCreatedAt())
                .build();
        merged.touch();

        log.info("UNIVERSAL agent dynamic bindings merged: agentId={}, userId={}, dynamicCount={}",
                template.getAgentId(), userId, dynamicBindings.size());
        return merged;
    }

    /**
     * 从 DB 加载用户的动态绑定（订阅智能体的资源绑定）。
     * 增加 tenantId 过滤，避免跨租户查询与 N+1 全量扫描。
     */
    private List<AgentBinding> loadDynamicBindings(AgentRuntimeTemplate template, Long userId, Long tenantId) {
        // 增加 tenantId 过滤条件，防止跨租户查询
        List<AgentSubscription> subscriptions = agentSubscriptionMapper.selectList(
                new LambdaQueryWrapper<AgentSubscription>()
                        .eq(AgentSubscription::getTenantId, tenantId)
                        .eq(AgentSubscription::getUserId, userId)
                        .eq(AgentSubscription::getStatus, SubscriptionStatus.ACTIVE));

        if (subscriptions == null || subscriptions.isEmpty()) {
            return Collections.emptyList();
        }

        // 加载订阅智能体的资源绑定作为动态绑定
        List<AgentBinding> dynamicBindings = new ArrayList<>();
        for (AgentSubscription sub : subscriptions) {
            // 跳过自身
            if (sub.getAgentId().equals(template.getAgentId())) {
                continue;
            }
            AgentDef subDef = agentDefMapper.selectById(sub.getAgentId());
            if (subDef == null || subDef.getLifeStatus() != com.aegis.core.enums.agent.AgentLifeStatus.PUBLISHED) {
                continue;
            }
            List<AgentBinding> subBindings = agentBindingMapper.selectList(
                    new LambdaQueryWrapper<AgentBinding>()
                            .eq(AgentBinding::getAgentId, sub.getAgentId())
                            .eq(AgentBinding::getAgentVersion, subDef.getVersion()));
            for (AgentBinding b : subBindings) {
                // 标记为动态绑定
                AgentBinding dynamic = AgentBinding.builder()
                        .agentId(template.getAgentId())
                        .agentVersion(template.getVersion())
                        .resourceType(b.getResourceType())
                        .resourceId(b.getResourceId())
                        .resourceVersion(b.getResourceVersion())
                        .bindingType(BindingType.DYNAMIC)
                        .enabled(true)
                        .build();
                dynamic.setTenantId(tenantId);
                dynamicBindings.add(dynamic);
            }
        }
        return dynamicBindings;
    }

    private String buildCacheKey(Long agentId, String version, Long tenantId) {
        return agentId + ":" + version + ":" + tenantId;
    }
}
