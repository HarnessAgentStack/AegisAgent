package com.aegis.runtime.infrastructure;

import org.springframework.stereotype.Component;

/**
 * MQ 事件消费者。
 *
 * <p>订阅平台事件总线，作为配置与资源变更通知的<b>兜底通道</b>：
 * 当 Nacos 配置监听（{@link ConfigChangeListener}）漏推时，由本消费者补偿失效本地缓存，
 * 保证配置与资源变更最终一致。
 *
 * <h3>消费事件</h3>
 * <ul>
 *   <li>{@code ConfigChangedEvent}：兜底失效本地缓存（主通道 Nacos 漏推时补偿）</li>
 *   <li>{@code ResourceChangedEvent}：失效 Layer 1 池化对象（智能体模板/向量索引）</li>
 *   <li>{@code AuditEvent}：异步写入审计日志</li>
 * </ul>
 *
 * @author wang.zhen
 * @see ConfigChangeListener
 */
@Component
public class MqEventConsumer {

}
