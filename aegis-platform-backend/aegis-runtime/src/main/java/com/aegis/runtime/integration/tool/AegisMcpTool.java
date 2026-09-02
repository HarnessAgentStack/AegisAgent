package com.aegis.runtime.integration.tool;

import com.aegis.runtime.integration.agent.ToolResultCache;
import com.aegis.runtime.integration.mcp.McpInvoker;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 MCP 工具的 AS {@link ToolBase} 适配器（P0-C 接通对话循环）。
 *
 * <p>历史问题：{@link com.aegis.runtime.integration.agent.AegisToolBridge#resolveTools}
 * 显式跳过 MCP 工具（"非内置工具，跳过 AS 注册"），导致 MCP 工具在对话里"调不动"。
 * 本类把 res_tool.sourceType == MCP 的工具，按 MCP 服务实际暴露的工具 schema 包装为
 * AS ToolBase，注册到 {@link io.agentscope.core.tool.Toolkit}，使 LLM 能识别并调用。
 *
 * <h3>实例化策略</h3>
 * <p>每个 (mcpServiceId, mcpToolName) 组合 new 一个实例，由
 * {@link com.aegis.runtime.integration.agent.AegisToolBridge} 在装配阶段循环创建。
 * 不使用 @Component（避免 Spring 单例冲突），通过工厂方法 {@link #of} 构造。
 *
 * <h3>调用链</h3>
 * <pre>{@code
 * LLM tool_call(toolName=mcp_xxx, args=...)
 *   → AS Toolkit 分派到 AegisMcpTool.callAsync
 *     → McpInvoker.invoke(mcpServiceId, toolName, args)
 *       → McpClientWrapper.callTool (MCP 协议真实调用)
 *     → 填充 ToolResultCache（供 tool_result SSE 事件携带结果）
 *   → 返回 ToolResultBlock
 * }</pre>
 *
 * @author wang.zhen
 * @see McpInvoker
 * @see com.aegis.runtime.integration.agent.AegisToolBridge
 */
@Slf4j
public class AegisMcpTool extends ToolBase {

    private final McpInvoker mcpInvoker;
    private final ToolResultCache toolResultCache;
    private final String mcpServiceId;
    private final String mcpToolName;
    private final String mcpEndpoint;

    /**
     * 工厂方法：从 MCP schema 创建 AegisMcpTool 实例。
     *
     * @param mcpInvoker      MCP 调用器
     * @param toolResultCache 工具结果缓存
     * @param mcpServiceId    MCP 服务ID（字符串形式）
     * @param mcpTool         MCP 服务暴露的工具 schema
     * @param mcpEndpoint     MCP 服务接入端点URL（用于出站策略检查）
     * @return 已配置元数据的 AegisMcpTool 实例
     */
    public static AegisMcpTool of(McpInvoker mcpInvoker,
                                  ToolResultCache toolResultCache,
                                  String mcpServiceId,
                                  McpSchema.Tool mcpTool,
                                  String mcpEndpoint) {
        String toolName = mcpTool.name();
        String description = buildDescription(mcpTool);
        Map<String, Object> inputSchema = mcpTool.inputSchema() != null
                ? McpTool.convertMcpSchemaToParameters(mcpTool.inputSchema(), null)
                : Map.of("type", "object");
        return new AegisMcpTool(mcpInvoker, toolResultCache, mcpServiceId, toolName, description, inputSchema, mcpEndpoint);
    }

    private AegisMcpTool(McpInvoker mcpInvoker,
                         ToolResultCache toolResultCache,
                         String mcpServiceId,
                         String toolName,
                         String description,
                         Map<String, Object> inputSchema,
                         String mcpEndpoint) {
        super(ToolBase.builder()
                .name(toolName)
                .description(description)
                .inputSchema(inputSchema));
        this.mcpInvoker = mcpInvoker;
        this.toolResultCache = toolResultCache;
        this.mcpServiceId = mcpServiceId;
        this.mcpToolName = toolName;
        this.mcpEndpoint = mcpEndpoint;
    }

    /**
     * 覆盖权限检查：MCP工具默认为低风险工具，工具自检返回 ALLOW（不"跳过"审批——
     * AS PermissionEngine 中 ask/deny 规则先于工具自检评估，命中即生效）。
     *
     * <p>MCP工具的风险评估由上层风险服务负责，AgentScope的内置PermissionEngine不需要再对MCP工具进行HITL审批。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(PermissionDecision.builder()
                .behavior(PermissionBehavior.ALLOW)
                .message("MCP tool " + mcpToolName + " allowed by Aegis framework")
                .decisionReason("MCP tool - risk managed by Aegis policy engine")
                .build());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String toolCallId = (param.getToolUseBlock() != null) ? param.getToolUseBlock().getId() : null;

        final String argsJson;
        if (input == null || input.isEmpty()) {
            argsJson = "{}";
        } else {
            argsJson = JSON.toJSONString(input);
        }

        final String svcId = this.mcpServiceId;
        final String toolNm = this.mcpToolName;
        final String callId = toolCallId;

        return Mono.fromCallable(() -> {
            String rawResult = mcpInvoker.invoke(svcId, toolNm, argsJson);
            if (callId != null) {
                toolResultCache.put(callId, rawResult);
            }
            boolean isError = rawResult != null && rawResult.contains("\"success\":false");
            log.info("AegisMcpTool 调用完成: serviceId={}, tool={}, status={}",
                    svcId, toolNm, isError ? "ERROR" : "SUCCESS");
            return buildResult(callId, toolNm, rawResult, isError);
        }).onErrorResume(e -> {
            log.error("AegisMcpTool 调用异常: serviceId={}, tool={}", svcId, toolNm, e);
            String errJson = "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            if (callId != null) {
                toolResultCache.put(callId, errJson);
            }
            return Mono.just(buildResult(callId, toolNm, errJson, true));
        });
    }

    private ToolResultBlock buildResult(String toolCallId, String toolName, String text, boolean isError) {
        ToolResultState state = isError ? ToolResultState.ERROR : ToolResultState.SUCCESS;
        Map<String, Object> metadata = new HashMap<>(2);
        metadata.put("mcpServiceId", mcpServiceId);
        metadata.put("tool", toolName);
        return new ToolResultBlock(
                toolCallId,
                toolName,
                List.of(TextBlock.builder().text(text != null ? text : "").build()),
                metadata,
                state);
    }

    private static String buildDescription(McpSchema.Tool tool) {
        String base = tool.description() != null ? tool.description() : "MCP 工具";
        return String.format("【MCP:%s】%s", tool.name(), base);
    }

    private static String escapeJson(String s) {
        if (s == null) return "unknown";
        return s.replace("\\", "\\\\").replace("\"", "'");
    }
}
