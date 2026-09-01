package com.aegis.runtime.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 策略缓存失效监听器。
 *
 * <p>订阅 Redis pub/sub 频道 {@code aegis:security:policy:changed:*}，
 * 收到策略变更事件后立即清除 Caffeine 本地缓存，实现 5s 内全节点策略刷新。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityPolicyCacheInvalidator implements MessageListener {

    private final SecurityPolicyCache policyCache;
    private final RedisMessageListenerContainer redisContainer;
    private final ObjectMapper objectMapper;
    /** P0-2：HITL 事件联动 HITL 规则缓存刷新（AegisHitlRuleLoader 独立 ConcurrentHashMap，不感知 policyCache） */
    private final com.aegis.runtime.integration.middleware.AegisHitlRuleLoader hitlRuleLoader;
    /** P0-2：HITL/策略变更后驱逐空闲 Agent 实例，使新会话 buildAgent 重载最新规则（复用 TTL 通道，不动运行中实例） */
    private final com.aegis.runtime.integration.agent.AegisAgentInstanceManager agentInstanceManager;

    private static final String CHANNEL_PATTERN = "aegis:security:policy:changed:*";

    @PostConstruct
    public void init() {
        redisContainer.addMessageListener(this, new PatternTopic(CHANNEL_PATTERN));
        log.info("SecurityPolicyCacheInvalidator 已订阅频道: {}", CHANNEL_PATTERN);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            String channel = new String(message.getChannel());

            log.debug("收到策略变更事件: channel={}, body={}", channel, body);

            JsonNode json = objectMapper.readTree(body);
            Long tenantId = json.has("tenantId") ? json.get("tenantId").asLong() : null;
            String policyType = json.has("policyType") ? json.get("policyType").asText() : null;
            String eventType = json.has("eventType") ? json.get("eventType").asText() : null;

            if (tenantId == null) return;

            // P0-2：HITL 规则变更（任何 op：CREATE/UPDATE/DELETE/RELOAD）
            // 联动三件事：① policyCache 全租户清除（含 Redis SCAN 真实删除）
            //             ② hitlRuleLoader.forceReload() 清空独立 ruleCache（下次 getCachedNodes 走 DB）
            //             ③ agentInstanceManager.evictIdleInstances() 驱逐空闲池化实例
            //                （复用 TTL 通道 + closeAgent 落盘；运行中实例不动）
            // 语义：运行中会话沿用旧规则至会话结束，新会话即刻生效
            if ("HITL".equals(policyType)) {
                policyCache.evictAll(tenantId);
                hitlRuleLoader.forceReload();
                try {
                    agentInstanceManager.evictIdleInstances();
                } catch (Exception ex) {
                    log.warn("HITL 变更后空闲实例驱逐异常（不影响缓存刷新）: tenantId={}", tenantId, ex);
                }
                log.info("HITL 规则已刷新: tenantId={}, eventType={}, policyId={}（policyCache+hitlRuleLoader+空闲实例）",
                        tenantId, eventType, json.has("policyId") ? json.get("policyId").asLong() : null);
            } else if (policyType != null) {
                // P1-1：admin 发布的 policyType（TOOL/SENSITIVE_WORD/MASK_RULE/OUTBOUND）
                // 映射到缓存 key（TOOL/CONTENT/MASK/OUTBOUND），否则 evict 不命中缓存键
                String cacheKey = mapPolicyTypeToCacheKey(policyType);
                policyCache.evict(tenantId, cacheKey);
                log.info("策略缓存已清除: tenantId={}, policyType={}, cacheKey={}, eventType={}",
                        tenantId, policyType, cacheKey, eventType);
            } else {
                // 无策略类型 → 全量清除
                policyCache.evictAll(tenantId);
                log.info("策略缓存已全量清除: tenantId={}", tenantId);
            }

        } catch (Exception e) {
            log.error("策略缓存失效处理异常", e);
        }
    }

    /**
     * P1-1：将 admin 发布的 policyType 映射到 SecurityPolicyCache 的缓存 key。
     *
     * <p>admin 侧事件 policyType：TOOL / SENSITIVE_WORD / MASK_RULE / OUTBOUND / HITL
     * 缓存 key：TOOL / CONTENT / MASK / OUTBOUND / HITL（与 AegisSecurityPolicyEngine/DataMaskService 一致）
     */
    private String mapPolicyTypeToCacheKey(String policyType) {
        return switch (policyType) {
            case "SENSITIVE_WORD" -> "CONTENT";
            case "MASK_RULE" -> "MASK";
            default -> policyType; // TOOL/OUTBOUND/HITL 与缓存 key 同名
        };
    }
}
