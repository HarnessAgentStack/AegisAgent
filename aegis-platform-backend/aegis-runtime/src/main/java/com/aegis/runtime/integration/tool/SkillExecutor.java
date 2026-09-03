package com.aegis.runtime.integration.tool;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.runtime.service.agent.ResourceQueryService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.integration.mcp.McpInvoker;

/**
 * 技能执行器。
 *
 * <p>承载技能编排与执行核心流程，支持原子技能（单工具直接封装）与组合技能（多工具 DAG 编排）两种执行模式。
 *
 * <p><b>⚠️ 已废弃（P2-3）</b>：新架构已通过 {@link SkillAsToolAdapter} 将 Skill 直接包装为
 * AgentScope {@link io.agentscope.core.tool.ToolBase} 子类注册到 Toolkit，
 * 使 LLM 能通过 {@code tool_call} 直接调用技能，无需经过本执行器的 DAG 编排。
 *
 * <p>本类保留用于向后兼容，建议新功能直接使用 {@link SkillAsToolAdapter}。
 *
 * <h3>执行流程</h3>
 * <ul>
 *   <li>1. 查询 Skill 定义（skillType、bindingTools、mappingConfig）</li>
 *   <li>2. ATOMIC：解析 bindingTools，调用工具，返回结果</li>
 *   <li>3. COMPOSITE：解析 mappingConfig 中的 DAG，拓扑排序，逐节点执行，传递中间结果</li>
 *   <li>4. 返回 {success, output, nodeResults}</li>
 * </ul>
 *
 * @deprecated 请使用 {@link SkillAsToolAdapter} 将 Skill 包装为 ToolBase 直接注册到 Toolkit
 * @author wang.zhen
 * @see SkillAsToolAdapter
 * @see Skill
 * @see SkillType
 */
@Slf4j
@Deprecated
@Component
@RequiredArgsConstructor
public class SkillExecutor {

    private final ResourceQueryService resourceQueryService;
    private final com.aegis.core.spi.ISandboxBackend sandboxBackend;
    private final McpInvoker mcpInvoker;
    private final AegisBuiltinTools aegisBuiltinTools;
    private final AegisHttpTool aegisHttpTool;

    /** AegisBuiltinTools 提供的工具编码集合（@Tool 注解方法） */
    private static final Set<String> BUILTIN_ANNOTATED_TOOLS = Set.of(
            "generate_file", "web_search");

    /** P1 CMD-04 修复：当前线程的沙箱实例ID（供无法直接传参的调用链路透传） */
    private static final ThreadLocal<String> CURRENT_SANDBOX_INSTANCE_ID = new ThreadLocal<>();

    /**
     * 执行技能。
     *
     * <p>P1 CMD-04 修复：未显式传入 sandboxInstanceId 时，尝试从 ThreadLocal 获取，
     * 确保 CODE_EXEC 类型工具能拿到沙箱实例ID。
     *
     * @param tenantId 租户ID
     * @param skillId  技能ID
     * @param inputs   输入参数
     * @return 执行结果，含 success、output、nodeResults
     */
    public Map<String, Object> execute(Long tenantId, Long skillId, Map<String, Object> inputs) {
        // P1 CMD-04 修复：回退到 ThreadLocal 中保存的沙箱实例ID
        return execute(tenantId, skillId, inputs, CURRENT_SANDBOX_INSTANCE_ID.get());
    }

