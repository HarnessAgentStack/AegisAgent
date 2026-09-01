package com.aegis.core.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 配置变更事件。
 *
 * <p>当管理平面修改运行时配置（智能体配置、模型路由策略、安全策略、限流阈值等）后发布，
 * 运行平面采用 <b>Nacos 配置中心 + 消息队列</b> 双通道通知机制接收变更：
 * Nacos 提供准实时推送（秒级），MQ 作为兜底通道保证最终一致，二者任一触发即失效本地缓存。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>双通知幂等：消费方按 configType + configKey + version 去重，避免重复失效</li>
 *   <li>配置类别（configType）：AGENT_CONFIG / MODEL_ROUTE / SECURITY_POLICY / RATE_LIMIT 等</li>
 *   <li>租户隔离：tenantId 为 null 表示平台级全局配置变更</li>
 *   <li>配置键（configKey）定位具体配置项，如 agentId 或 routeId</li>
 * </ul>
 *
 * <h3>双通道容错</h3>
 * <ul>
 *   <li>主通道：Nacos 配置监听器（{@code ConfigChangeListener}），秒级推送</li>
 *   <li>兜底通道：MQ 消费者（{@code MqEventConsumer}），Nacos 漏推时补偿</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigChangedEvent {

    /** 事件产生时间戳（毫秒） */
    private long timestamp;

    /** 配置类别：AGENT_CONFIG / MODEL_ROUTE / SECURITY_POLICY / RATE_LIMIT 等 */
    private String configType;

    /** 租户ID，null 表示平台级全局配置变更 */
    private Long tenantId;

    /** 配置键，定位具体配置项（如 agentId、routeId），null 表示该类别全量失效 */
    private String configKey;

    /** 配置版本号，单调递增，用于乐观失效与幂等去重 */
    private Long version;
}
