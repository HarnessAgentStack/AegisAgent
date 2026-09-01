package com.aegis.admin.service.security;

import com.aegis.core.dto.security.SecurityConfigPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理控制台侧策略变更事件发布实现。
 *
 * <p>通过 Redis pub/sub 发布策略变更事件到频道 {@code aegis:security:policy:changed:*}，
 * 通知运行时（aegis-runtime）所有节点刷新本地策略缓存。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class AdminSecurityConfigPublisher implements SecurityConfigPublisher {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String CHANNEL_PATTERN = "aegis:security:policy:changed";

    public AdminSecurityConfigPublisher(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void publishPolicyChangedEvent(Long tenantId, String policyType, Long policyId, String eventType) {
        try {
            Map<String, Object> event = new HashMap<>(8);
            event.put("tenantId", tenantId);
            event.put("policyType", policyType);
            event.put("policyId", policyId);
            event.put("eventType", eventType);
            event.put("timestamp", System.currentTimeMillis());

            String body = objectMapper.writeValueAsString(event);
            String channel = CHANNEL_PATTERN + ":" + (policyType != null ? policyType.toLowerCase() : "all");

            RTopic topic = redissonClient.getTopic(channel);
            long subscribers = topic.publish(body);
            log.info("策略变更事件已发布: channel={}, policyType={}, policyId={}, eventType={}, subscribers={}",
                    channel, policyType, policyId, eventType, subscribers);
        } catch (JsonProcessingException e) {
            log.error("发布策略变更事件序列化失败: tenantId={}, policyType={}, policyId={}",
                    tenantId, policyType, policyId, e);
        } catch (Exception e) {
            log.error("发布策略变更事件异常: tenantId={}, policyType={}, policyId={}",
                    tenantId, policyType, policyId, e);
        }
    }

    @Override
    public void publishHitlRuleChangedEvent(Long tenantId, Long agentId) {
        try {
            Map<String, Object> event = new HashMap<>(6);
            event.put("tenantId", tenantId);
            event.put("agentId", agentId);
            event.put("policyType", "HITL");
            event.put("eventType", "RELOAD");
            event.put("timestamp", System.currentTimeMillis());

            String body = objectMapper.writeValueAsString(event);
            String channel = CHANNEL_PATTERN + ":hitl";

            RTopic topic = redissonClient.getTopic(channel);
            long subscribers = topic.publish(body);
            log.info("HITL 规则变更事件已发布: tenantId={}, agentId={}, subscribers={}",
                    tenantId, agentId, subscribers);
        } catch (JsonProcessingException e) {
            log.error("发布 HITL 规则变更事件序列化失败: tenantId={}, agentId={}",
                    tenantId, agentId, e);
        } catch (Exception e) {
            log.error("发布 HITL 规则变更事件异常: tenantId={}, agentId={}",
                    tenantId, agentId, e);
        }
    }
}
