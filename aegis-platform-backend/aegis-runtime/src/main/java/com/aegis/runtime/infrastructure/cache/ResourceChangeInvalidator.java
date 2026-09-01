package com.aegis.runtime.infrastructure.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.runtime.integration.agent.AegisAgentInstanceManager;
import com.aegis.runtime.integration.pool.AgentPoolManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 资源变更失效监听器（P2-8 统一事件总线消费侧）。
 *
 * <p>订阅 Redis pub/sub 频道 {@code aegis:resource:changed:*}，
 * 收到资源变更事件后执行定向失效：
 * <ul>
 *   <li>MCP 订阅变更 → 驱逐该用户在 UNIVERSAL 智能体上的空闲实例（新会话即刻加载新订阅）</li>
 *   <li>技能订阅变更 → 同上</li>
 *   <li>智能体发布/版本变更 → 失效模板缓存（AgentPoolManager.invalidateTemplate）+ 驱逐空闲实例</li>
 * </ul>
 *
 * <p>复用 {@link SecurityPolicyCacheInvalidator} 已验证的 Redis pub/sub 模式，
 * 与安全策略事件总线并行运行，各自独立频道，互不干扰。
 *
 * <p>语义保证：运行中会话沿用旧资源至会话结束，新会话即刻生效。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceChangeInvalidator implements MessageListener {

    private final RedisMessageListenerContainer redisContainer;
    private final ObjectMapper objectMapper;
    private final AgentPoolManager agentPoolManager;
    private final AegisAgentInstanceManager agentInstanceManager;
    private final com.aegis.runtime.service.agent.ResourceQueryService resourceQueryService;

    private static final String CHANNEL_PATTERN = "aegis:resource:changed:*";

    @PostConstruct
    public void init() {
        redisContainer.addMessageListener(this, new PatternTopic(CHANNEL_PATTERN));
        log.info("ResourceChangeInvalidator 已订阅频道: {}", CHANNEL_PATTERN);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            String channel = new String(message.getChannel());

            log.debug("收到资源变更事件: channel={}, body={}", channel, body);

            JsonNode json = objectMapper.readTree(body);
            String resourceType = json.has("resourceType") ? json.get("resourceType").asText() : null;
            Long tenantId = json.has("tenantId") ? json.get("tenantId").asLong() : null;
            String eventType = json.has("eventType") ? json.get("eventType").asText() : null;

            if (resourceType == null) return;

            switch (resourceType) {
                case "MCP_SUBSCRIPTION", "SKILL_SUBSCRIPTION" -> {
                    // 订阅变更：驱逐空闲实例，使新会话 buildAgent 时重新加载订阅资源
                    // tenantId 可能为 null（activate/deactivate 平台级操作），此时全量驱逐
                    log.info("资源订阅变更，驱逐空闲实例: resourceType={}, tenantId={}, eventType={}",
                            resourceType, tenantId, eventType);
                    try {
                        agentInstanceManager.evictIdleInstances();
                    } catch (Exception e) {
                        log.warn("订阅变更后空闲实例驱逐异常: tenantId={}", tenantId, e);
                    }
                }
                case "AGENT_PUBLISH", "AGENT_BINDING" -> {
                    // 智能体发布/绑定变更：失效模板缓存 + 驱逐空闲实例
                    Long agentId = json.has("agentId") ? json.get("agentId").asLong() : null;
                    String version = json.has("version") ? json.get("version").asText() : null;
                    if (agentId != null && tenantId != null) {
                        log.info("智能体变更，失效模板+驱逐实例: agentId={}, version={}, eventType={}",
                                agentId, version, eventType);
                        agentPoolManager.invalidateTemplate(agentId, version, tenantId);
                        try {
                            agentInstanceManager.evictIdleInstances();
                        } catch (Exception e) {
                            log.warn("智能体变更后空闲实例驱逐异常: agentId={}", agentId, e);
                        }
                    }
                }
                case "SKILL_PUBLISHED" -> {
                    // P2-4：技能发布/变更 → 清空 GLOBAL 技能缓存 + 驱逐空闲实例
                    log.info("技能发布变更，清空GLOBAL缓存+驱逐实例: eventType={}", eventType);
                    resourceQueryService.forceReloadGlobalSkills();
                    try {
                        agentInstanceManager.evictIdleInstances();
                    } catch (Exception e) {
                        log.warn("技能变更后空闲实例驱逐异常", e);
                    }
                }
                default -> log.debug("未知资源类型，忽略: resourceType={}", resourceType);
            }

        } catch (Exception e) {
            log.error("资源变更失效处理异常", e);
        }
    }
}
