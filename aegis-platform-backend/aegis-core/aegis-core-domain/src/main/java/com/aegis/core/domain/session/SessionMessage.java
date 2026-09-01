package com.aegis.core.domain.session;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.session.MessageType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * 会话消息实体
 *
 * <p>会话消息（SessionMessage）记录会话中每一条交互的完整内容，包括文本、推理、工具调用、
 * 知识库引用等，是智能体对话历史与审计追踪的最细粒度记录。</p>
 *
 * <h3>消息类型</h3>
 * <ul>
 *     <li>USER：用户输入消息</li>
 *     <li>ASSISTANT：智能体回复消息</li>
 *     <li>TOOL_CALL：工具调用记录，含 toolName / toolParams / toolResult</li>
 *     <li>SYSTEM：系统消息，如会话状态变更通知</li>
 * </ul>
 *
 * <h3>成本追踪</h3>
 * <p>tokenInput / tokenOutput / costAmount 精确记录每条消息的 token 消耗与费用，
 * 支撑租户级成本核算与预算管控。</p>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，消息随会话一起隔离；
 * seq 字段保证会话内消息顺序，便于回放与展示。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see Session
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sess_message")
public class SessionMessage extends TenantEntity {
    /** 会话 ID，关联 session.session_id，消息所属会话 */
    private String sessionId;
    /** 消息类型：{@link MessageType#USER}（用户消息）/{@link MessageType#ASSISTANT}（助手消息）/{@link MessageType#TOOL_CALL}（工具调用）/{@link MessageType#TOOL_RESULT}（工具结果）等 */
    private MessageType messageType;
    /** 消息内容，文本类型消息的正文，最长 32KB */
    private String content;
    /** 推理过程，智能体思维链（CoT）内容，可选，用于透明化展示 */
    private String reasoning;
    /** 工具调用 ID，当 messageType 为 TOOL_CALL 时生成，用于关联调用与结果 */
    private String toolCallId;
    /** 工具名称，调用的工具标识，关联 tool.tool_code */
    private String toolName;
    /** 工具参数，JSON 字符串，调用工具时传入的参数 */
    private String toolParams;
    /** 工具结果，JSON 字符串，工具执行返回的结果 */
    private String toolResult;
    /** 知识库引用，JSON 数组字符串，记录检索命中的知识库切片 ID 列表 */
    private String kbRefs;
    /** 输入 Token 数，该消息消耗的输入 token，用于成本核算 */
    private Integer tokenInput;
    /** 输出 Token 数，该消息消耗的输出 token，用于成本核算 */
    private Integer tokenOutput;
    /** 费用金额，该消息产生的费用，单位元，由 token 用量与模型单价计算得出 */
    private BigDecimal costAmount;
    /** 延迟时间，单位毫秒，处理该消息的总耗时，用于性能监控 */
    private Integer latencyMs;
    /** 消息序号，会话内自增序号，保证消息顺序 */
    private Integer seq;
}