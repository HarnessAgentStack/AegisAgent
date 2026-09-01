package com.aegis.core.dto.chat;

import com.aegis.core.enums.session.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话ID */
    private String sessionId;

    /** 智能体ID */
    private Long agentId;

    /** 智能体名称（冗余） */
    private String agentName;

    /** 智能体版本 */
    private String agentVersion;

    /** 用户ID */
    private Long userId;

    /** 会话标题 */
    private String title;

    /** 会话状态 */
    private SessionStatus status;

    /** 沙箱实例ID */
    private String sandboxId;

    /** 消息数量 */
    private Integer messageCount;

    /** 已用 Token 数 */
    private Long tokenUsed;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveTime;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
