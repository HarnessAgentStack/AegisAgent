package com.aegis.core.dto.chat;

import com.aegis.core.enums.session.MessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话消息视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息ID */
    private Long id;

    /** 会话ID */
    private String sessionId;

    /** 消息类型 */
    private MessageType messageType;

    /** 消息内容 */
    private String content;

    /** 推理过程 */
    private String reasoning;

    /** 工具调用ID */
    private String toolCallId;

    /** 工具名称 */
    private String toolName;

    /** 工具参数 */
    private String toolParams;

    /** 工具结果 */
    private String toolResult;

    /** 知识库引用 */
    private String kbRefs;

    /** 输入 Token 数 */
    private Integer tokenInput;

    /** 输出 Token 数 */
    private Integer tokenOutput;

    /** 延迟时间（毫秒） */
    private Integer latencyMs;

    /** 消息序号 */
    private Integer seq;

    /** 创建时间 */
    private LocalDateTime createTime;
}
