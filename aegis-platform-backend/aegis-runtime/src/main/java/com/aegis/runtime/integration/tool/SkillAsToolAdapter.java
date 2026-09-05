package com.aegis.runtime.integration.tool;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.common.tenant.TenantContextScope;
import com.aegis.runtime.integration.agent.ToolResultCache;
import com.aegis.runtime.integration.skill.SkillCreatorOrchestrator;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能 → AgentScope Tool 适配器（P0-B 对话循环闭环）。
 *
 * <p>将 Aegis {@link Skill} 包装为 AgentScope {@link ToolBase}，
 * 使 LLM 能识别 Skill 并通过 tool_call 机制调用。
 * 解决 Skill 无法被 AgentScope Toolkit 注册、对话中不可用的问题。
 *
 * <h3>skill_creator 特殊处理</h3>
 * <p>当技能编码为 {@code skill_creator} 时，调用会被路由到 {@link SkillCreatorOrchestrator}，
 * 由编排器处理创建/修改/调试/打包/提交等业务逻辑，收集 SSE 事件注入到事件流中。
 *
 * <h3>实例化策略</h3>
 * <p>每个 Skill 创建一个实例，由
 * {@link com.aegis.runtime.integration.agent.AegisToolBridge} 在装配阶段循环创建。
 * 不使用 @Component（避免 Spring 单例冲突），通过工厂方法 {@link #of} 构造。
 *
 * <h3>调用链</h3>
 * <pre>{@code
 * LLM tool_call(toolName=skill_xxx, args=...)
 *   → AS Toolkit 分派到 SkillAsToolAdapter.callAsync
 *     → skill_creator? → SkillCreatorOrchestrator.handleSkillCreator()
 *                          → 收集事件返回编排结果
 *     → 其他技能? → SkillExecutor.execute()
 *                          → ATOMIC: executeAtomic → MCP/BUILTIN/沙箱
 *                          → COMPOSITE: parseDag → topologicalSort → 逐节点执行
 *     → 填充 ToolResultCache（供 tool_result SSE 事件携带结果）
 *   → 返回 ToolResultBlock（或 Flux<ToolResultBlock> 用于 skill_creator 的多事件场景）
 * }</pre>
 *
 * @author wang.zhen
 * @see Skill
 * @see SkillExecutor
 * @see SkillCreatorOrchestrator
 * @see com.aegis.runtime.integration.agent.AegisToolBridge
 */
@Slf4j
public class SkillAsToolAdapter extends ToolBase {

    private final Skill skill;
    private final SkillExecutor skillExecutor;
    private final ToolResultCache toolResultCache;
    private final SkillCreatorOrchestrator skillCreatorOrchestrator;

    /**
     * 工厂方法：从 Skill 创建 SkillAsToolAdapter 实例。
     *
     * @param skill                    技能定义
     * @param skillExecutor            技能执行器
     * @param toolResultCache          工具结果缓存
     * @param skillCreatorOrchestrator 技能创建编排器（用于 skill_creator 特殊处理）
     * @return 已配置元数据的 SkillAsToolAdapter 实例
     */
    public static SkillAsToolAdapter of(Skill skill,
                                        SkillExecutor skillExecutor,
                                        ToolResultCache toolResultCache,
                                        SkillCreatorOrchestrator skillCreatorOrchestrator) {
        // toolName 对齐 skillCode：skillCode 已含 skill_ 前缀时直接使用，
        // 避免 skill_creator → skill_skill_creator 双重前缀与提示词中的技能名不一致
        // （LLM 在 <available_skills> 看到的是 skillCode，tool_call 只能命中 Toolkit 注册名）
        String skillCode = skill.getSkillCode() != null ? skill.getSkillCode() : "unknown_skill";
        String toolName = skillCode.startsWith("skill_") ? skillCode : "skill_" + skillCode;
        String description = buildDescription(skill);
        // S1: skill_creator 是元技能，有独立的 Tool parameters schema（不使用数据库 skill.inputs），
        // 为 inputs/outputs/bindingTools 字段去掉 type 限制，接受 string/object/array 任意类型，
        // 避免 AgentScope ToolValidator 因 LLM 直觉传 object/array 而拒绝调用，浪费 2+ 次 tool_call 迭代
        Map<String, Object> inputSchema;
        if ("skill_creator".equals(skillCode)) {
            inputSchema = buildSkillCreatorSchema();
        } else {
            inputSchema = parseInputSchema(skill.getInputs());
        }
        return new SkillAsToolAdapter(skill, skillExecutor, toolResultCache,
                skillCreatorOrchestrator, toolName, description, inputSchema);
    }

    private SkillAsToolAdapter(Skill skill,
                               SkillExecutor skillExecutor,
                               ToolResultCache toolResultCache,
                               SkillCreatorOrchestrator skillCreatorOrchestrator,
                               String toolName,
                               String description,
                               Map<String, Object> inputSchema) {
        super(ToolBase.builder()
                .name(toolName)
                .description(description)
                .inputSchema(inputSchema));
        this.skill = skill;
        this.skillExecutor = skillExecutor;
        this.toolResultCache = toolResultCache;
        this.skillCreatorOrchestrator = skillCreatorOrchestrator;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String toolCallId = (param.getToolUseBlock() != null) ? param.getToolUseBlock().getId() : null;

        final Map<String, Object> inputs = input != null ? input : Map.of();
        final Long tenantId = skill.getTenantId();
        final Long skillId = skill.getId();
        final String callId = toolCallId;

        // 缓存调用元信息
        if (callId != null) {
            toolResultCache.putCallMeta(callId, JSON.toJSONString(inputs), System.currentTimeMillis());
        }

        // skill_creator 特殊路径：路由到 SkillCreatorOrchestrator
        if ("skill_creator".equals(skill.getSkillCode())) {
            return handleSkillCreatorCall(param, skillId, inputs, callId);
        }

        // 普通技能路径
        // boundedElastic 池化线程不继承入口 TenantContext，需在执行线程显式绑定（与 handleSkillCreatorCall 对齐）
        final Long sessionTenantId = resolveSessionTenantId(param, tenantId);
        return Mono.fromCallable(() -> {
            try (var ignore = TenantContextScope.bound(sessionTenantId)) {
                Map<String, Object> result = skillExecutor.execute(sessionTenantId, skillId, inputs);
                String resultJson = JSON.toJSONString(result);
                if (callId != null) {
                    toolResultCache.put(callId, resultJson);
                }
                boolean isError = result != null && Boolean.FALSE.equals(result.get("success"));
                log.info("SkillAsToolAdapter 调用完成: skillCode={}, status={}",
                        skill.getSkillCode(), isError ? "ERROR" : "SUCCESS");
                return buildResult(callId, skill.getSkillCode(), resultJson, isError);
            }
        }).onErrorResume(e -> {
            log.error("SkillAsToolAdapter 调用异常: skillCode={}", skill.getSkillCode(), e);
            String errJson = "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            if (callId != null) {
                toolResultCache.put(callId, errJson);
            }
            return Mono.just(buildResult(callId, skill.getSkillCode(), errJson, true));
        });
    }

    /**
     * 从 ToolCallParam 的 RuntimeContext 解析当前会话租户 ID。
     *
     * <p>boundedElastic 池化线程不继承入口 TenantContext，普通技能执行前需在执行线程显式绑定。
     * 优先取会话级 tenantId（RuntimeContext.AegisTaskContext），回落 skill.tenantId，
     * 最后为 null（TenantContextScope.bound(null) 为 NOOP，仅 GLOBAL 跨租户技能会到此）。</p>
     */
    private Long resolveSessionTenantId(ToolCallParam param, Long fallbackTenantId) {
        try {
            if (param != null) {
                RuntimeContext rc = param.getRuntimeContext();
                if (rc != null) {
                    AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
                    if (taskCtx != null && taskCtx.getTenantId() != null) {
                        return taskCtx.getTenantId();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("普通技能执行: 从 RuntimeContext 解析 tenantId 失败, 回退 skill.tenantId: {}", e.getMessage());
        }
        return fallbackTenantId;
    }

    /**
     * 处理 skill_creator 调用。
     *
     * <p>调用 SkillCreatorOrchestrator 执行业务逻辑，
     * 将结果缓存并转换为 ToolResultBlock，编排阶段事件随 ToolResultCache 注入 SSE 流。
     *
     * <h3>P0 修复：tenantId/userId 必须取自会话上下文</h3>
     * <p>skill_creator 是 scope=GLOBAL 的系统技能（tenant_id=0、authorUserId=null），
     * 此前直接透传 {@code skill.getTenantId()}/{@code skill.getAuthorUserId()} 会导致：
     * <ul>
     *   <li>新建技能挂到系统租户（tenant_id=0），用户在市场/我的技能中不可见</li>
     *   <li>userId=null 触发 createDraftSkill 的"用户ID不能为空"异常，创建直接失败</li>
     * </ul>
     * 现从 {@link ToolCallParam#getRuntimeContext()} 解析 {@link AegisTaskContext}
     * 获取真实会话的 tenantId/userId。
     *
     * @param param   工具调用参数（含 RuntimeContext）
     * @param skillId 技能ID
     * @param inputs  工具调用参数
     * @param callId  工具调用ID
     * @return ToolResultBlock（包含编排结果和事件信息）
     */
    private Mono<ToolResultBlock> handleSkillCreatorCall(ToolCallParam param,
                                                         Long skillId,
                                                         Map<String, Object> inputs, String callId) {
        return Mono.fromCallable(() -> {
            List<AgentEvent> events = new ArrayList<>();
            // skill_creator 在 AgentScope Toolkit 线程执行，入口绑定的 TenantContext 不跨线程传递。
            // handleSkillCreator 内 skillMapper.selectCount/selectOne/insert/updateById 均操作 res_skill（非 ignore 表），
            // 无 TenantContext 会触发 fail-closed。此处解析 tenantId 后显式 bind，finally clear。
            boolean boundHere = false;
            try {
                Long tenantId = null;
                Long userId = null;
                try {
                    io.agentscope.core.agent.RuntimeContext rc =
                            param != null ? param.getRuntimeContext() : null;
                    if (rc != null) {
                        AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
                        if (taskCtx != null) {
                            tenantId = taskCtx.getTenantId();
                            userId = taskCtx.getUserId();
                        }
                    }
                } catch (Exception e) {
                    log.warn("skill_creator: 从 RuntimeContext 解析会话上下文失败: {}", e.getMessage());
                }
                if (tenantId == null) {
                    tenantId = skill.getTenantId();
                    log.warn("skill_creator: 会话上下文缺 tenantId，回退 skill.tenantId={}（系统技能场景该值不可用）",
                            tenantId);
                }
                if (tenantId != null && com.aegis.core.common.tenant.TenantContextHolder.get() == null) {
                    com.aegis.core.common.tenant.TenantContextHolder.bind(tenantId);
                    boundHere = true;
                }

                log.info("skill_creator 编排调用开始: tenantId={}, userId={}, skillId={}, inputs={}",
                        tenantId, userId, skillId, JSON.toJSONString(inputs));

                Map<String, Object> result = skillCreatorOrchestrator.handleSkillCreator(
                        tenantId, userId, inputs, events);

                log.info("skill_creator 编排完成: eventCount={}, result={}",
                        events.size(), JSON.toJSONString(result));

                Map<String, Object> enrichedResult = new HashMap<>(result);
                if (!events.isEmpty()) {
                    enrichedResult.put("_skillEvents", events);
                }

                String resultJson = JSON.toJSONString(enrichedResult);
                if (callId != null) {
                    toolResultCache.put(callId, resultJson, events);
                }

                boolean isError = result != null && Boolean.FALSE.equals(result.get("success"));
                return buildResultWithEvents(callId, skill.getSkillCode(), resultJson, isError, events);
            } catch (Exception e) {
                log.error("skill_creator 编排异常: skillId={}", skillId, e);
                String errJson = "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                if (callId != null) {
                    toolResultCache.put(callId, errJson);
                }
                return buildResult(callId, skill.getSkillCode(), errJson, true);
            } finally {
                if (boundHere) {
                    com.aegis.core.common.tenant.TenantContextHolder.clear();
                }
            }
        });
    }

    private ToolResultBlock buildResult(String toolCallId, String toolName, String text, boolean isError) {
        ToolResultState state = isError ? ToolResultState.ERROR : ToolResultState.SUCCESS;
        Map<String, Object> metadata = new HashMap<>(2);
        metadata.put("skillId", skill.getId() != null ? String.valueOf(skill.getId()) : null);
        metadata.put("skillType", skill.getSkillType() != null ? skill.getSkillType().name() : "UNKNOWN");
        return new ToolResultBlock(
                toolCallId,
                toolName,
                List.of(TextBlock.builder().text(text != null ? text : "").build()),
                metadata,
                state);
    }

    /**
     * 构建包含 skill_creator 事件的结果。
     *
     * @param toolCallId  工具调用ID
     * @param toolName    工具名称
     * @param text        结果文本
     * @param isError     是否错误
     * @param skillEvents 技能创建事件列表
     * @return ToolResultBlock
     */
    private ToolResultBlock buildResultWithEvents(String toolCallId, String toolName, String text,
                                                   boolean isError, List<com.aegis.core.dto.agent.AgentEvent> skillEvents) {
        ToolResultState state = isError ? ToolResultState.ERROR : ToolResultState.SUCCESS;
        Map<String, Object> metadata = new HashMap<>(3);
        metadata.put("skillId", skill.getId() != null ? String.valueOf(skill.getId()) : null);
        metadata.put("skillType", skill.getSkillType() != null ? skill.getSkillType().name() : "UNKNOWN");
        if (skillEvents != null && !skillEvents.isEmpty()) {
            metadata.put("_skillEvents", skillEvents);
        }
        return new ToolResultBlock(
                toolCallId,
                toolName,
                List.of(TextBlock.builder().text(text != null ? text : "").build()),
                metadata,
                state);
    }

    /**
     * 构建工具描述。
     */
    private static String buildDescription(Skill skill) {
        String base = skill.getDescription() != null ? skill.getDescription() : "技能工具";
        String typeTag = skill.getSkillType() != null ? "[" + skill.getSkillType().name() + "]" : "";
        return String.format("【Skill:%s】%s %s", skill.getSkillCode(), typeTag, base);
    }

    /**
     * 解析 Skill 的 inputs 字段为 inputSchema。
     * 若无 inputs 定义，则返回默认的 object schema。
     */
    private static Map<String, Object> parseInputSchema(String inputsJson) {
        if (inputsJson == null || inputsJson.isEmpty()) {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            schema.put("description", "技能输入参数");
            return schema;
        }
        try {
            JSONObject parsed = JSON.parseObject(inputsJson);
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", parsed.getString("type") != null ? parsed.getString("type") : "object");
            schema.put("description", parsed.getString("description") != null ? parsed.getString("description") : "技能输入参数");
            if (parsed.containsKey("properties")) {
                schema.put("properties", parsed.getJSONObject("properties"));
            }
            if (parsed.containsKey("required")) {
                schema.put("required", parsed.getJSONArray("required"));
            }
            return schema;
        } catch (Exception e) {
            log.warn("Skill inputs 解析失败，使用默认 schema: {}", e.getMessage());
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            schema.put("description", "技能输入参数");
            return schema;
        }
    }

    /**
     * S1: 为 skill_creator 元技能构建专用 Tool parameters schema。
     *
     * <p>关键设计：inputs/outputs/bindingTools 三个字段**不设 type 限制**，
     * 接受 string（JSON 字符串）、object（JSON object）、array 任意类型，
     * 由 {@link SkillCreatorOrchestrator#getString} 统一做类型归一。
     *
     * <p>这解决了 AgentScope ToolValidator 拒绝 LLM 直觉传 object/array
     * 的问题——原 schema 把这三个字段定义为 {@code type: string}，
     * 导致每次 MODIFY 动作都会经历 2 次迭代失败再纠正，浪费 token 和响应时间。
     */
    private static Map<String, Object> buildSkillCreatorSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("description", "技能创建工具 - 支持创建、修改、打包、提交审核技能草稿");

        Map<String, Object> props = new HashMap<>();

        // action + skillId: 核心定位字段
        props.put("action", Map.of(
                "type", "string",
                "description", "操作类型: CREATE(创建草稿) / MODIFY(修改) / PACKAGE(打包) / SUBMIT(提交审核)"));
        props.put("skillId", Map.of(
                "type", "integer",
                "description", "技能ID（MODIFY/PACKAGE/SUBMIT 时必填）"));

        // 基础元数据
        props.put("skillName", Map.of("type", "string", "description", "技能名称"));
        props.put("description", Map.of("type", "string", "description", "技能描述"));
        props.put("instructions", Map.of("type", "string", "description", "技能执行指令/方法论"));
        props.put("category", Map.of("type", "string",
                "description", "技能分类: INTEGRATION/MCP_AGENT/BUSINESS/UTILITY"));
        props.put("securityLevel", Map.of("type", "string",
                "description", "安全等级: L1(公开)/L2(受限)/L3(敏感)"));

        // ⚠️ S1 核心：这三个字段**不设 type 限制**，接受 string/object/array
        props.put("inputs", Map.of(
                "description", "输入参数定义（JSON Schema，可传 object 或 JSON string）"));
        props.put("outputs", Map.of(
                "description", "输出参数定义（JSON Schema，可传 object 或 JSON string）"));
        props.put("bindingTools", Map.of(
                "description", "绑定工具列表（JSON array 或 JSON string）"));

        props.put("skillCode", Map.of("type", "string", "description", "技能编码（可选，自动生成）"));
        props.put("version", Map.of("type", "string", "description", "版本号（可选，默认 0.0.1）"));

        schema.put("properties", props);
        return schema;
    }

    private static String escapeJson(String s) {
        if (s == null) return "unknown";
        return s.replace("\\", "\\\\").replace("\"", "'");
    }
}
