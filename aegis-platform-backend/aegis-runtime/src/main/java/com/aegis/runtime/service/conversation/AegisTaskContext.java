package com.aegis.runtime.service.conversation;

import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.runtime.integration.pool.AgentRuntimeTemplate;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.dto.chat.SessionResourcesRef;
import com.aegis.core.dto.chat.SkillRef;
import io.agentscope.core.message.ContentBlock;

/**
 * Aegis 任务执行上下文。
 *
 * <p>贯穿装配、中间件链路与流式执行全流程，承载请求、智能体配置、会话信息与运行时状态。
 * 中间件通过修改本上下文实现租户注入、配额预扣、内容过滤等能力。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AegisTaskContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务ID（UUID） */
    private String taskId;

    /** 会话ID */
    private String sessionId;

    /** 智能体ID */
    private Long agentId;

    /** 智能体版本 */
    private String agentVersion;

    /** 租户ID */
    private Long tenantId;

    /** 用户ID */
    private Long userId;

    /** 用户显示名（realName 优先，回退 username），用于可观测追踪展示 */
    private String userName;

    /** 用户输入消息 */
    private String userMessage;

    /** 智能体定义 */
    private AgentDef agentDef;

    /** 智能体配置 */
    private AgentConfig agentConfig;

    /** 资源绑定列表 */
    private List<AgentBinding> bindings;

    /** 客户端IP */
    private String clientIp;

    /** User-Agent */
    private String userAgent;

    /** 链路追踪ID */
    private String traceId;

    /** API 调用方 Bearer Token（透传用） */
    private String bearerToken;

    /** API 调用方 Bearer Token 是否需要透传 */
    private boolean bearerTokenPassThrough;

    /** 任务开始时间 */
    private LocalDateTime startTime;

    /** 输入 Token 数（执行中累加） */
    private int tokenInput;

    /** 输出 Token 数（执行中累加） */
    private int tokenOutput;

    /** 模型名称 */
    private String modelName;

    /** 运行时模板（Layer 1 池化对象） */
    private AgentRuntimeTemplate template;

    /** 沙箱实例ID（由 SandboxLifecycleMiddleware 注入） */
    private String sandboxInstanceId;

    /** 沙箱隔离策略（ChatRequest 透传） */
    private IsolationStrategy isolationStrategy;

    /** 是否需要沙箱环境（仅标记，实际分配由 AgentScope 内部 Coordinator 完成） */
    private boolean sandboxRequired;

    /** 是否被中间件拦截 */
    private boolean blocked;
    /** 拦截原因 */
    private String blockReason;

    /**
     * 本次请求显式引用的技能列表（{@link ChatRequest#getSkills()}）。
     * 装配期写入 {@code RuntimeContext} 的 {@code aegis.requestedSkills} 属性，
     * 供 {@code RuntimeContextSkillRepository} 强制包含。
     */
    private List<SkillRef> requestedSkills;

    /**
     * 会话级资源引用（{@link ChatRequest#getResources()}）：
     * 用户在对话中临时选择的知识库与 MCP 服务，仅本次会话生效。
     */
    private SessionResourcesRef sessionResources;

    /**
     * 被驳回（不可见/不存在/无权限）的技能 code 列表，
     * 用于向用户发出 {@code skill.rejected} 事件反馈。
     */
    private List<String> rejectedSkillCodes;

    /** 多模态图片内容块（AgentAssemblyService 构造，TaskExecutionService 注入 UserMessage） */
    private transient List<ContentBlock> multimodalBlocks;

    /** 已装配的 HarnessAgent 实例（AgentAssemblyService 装配产出） */
    private transient HarnessAgent agent;

    /** AgentScope 运行时上下文（AgentAssemblyService 构建产出，供 streamEvents 使用） */
    private transient RuntimeContext runtimeContext;

    /**
     * 流式输出累积缓冲：由 AegisMemoryMiddleware 在 onAgent 创建并累积 TextBlockDeltaEvent，
     * 供 AegisMaskMiddleware 在 doFinally 只读消费做输出安全审计，
     * 避免两个中间件各自维护 StringBuilder 重复累积同一份 delta。
     */
    private transient StringBuilder assistantReplyBuffer;

    // ==================== 安全运行时治理 ====================

    /** 治理档位（AgentDef.governanceTier 透传） */
    private GovernanceTier governanceTier;

    /** 智能体安全等级（用于 level-matrix 评估） */
    private SecurityLevel agentLevel;

    /** 累计阻断次数（用于升级决策） */
    private int blockCount;

    /** 工具安全等级元数据（toolCode → SecurityLevel，AegisToolBridge 注入） */
    private Map<String, SecurityLevel> toolSecurityLevels;

    /** 获取治理档位（null 时回退 {@link GovernanceTier#STANDARD}）。 */
    public GovernanceTier getGovernanceTier() {
        return governanceTier != null ? governanceTier : GovernanceTier.STANDARD;
    }

    /** 获取智能体安全等级（null 时回退 {@link SecurityLevel#L1}）。 */
    public SecurityLevel getAgentLevel() {
        return agentLevel != null ? agentLevel : SecurityLevel.L1;
    }

    /** 获取工具安全等级（未注册返回 null）。 */
    public SecurityLevel getToolSecurityLevel(String toolCode) {
        if (toolSecurityLevels == null || toolCode == null) return null;
        return toolSecurityLevels.get(toolCode);
    }

    /**
     * onActing 直接发起的 HITL 审批请求（兜底落库用）。
     *
     * <p>安全中间件 onActing 命中未知/MCP 工具默认审批或通配符 HitlNode 时，
     * 构造 hitl.request 事件并存入此处；{@code TaskExecutionService} 的 doFinally
     * 读取它统一落库 + 置 PAUSED，确保即使该事件未途经流事件转换也可审批、可恢复。
     */
    private transient Map<String, Object> pendingHitlRequest;

    /** 暂存 onActing 直接发起的 HITL 审批请求（单次写入）。 */
    public void setPendingHitlRequest(Map<String, Object> request) {
        this.pendingHitlRequest = request;
    }

    /** 取出并清空 HITL 审批请求（幂等消费），无则返回 null。 */
    public Map<String, Object> takePendingHitlRequest() {
        Map<String, Object> r = this.pendingHitlRequest;
        this.pendingHitlRequest = null;
        return r;
    }
}
