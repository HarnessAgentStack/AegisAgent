package com.aegis.core.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 系统智能体对外 API 调用请求。
 *
 * <p>外部系统通过此 DTO 调用已审核通过的系统智能体。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApiInvokeRequest {

    /** 智能体ID */
    @NotNull(message = "智能体ID不能为空")
    private Long agentId;

    /** 用户输入内容 */
    @NotBlank(message = "输入内容不能为空")
    private String input;

    /** 会话ID（可选，不传则自动创建） */
    private String sessionId;

    /** 外部请求者标识（系统名/调用方） */
    private String callerId;

    /** 额外参数（透传给智能体） */
    private Map<String, Object> extraParams;
}
