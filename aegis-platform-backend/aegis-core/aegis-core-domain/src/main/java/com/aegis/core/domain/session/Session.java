package com.aegis.core.domain.session;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.session.SessionStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 会话实体
 *
 * <p>会话（Session）记录用户与智能体的交互上下文，承载消息历史、沙箱绑定、
 * 版本快照等信息，是智能体持续对话与状态管理的核心实体。</p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *     <li>上下文管理：通过 sessionId 关联所有会话消息，维护对话连续性</li>
 *     <li>沙箱绑定：sandboxId 关联运行时沙箱实例，支撑代码执行与文件操作</li>
 *     <li>版本快照：versionSnapshot 记录会话创建时的智能体配置版本，保证可复现</li>
 *     <li>过期管理：expireTime 控制会话生命周期，过期自动归档</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，会话带 tenantId 隔离；
 * userId 标识会话所有者，确保用户间数据隔离。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see SessionMessage
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sess_session")
public class Session extends TenantEntity {
    /** 会话唯一标识，UUID 字符串，用于全链路追踪 */
    private String sessionId;
    /** 智能体 ID，关联 agent_def.id，会话绑定的智能体 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long agentId;
    /** 智能体版本，会话创建时的智能体版本号，保证会话可复现 */
    private String agentVersion;
    /** 用户 ID，关联 user.id，会话所有者 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    /** 会话标题，长度不超过 128，默认取首条消息摘要，用户可修改 */
    private String title;
    /** 会话状态：{@link SessionStatus#STARTED}（已开始）→{@link SessionStatus#THINKING}（思考中）→{@link SessionStatus#TOOL_CALLING}（工具调用）→{@link SessionStatus#OUTPUTTING}（输出中），异常为 {@link SessionStatus#EXCEPTION}，结束为 {@link SessionStatus#ENDED} */
    private SessionStatus status;
    /** 沙箱实例 ID，关联 sandbox_instance.id，会话绑定的运行时沙箱 */
    private String sandboxId;
    /** 消息数量，会话内消息总数，由系统自动统计 */
    private Integer messageCount;
    /** 已用 Token 数，会话累计消耗的 token 总量，用于成本核算 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tokenUsed;
    /** 最后活跃时间，会话最近一次交互时间，用于过期判断 */
    private LocalDateTime lastActiveTime;
    /** 过期时间，会话自动归档时间，超过此时间未活跃则置为 EXPIRED */
    private LocalDateTime expireTime;
    /** 版本快照，JSON 字符串，记录会话创建时的智能体配置，保证会话可复现 */
    private String versionSnapshot;
}