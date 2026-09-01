package com.aegis.core.enums.api;

import lombok.Getter;
import com.aegis.core.domain.agent.AgentApi;

/**
 * API响应模式枚举。
 *
 * <p>智能体开放API（AgentApi）的响应方式，调用方按需选择。
 * 不同模式对应不同的连接保持策略与超时配置。
 *
 * @author wang.zhen
 */
@Getter
public enum ApiResponseMode {

    /** 同步：请求阻塞等待完整响应，适合短任务，超时30秒 */
    SYNC("同步"),

    /** 异步：提交任务返回taskId，轮询查询结果，适合长任务 */
    ASYNC("异步"),

    /** SSE流式：Server-Sent Events流式输出，适合实时交互场景 */
    SSE("SSE流式");

    /** 模式中文描述，用于日志输出 */
    private final String desc;

    ApiResponseMode(String desc) { this.desc = desc; }
}