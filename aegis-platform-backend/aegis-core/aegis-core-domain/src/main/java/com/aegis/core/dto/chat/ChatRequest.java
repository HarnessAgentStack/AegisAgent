package com.aegis.core.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import com.aegis.core.dto.agent.AttachmentRef;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import java.util.ArrayList;

/**
 * SSE 流式对话请求。
 *
 * <p>经网关注入租户上下文后转发至 aegis-runtime，
 * 由 {@code TaskExecutionService} 执行并返回 SSE 事件流。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code agentId}：目标智能体ID，必填</li>
 *   <li>{@code sessionId}：会话ID，可选；为空时由运行时创建新会话</li>
 *   <li>{@code message}：用户输入消息，必填</li>
 *   <li>{@code attachments}：附件列表，可选</li>
 *   <li>{@code context}：上下文信息（tenantId/userId/deptId），由网关注入</li>
 *   <li>{@code isolationStrategy}：沙箱隔离策略（SHARED_PER_SCOPE/DEDICATED_PER_SESSION/SHARED_WITH_QUOTA），可选，默认 SHARED_PER_SCOPE</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体ID，必填 */
    private Long agentId;

    /** 会话ID，可选；为空时由运行时创建新会话 */
    private String sessionId;

    /** 用户输入消息，必填 */
    private String message;

    /** 附件列表，可选 */
    private List<AttachmentRef> attachments;

    /** 上下文信息（tenantId/userId/deptId），由网关注入 */
    private Map<String, Object> context;

    /**
     * 回复ID，用于客户端重试去重。
     * <p>客户端网络抖动重试时携带相同 replyId，服务端通过 Redis SETNX 去重，
     * 防止同一句话被多次落库、多次消耗 Token。为空时不做去重。
     */
    private String replyId;

    /** 沙箱隔离策略（SHARED_PER_SCOPE/DEDICATED_PER_SESSION/SHARED_WITH_QUOTA），可选，默认 SHARED_PER_SCOPE */
    private String isolationStrategy;

    /**
     * @SKILL 结构化引用列表（不靠解析文本）。
     * <p>通过 {@code @} 唤起技能并选中后透传；运行时强制将这些技能注入本次请求上下文。
     * 为空表示不显式指定，仅使用智能体已绑定的可见技能。
     */
    private List<SkillRef> skills = new ArrayList<>();

    /**
     * 会话级资源引用（知识库+MCP服务）。
     * <p>允许用户在对话中临时选择可用资源，仅本次会话生效，不改变用户订阅关系。
     */
    private SessionResourcesRef resources;

    /** 便捷方法：从 context 获取 tenantId */
    public Long getTenantId() {
        if (context == null) {
            return null;
        }
        Object v = context.get("tenantId");
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(v.toString());
    }

    /** 便捷方法：从 context 获取 userId */
    public Long getUserId() {
        if (context == null) {
            return null;
        }
        Object v = context.get("userId");
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(v.toString());
    }

    /** 便捷方法：从 context 获取 deptId */
    public Long getDeptId() {
        if (context == null) {
            return null;
        }
        Object v = context.get("deptId");
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return v.toString().isEmpty() ? null : Long.valueOf(v.toString());
    }

    /**
     * 解析隔离策略字符串为枚举。
     *
     * <p>为 null 或非法值时返回默认 {@link IsolationStrategy#SHARED_PER_SCOPE}。</p>
     *
     * @return 解析后的隔离策略
     */
    public IsolationStrategy resolveIsolationStrategy() {
        if (isolationStrategy == null || isolationStrategy.isEmpty()) {
            return IsolationStrategy.SHARED_PER_SCOPE;
        }
        try {
            return IsolationStrategy.valueOf(isolationStrategy);
        } catch (IllegalArgumentException e) {
            return IsolationStrategy.SHARED_PER_SCOPE;
        }
    }
}
