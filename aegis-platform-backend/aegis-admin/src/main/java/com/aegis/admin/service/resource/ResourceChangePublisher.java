package com.aegis.admin.service.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理控制台侧资源变更事件发布实现。
 *
 * <p>通过 Redis pub/sub 发布资源变更事件到频道 {@code aegis:resource:changed:*}，
 * 通知运行时（aegis-runtime）所有节点刷新本地资源缓存。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class ResourceChangePublisher {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String CHANNEL_BASE = "aegis:resource:changed";

    public ResourceChangePublisher(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 发布 MCP 订阅变更事件。
     *
     * @param tenantId  租户ID（平台级操作可传 null）
     * @param userId    用户ID（平台级操作可传 null）
     * @param eventType 事件类型（SUBSCRIBE / UNSUBSCRIBE / ACTIVATE / DEACTIVATE）
     */
    public void publishMcpSubscriptionChanged(Long tenantId, Long userId, String eventType) {
        try {
            Map<String, Object> event = new HashMap<>(8);
            event.put("resourceType", "MCP_SUBSCRIPTION");
            event.put("tenantId", tenantId);
            event.put("userId", userId);
            event.put("eventType", eventType);
            event.put("timestamp", System.currentTimeMillis());

            String body = objectMapper.writeValueAsString(event);
            String channel = CHANNEL_BASE + ":mcp:subscription";

            RTopic topic = redissonClient.getTopic(channel);
            long subscribers = topic.publish(body);
            log.info("MCP订阅变更事件已发布: channel={}, tenantId={}, userId={}, eventType={}, subscribers={}",
                    channel, tenantId, userId, eventType, subscribers);
        } catch (JsonProcessingException e) {
            log.error("发布MCP订阅变更事件序列化失败: tenantId={}, userId={}", tenantId, userId, e);
        } catch (Exception e) {
            log.error("发布MCP订阅变更事件异常: tenantId={}, userId={}", tenantId, userId, e);
        }
    }

    /**
     * 发布技能订阅变更事件。
     *
     * @param tenantId  租户ID
     * @param userId    订阅者ID
     * @param eventType 事件类型（SUBSCRIBE / UNSUBSCRIBE）
     */
    public void publishSkillSubscriptionChanged(Long tenantId, Long userId, String eventType) {
        try {
            Map<String, Object> event = new HashMap<>(8);
            event.put("resourceType", "SKILL_SUBSCRIPTION");
            event.put("tenantId", tenantId);
            event.put("userId", userId);
            event.put("eventType", eventType);
            event.put("timestamp", System.currentTimeMillis());

            String body = objectMapper.writeValueAsString(event);
            String channel = CHANNEL_BASE + ":skill:subscription";

            RTopic topic = redissonClient.getTopic(channel);
            long subscribers = topic.publish(body);
            log.info("技能订阅变更事件已发布: channel={}, tenantId={}, userId={}, eventType={}, subscribers={}",
                    channel, tenantId, userId, eventType, subscribers);
        } catch (JsonProcessingException e) {
            log.error("发布技能订阅变更事件序列化失败: tenantId={}, userId={}", tenantId, userId, e);
        } catch (Exception e) {
            log.error("发布技能订阅变更事件异常: tenantId={}, userId={}", tenantId, userId, e);
        }
    }

    /**
     * 发布智能体发布事件。
     *
     * @param tenantId 租户ID
     * @param agentId  智能体ID
     * @param version  发布版本
     */
    public void publishAgentPublished(Long tenantId, Long agentId, String version) {
        try {
            Map<String, Object> event = new HashMap<>(8);
            event.put("resourceType", "AGENT_PUBLISHED");
            event.put("tenantId", tenantId);
            event.put("agentId", agentId);
            event.put("version", version);
            event.put("eventType", "PUBLISH");
            event.put("timestamp", System.currentTimeMillis());

            String body = objectMapper.writeValueAsString(event);
            String channel = CHANNEL_BASE + ":agent:published";

            RTopic topic = redissonClient.getTopic(channel);
            long subscribers = topic.publish(body);
            log.info("智能体发布事件已发布: channel={}, tenantId={}, agentId={}, version={}, subscribers={}",
                    channel, tenantId, agentId, version, subscribers);
        } catch (JsonProcessingException e) {
            log.error("发布智能体发布事件序列化失败: tenantId={}, agentId={}", tenantId, agentId, e);
        } catch (Exception e) {
            log.error("发布智能体发布事件异常: tenantId={}, agentId={}", tenantId, agentId, e);
        }
    }

    /**
     * 发布绑定变更事件。
     *
     * @param tenantId 租户ID
     * @param agentId  智能体ID
     * @param version  绑定版本
     */
    public void publishBindingChanged(Long tenantId, Long agentId, String version) {
        try {
            Map<String, Object> event = new HashMap<>(8);
            event.put("resourceType", "BINDING");
            event.put("tenantId", tenantId);
            event.put("agentId", agentId);
            event.put("version", version);
            event.put("eventType", "BINDING_CHANGED");
            event.put("timestamp", System.currentTimeMillis());

            String body = objectMapper.writeValueAsString(event);
            String channel = CHANNEL_BASE + ":binding";

            RTopic topic = redissonClient.getTopic(channel);
            long subscribers = topic.publish(body);
            log.info("绑定变更事件已发布: channel={}, tenantId={}, agentId={}, version={}, subscribers={}",
                    channel, tenantId, agentId, version, subscribers);
        } catch (JsonProcessingException e) {
            log.error("发布绑定变更事件序列化失败: tenantId={}, agentId={}", tenantId, agentId, e);
        } catch (Exception e) {
            log.error("发布绑定变更事件异常: tenantId={}, agentId={}", tenantId, agentId, e);
        }
    }
}
