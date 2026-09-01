package com.aegis.runtime.infrastructure;

import org.springframework.stereotype.Component;

/**
 * Nacos 配置变更监听器。
 *
 * <p>订阅 Nacos 配置中心变更，作为配置热更新的<b>主通道</b>（秒级推送），
 * 收到变更后失效 {@link CacheManager} 中对应本地缓存，触发 Layer 1 池化对象刷新。
 * 与 {@link MqEventConsumer}（兜底通道）配合，保证配置变更最终一致。
 *
 * <h3>双通道容错</h3>
 * <ul>
 *   <li>主通道（本类）：Nacos 监听，秒级推送，准实时</li>
 *   <li>兜底通道（{@link MqEventConsumer}）：MQ 消费，Nacos 漏推时补偿</li>
 *   <li>幂等去重：两通道按 configType + configKey + version 去重，避免重复失效</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>订阅 dataId：aegis-runtime.yaml（全局）+ tenant:{id}:runtime.yaml（租户级）</li>
 *   <li>失效粒度：按 configKey 精确失效，configKey 为 null 时按 configType 批量失效</li>
 *   <li>异常隔离：监听回调异常不影响后续监听，记录告警</li>
 * </ul>
 *
 * @author wang.zhen
 * @see MqEventConsumer
 * @see com.aegis.core.event.ConfigChangedEvent
 */
@Component
public class ConfigChangeListener {

}
