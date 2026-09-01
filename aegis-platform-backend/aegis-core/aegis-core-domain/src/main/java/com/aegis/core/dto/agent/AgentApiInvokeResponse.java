package com.aegis.core.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 系统智能体对外 API 调用响应。
 *
 * <p>外部系统调用已审核通过的系统智能体后，由 runtime 服务返回的结构化响应。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApiInvokeResponse {

    /** 请求ID，全链路追踪 */
    private String requestId;

    /** 智能体ID */
    private Long agentId;

    /** 会话ID */
    private String sessionId;

    /** 智能体回答内容 */
    private String answer;

    /** Token 用量等统计信息 */
    private Map<String, Object> usage;

    /** 延迟（毫秒） */
    private Long latencyMs;

    /** 状态：SUCCESS / ERROR / ACCEPTED */
    private String status;

    /** 错误信息（失败时填写） */
    private String errorMessage;
}