    /**
     * 执行技能（指定沙箱实例ID）。
     *
     * <p>P1 CMD-04 修复：新增 sandboxInstanceId 参数透传，CODE_EXEC 类型工具需沙箱实例才能执行，
     * 原实现内部调用 executeAtomic(tool, argsJson, null, tenantId) 传 null 导致 CODE_EXEC 工具无法执行。
     *
     * @param tenantId          租户ID
     * @param skillId           技能ID
     * @param inputs            输入参数
     * @param sandboxInstanceId 沙箱实例ID（CODE_EXEC 类型工具使用，可为 null）
     * @return 执行结果，含 success、output、nodeResults
     */
    public Map<String, Object> execute(Long tenantId, Long skillId, Map<String, Object> inputs,
                                       String sandboxInstanceId) {
        Skill skill = resourceQueryService.findSkillById(skillId);
        if (skill == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "技能不存在: " + skillId);
        }
        if (tenantId != null && !tenantId.equals(skill.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权执行该技能");
        }

        log.info("技能执行开始: skillId={}, skillCode={}, type={}, sandboxInstanceId={}",
                skillId, skill.getSkillCode(), skill.getSkillType(), sandboxInstanceId);

        if (skill.getSkillType() == SkillType.ATOMIC) {
            return executeAtomic(tenantId, skill, inputs, sandboxInstanceId);
        } else {
            return executeComposite(tenantId, skill, inputs, sandboxInstanceId);
        }
    }

    /**
     * P1 CMD-04 修复：设置当前线程的沙箱实例ID（供调用方在无法直接传参时透传）。
     *
     * @param sandboxInstanceId 沙箱实例ID
     */
    public static void setSandboxInstanceId(String sandboxInstanceId) {
        CURRENT_SANDBOX_INSTANCE_ID.set(sandboxInstanceId);
    }

    /**
     * P1 CMD-04 修复：清除当前线程的沙箱实例ID（防止线程池复用导致串号）。
     */
    public static void clearSandboxInstanceId() {
        CURRENT_SANDBOX_INSTANCE_ID.remove();
    }

    /**
     * 解析 DAG 配置。
     *
     * @param mappingConfig 映射配置 JSON 字符串
     * @return DAG 配置对象
     */
    public DagConfig parseDag(String mappingConfig) {
        if (mappingConfig == null || mappingConfig.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "映射配置为空");
        }
        try {
            JSONObject json = JSON.parseObject(mappingConfig);
            DagConfig dag = new DagConfig();
            dag.setOutput(json.getString("output"));

            JSONArray nodesArray = json.getJSONArray("nodes");
            if (nodesArray != null) {
                for (int i = 0; i < nodesArray.size(); i++) {
                    JSONObject nodeJson = nodesArray.getJSONObject(i);
                    DagNode node = new DagNode();
                    node.setId(nodeJson.getString("id"));
                    node.setToolId(nodeJson.getLong("toolId"));
                    node.setInputs(nodeJson.getJSONObject("inputs"));
                    JSONArray deps = nodeJson.getJSONArray("dependsOn");
                    if (deps != null) {
                        List<String> dependsOn = new ArrayList<>(deps.size());
                        for (int j = 0; j < deps.size(); j++) {
                            dependsOn.add(deps.getString(j));
                        }
                        node.setDependsOn(dependsOn);
                    } else {
                        node.setDependsOn(new ArrayList<>());
                    }
                    dag.getNodes().add(node);
                }
            }
            return dag;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "DAG 配置解析失败: " + e.getMessage());
        }
    }

    /**
     * DAG 拓扑排序（Kahn 算法）。
     *
     * @param dag DAG 配置
     * @return 拓扑排序后的节点列表
     */
    public List<DagNode> topologicalSort(DagConfig dag) {
        Map<String, DagNode> nodeMap = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (DagNode node : dag.getNodes()) {
            nodeMap.put(node.getId(), node);
            inDegree.put(node.getId(), 0);
        }
        // 计算入度
        for (DagNode node : dag.getNodes()) {
            for (String dep : node.getDependsOn()) {
                inDegree.merge(node.getId(), 1, Integer::sum);
            }
        }
        // 入度为 0 的节点入队
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        List<DagNode> sorted = new ArrayList<>(dag.getNodes().size());
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            DagNode node = nodeMap.get(nodeId);
            sorted.add(node);
            // 找到依赖该节点的后继节点，入度减 1
            for (DagNode n : dag.getNodes()) {
                if (n.getDependsOn().contains(nodeId)) {
                    int newDegree = inDegree.merge(n.getId(), -1, Integer::sum);
                    if (newDegree == 0) {
                        queue.add(n.getId());
                    }
                }
            }
        }
        if (sorted.size() != dag.getNodes().size()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "DAG 存在环，无法拓扑排序");
        }
        return sorted;
    }

    // ============ 内部方法 ============

    /**
     * 执行原子工具调用（B4-8 tool_call 闭环专用）。
     *
     * <p>根据工具类型分派执行策略：
     * <ul>
     *   <li>BUILTIN：Java 方法直接调用</li>
     *   <li>CODE_EXEC：沙箱 exec 执行</li>
     *   <li>MCP：远程调用 MCP Server</li>
     * </ul>
     *
     * @param tool             工具定义
     * @param arguments        调用参数（JSON 字符串）
     * @param sandboxInstanceId 沙箱实例ID（CODE_EXEC 类型使用，可为 null）
     * @param tenantId         租户ID（P0 CMD-02：沙箱 exec 归属校验用）
     * @return 执行结果 JSON 字符串
     */
    public String executeAtomic(Tool tool, String arguments, String sandboxInstanceId, Long tenantId) {
        if (tool == null) {
            return "{\"error\": \"Tool definition is null\"}";
        }

        log.info("executeAtomic: toolCode={}, toolType={}, sourceType={}, sandboxInstanceId={}, tenantId={}",
                tool.getToolCode(), tool.getToolType(), tool.getSourceType(), sandboxInstanceId, tenantId);

        try {
            if (tool.getSourceType() == ToolSourceType.BUILTIN) {
                // 平台内置工具：直接 Java 方法调用
                return executeBuiltin(tool, arguments);
            } else if (tool.getToolType() == ToolType.CODE_EXEC && sandboxInstanceId != null) {
                // 代码执行工具：沙箱 exec
                return executeInSandbox(tool, arguments, sandboxInstanceId, tenantId);
            } else if (tool.getSourceType() == ToolSourceType.MCP) {
                // MCP 工具：远程调用
                return executeMcp(tool, arguments);
            } else {
                // 降级：返回模拟结果（第六阶段完整实现）
                return "{\"result\": \"mock result of tool " + tool.getToolCode() + "\", \"status\": \"degraded\"}";
            }
        } catch (Exception e) {
            log.error("executeAtomic failed: toolCode={}", tool.getToolCode(), e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 执行平台内置工具。
     *
     * <p>P1-6 重构：通过 AgentScope {@link Toolkit#callTool(ToolCallParam)} 统一调用，
     * 自动映射 @ToolParam 注解参数。支持 generate_file/web_search（@Tool 注解模式）
     * 和 http_request（ToolBase 子类模式）。
     *
     * @param tool      工具定义
     * @param arguments 调用参数（JSON 字符串）
     * @return 执行结果 JSON 字符串
     */
    private String executeBuiltin(Tool tool, String arguments) {
        String toolCode = tool.getToolCode();
        log.info("executeBuiltin: toolCode={}, arguments={}", toolCode,
                arguments != null && arguments.length() > 200 ? arguments.substring(0, 200) + "..." : arguments);

        // 创建 Toolkit 并注册对应工具
        Toolkit toolkit = new Toolkit();
        if (BUILTIN_ANNOTATED_TOOLS.contains(toolCode)) {
            toolkit.registerTool(aegisBuiltinTools);
        } else if ("http_request".equals(toolCode)) {
            toolkit.registerAgentTool(aegisHttpTool);
        } else {
            return "{\"error\": \"Unsupported builtin tool: " + toolCode + "\"}";
        }

        // 解析参数 JSON 为 Map
        Map<String, Object> input;
        try {
            input = (arguments != null && !arguments.isEmpty())
                    ? JSON.parseObject(arguments) : Map.of();
        } catch (Exception e) {
            log.error("解析参数 JSON 失败: toolCode={}, arguments={}", toolCode, arguments, e);
            return "{\"error\": \"Invalid arguments JSON: " + e.getMessage().replace("\"", "'") + "\"}";
        }

        // 构造 ToolCallParam 并调用
        // content 为框架 ToolExecutor 参数校验数据源（validateInput(toolCall.getContent(), ...)），必须填充
        String callId = "skill-exec-" + System.nanoTime();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock(callId, toolCode, input, arguments, null))
                .input(input)
                .build();

        try {
            ToolResultBlock result = toolkit.callTool(param).block();
            if (result == null) {
                return "{\"error\": \"Tool returned null result\"}";
            }
            return extractTextFromResult(result);
        } catch (Exception e) {
            log.error("Toolkit.callTool 执行失败: toolCode={}", toolCode, e);
            String msg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "unknown error";
            return "{\"error\": \"" + msg + "\"}";
        }
    }

    /**
     * 从 {@link ToolResultBlock} 提取文本内容。
     *
     * <p>遍历 output 中的 {@link TextBlock}，拼接为完整文本。
     * 非 TextBlock 类型（如 ImageBlock）跳过。
     */
    private String extractTextFromResult(ToolResultBlock result) {
        if (result.getOutput() == null || result.getOutput().isEmpty()) {
            return result.getState() != null ? result.getState().name() : "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock) {
                sb.append(((TextBlock) block).getText());
            }
        }
        return sb.toString();
    }

    /**
     * 在沙箱中执行代码工具。
     */
    private String executeInSandbox(Tool tool, String arguments, String sandboxInstanceId, Long tenantId) {
        log.info("executeInSandbox: toolCode={}, sandboxInstanceId={}, tenantId={}",
                tool.getToolCode(), sandboxInstanceId, tenantId);

        // P0 CMD-01 修复：toolCode 白名单校验，防止命令注入
        String toolCode = tool.getToolCode();
        if (toolCode == null || !toolCode.matches("^[a-zA-Z0-9_-]+$")) {
            log.error("toolCode 包含非法字符，拒绝执行: toolCode={}", toolCode);
            return "{\"error\": \"Invalid toolCode: only alphanumeric, underscore and hyphen are allowed\"}";
        }

        // 构造执行命令：将参数写入临时文件，然后执行工具代码
        String command = "echo '" + arguments.replace("'", "'\\''") + "' | python3 /workspace/scripts/" + toolCode + ".py";
        // P0 CMD-02 修复：传入 tenantId 进行沙箱归属校验（替代原 null 硬编码）
        com.aegis.core.spi.ISandboxBackend.ExecResult result = sandboxBackend.exec(tenantId, sandboxInstanceId, command, 60);
        if (result.exitCode != 0) {
            return "{\"error\": \"Sandbox exec failed\", \"exitCode\": " + result.exitCode
                    + ", \"stderr\": \"" + (result.stderr != null ? result.stderr.replace("\"", "'") : "") + "\"}";
        }
        return result.stdout != null ? result.stdout : "{\"status\": \"empty output\"}";
    }

    /**
     * 调用 MCP 远程工具。
     */
    private String executeMcp(Tool tool, String arguments) {
        log.info("executeMcp: toolCode={}, mcpServiceId={}", tool.getToolCode(), tool.getMcpServiceId());
        return mcpInvoker.invoke(String.valueOf(tool.getMcpServiceId()), tool.getToolCode(), arguments);
    }

    /**
     * 执行原子技能。
     *
     * <p>解析 bindingTools（JSON 数组，工具ID列表），依次调用工具，返回结果。
     * 根据工具类型分派：BUILTIN→Java 调用，CODE_EXEC→沙箱执行，MCP→远程调用。
     *
     * <p>P1 CMD-04 修复：新增 sandboxInstanceId 参数，透传给工具执行，避免 CODE_EXEC 工具因 null 失效。
     *
     * @param tenantId          租户ID
     * @param skill             技能定义
     * @param inputs            输入参数
     * @param sandboxInstanceId 沙箱实例ID（CODE_EXEC 类型工具使用，可为 null）
     */
    private Map<String, Object> executeAtomic(Long tenantId, Skill skill, Map<String, Object> inputs,
                                              String sandboxInstanceId) {
        List<Long> toolIds = parseBindingTools(skill.getBindingTools());
        log.info("原子技能执行: skillId={}, toolIds={}, sandboxInstanceId={}",
                skill.getId(), toolIds, sandboxInstanceId);

        List<Map<String, Object>> nodeResults = new ArrayList<>();
        Object output = null;

        for (Long toolId : toolIds) {
            Tool tool = resourceQueryService.findToolById(toolId);
            Map<String, Object> toolResult = new HashMap<>(4);
            toolResult.put("toolId", toolId);

            if (tool == null) {
                toolResult.put("success", false);
                toolResult.put("output", "Tool not found: " + toolId);
            } else {
                String argsJson = inputs != null ? JSON.toJSONString(inputs) : "{}";
                // P1 CMD-04 修复：透传 sandboxInstanceId 而非 null，保证 CODE_EXEC 工具可执行
                String execResult = executeAtomic(tool, argsJson, sandboxInstanceId, tenantId);
                toolResult.put("success", true);
                toolResult.put("toolCode", tool.getToolCode());
                toolResult.put("output", execResult);
            }
            nodeResults.add(toolResult);
            output = toolResult.get("output");
        }

        Map<String, Object> result = new HashMap<>(3);
        result.put("success", true);
        result.put("output", output);
        result.put("nodeResults", nodeResults);
        return result;
    }

    /**
     * 执行组合技能。
     *
     * <p>解析 DAG，拓扑排序，逐节点执行，传递中间结果。
     * 根据工具类型分派执行：BUILTIN→Java 调用，CODE_EXEC→沙箱执行，MCP→远程调用。
     *
     * <p>P1 CMD-04 修复：新增 sandboxInstanceId 参数，透传给各节点工具执行，避免 CODE_EXEC 工具因 null 失效。
     *
     * @param tenantId          租户ID
     * @param skill             技能定义
     * @param inputs            输入参数
     * @param sandboxInstanceId 沙箱实例ID（CODE_EXEC 类型工具使用，可为 null）
     */
    private Map<String, Object> executeComposite(Long tenantId, Skill skill, Map<String, Object> inputs,
                                                 String sandboxInstanceId) {
        DagConfig dag = parseDag(skill.getMappingConfig());
        List<DagNode> sortedNodes = topologicalSort(dag);

        Map<String, Object> nodeOutputs = new HashMap<>();
        nodeOutputs.put("input", inputs);
        List<Map<String, Object>> nodeResults = new ArrayList<>(sortedNodes.size());

        for (DagNode node : sortedNodes) {
            // 解析节点输入（支持 ${input.xxx} 和 ${nodeId.output} 变量引用）
            Map<String, Object> nodeInput = resolveInputs(node, nodeOutputs, inputs);

            Tool tool = node.getToolId() != null ? resourceQueryService.findToolById(node.getToolId()) : null;
            Map<String, Object> nodeResult = new HashMap<>(4);
            nodeResult.put("nodeId", node.getId());
            nodeResult.put("toolId", node.getToolId());

            if (tool == null) {
                nodeResult.put("success", false);
                nodeResult.put("output", "Tool not found: " + node.getToolId());
            } else {
                String argsJson = nodeInput != null ? JSON.toJSONString(nodeInput) : "{}";
                // P1 CMD-04 修复：透传 sandboxInstanceId 而非 null，保证 CODE_EXEC 工具可执行
                String execResult = executeAtomic(tool, argsJson, sandboxInstanceId, tenantId);
                nodeResult.put("success", true);
                nodeResult.put("output", execResult);
            }
            nodeResults.add(nodeResult);
            nodeOutputs.put(node.getId(), nodeResult);
        }

        // 提取最终输出
        Object output = null;
        if (dag.getOutput() != null) {
            Map<String, Object> outputNode = (Map<String, Object>) nodeOutputs.get(dag.getOutput());
            if (outputNode != null) {
                output = outputNode.get("output");
            }
        }

        Map<String, Object> result = new HashMap<>(3);
        result.put("success", true);
        result.put("output", output);
        result.put("nodeResults", nodeResults);
        return result;
    }

    /**
     * 解析 bindingTools JSON 数组。
     */
    private List<Long> parseBindingTools(String bindingTools) {
        if (bindingTools == null || bindingTools.isEmpty()) {
            return new ArrayList<>();
        }
        JSONArray array = JSON.parseArray(bindingTools);
        List<Long> toolIds = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            toolIds.add(array.getLong(i));
        }
        return toolIds;
    }

    /**
     * 解析节点输入，支持变量引用。
     *
     * <p>支持两种变量引用：
     * <ul>
     *   <li>${input.xxx} - 引用技能入参</li>
     *   <li>${nodeId.output} - 引用上游节点输出</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveInputs(DagNode node, Map<String, Object> nodeOutputs,
                                               Map<String, Object> inputs) {
        Map<String, Object> resolved = new HashMap<>();
        if (node.getInputs() == null) {
            return resolved;
        }
        for (Map.Entry<String, Object> entry : node.getInputs().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                value = resolveVariable((String) value, nodeOutputs, inputs);
            }
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    /**
     * 解析变量引用。
     */
    private Object resolveVariable(String expr, Map<String, Object> nodeOutputs, Map<String, Object> inputs) {
        if (expr == null || !expr.startsWith("${") || !expr.endsWith("}")) {
            return expr;
        }
        String path = expr.substring(2, expr.length() - 1);
        int dotIdx = path.indexOf('.');
        if (dotIdx < 0) {
            return nodeOutputs.get(path);
        }
        String scope = path.substring(0, dotIdx);
        String field = path.substring(dotIdx + 1);
        if ("input".equals(scope)) {
            return inputs != null ? inputs.get(field) : null;
        }
        Object nodeOutput = nodeOutputs.get(scope);
        if (nodeOutput instanceof Map) {
            return ((Map<String, Object>) nodeOutput).get(field);
        }
        return null;
    }

    // ============ 内部类 ============

    /** DAG 配置 */
    public static class DagConfig {
        /** 节点列表 */
        private List<DagNode> nodes = new ArrayList<>();
        /** 输出节点ID */
        private String output;

        public List<DagNode> getNodes() {
            return nodes;
        }

        public void setNodes(List<DagNode> nodes) {
            this.nodes = nodes;
        }

        public String getOutput() {
            return output;
        }

        public void setOutput(String output) {
            this.output = output;
        }
    }

    /** DAG 节点 */
    public static class DagNode {
        /** 节点ID */
        private String id;
        /** 绑定工具ID */
        private Long toolId;
        /** 节点输入配置 */
        private JSONObject inputs;
        /** 依赖节点ID列表 */
        private List<String> dependsOn;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Long getToolId() {
            return toolId;
        }

        public void setToolId(Long toolId) {
            this.toolId = toolId;
        }

        public JSONObject getInputs() {
            return inputs;
        }

        public void setInputs(JSONObject inputs) {
            this.inputs = inputs;
        }

        public List<String> getDependsOn() {
            return dependsOn;
        }

        public void setDependsOn(List<String> dependsOn) {
            this.dependsOn = dependsOn;
        }
    }
}
