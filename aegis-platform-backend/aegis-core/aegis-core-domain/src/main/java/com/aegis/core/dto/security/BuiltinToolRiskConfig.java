package com.aegis.core.dto.security;

import com.aegis.core.dto.security.ToolRiskInfo.RiskLevel;
import com.aegis.core.enums.resource.ToolType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 内置工具风险配置。
 *
 * <p>集中管理平台内置工具的静态风险配置，供 {@link com.aegis.runtime.service.ToolRiskService}
 * 查询与评估。内置工具的风险等级为代码硬编码，确保核心工具的风险基线不可被数据库配置覆盖。</p>
 *
 * <h3>工具清单来源（唯一权威对齐源）</h3>
 * <p>与 HarnessAgent 装配时框架实际注册的工具全集精确对齐（见 runtime 日志
 * {@code Toolkit: Registered tool '...'}），共 28 项：</p>
 * <ul>
 *   <li>Aegis 自建（5）：web_search / image_search / web_fetch / generate_file / http_request</li>
 *   <li>框架 Shell（1）：execute（framework-drive=true 时经沙箱执行）</li>
 *   <li>框架 FilesystemTool 拆分（6）：read_file / write_file / list_files / grep_files / glob_files / edit_file</li>
 *   <li>框架 Subagent/Task（7）：agent_spawn / agent_list / agent_send / task_list / task_cancel / task_output / wait_async_results</li>
 *   <li>框架 Memory/Session（6）：memory_search / memory_get / memory_save / session_search / session_list / session_history</li>
 *   <li>框架 Skill（2）：skill_creator / load_skill_through_path</li>
 *   <li>MCP 场景兜底（1）：browser_use</li>
 * </ul>
 *
 * <h3>风险分级原则</h3>
 * <ul>
 *   <li>只读查询（READONLY）→ LOW，无需审批</li>
 *   <li>内部调度（AGENT/ASYNC 只读类）→ LOW，无需审批</li>
 *   <li>写操作（write_file / edit_file / task_cancel / skill_creator）→ MEDIUM，需审批</li>
 *   <li>代码执行（execute）→ MEDIUM + 审批（沙箱内执行，破坏性命令升级 CRITICAL）</li>
 *   <li>HTTP 写方法 / browser_use → MEDIUM，需审批</li>
 * </ul>
 *
 * @author wang.zhen
 */
public final class BuiltinToolRiskConfig {

    private BuiltinToolRiskConfig() {
    }

    private static final Map<String, ToolRiskInfo> BUILTIN_TOOLS;

    static {
        Map<String, ToolRiskInfo> map = new HashMap<>();

        // ============ Aegis 自建工具（4） ============

        map.put("web_search", ToolRiskInfo.builder()
                .toolName("web_search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("互联网搜索，只读查询，无数据变更风险")
                .category("search")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("image_search", ToolRiskInfo.builder()
                .toolName("image_search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("图片搜索，只读查询，无数据变更风险")
                .category("search")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // web_fetch: 网页正文抓取，只读 + SSRF 防护，宿主 Jsoup 执行不进沙箱
        map.put("web_fetch", ToolRiskInfo.builder()
                .toolName("web_fetch")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("网页正文抓取，只读，经 UrlSafetyChecker SSRF 防护，无数据变更风险")
                .category("search")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // generate_file: 文档产出工具，用户主动请求，安全写操作
        map.put("generate_file", ToolRiskInfo.builder()
                .toolName("generate_file")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.LOW)
                .riskReason("用户主动请求的文档产出工具，安全写操作，无需审批")
                .category("file")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // http_request: 基础LOW，参数评估会根据HTTP方法动态调整
        map.put("http_request", ToolRiskInfo.builder()
                .toolName("http_request")
                .toolType(ToolType.EXTERNAL_NETWORK)
                .riskLevel(RiskLevel.LOW)
                .riskReason("HTTP请求，根据方法动态评估风险：GET只读放行，写操作需审批")
                .category("network")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // ============ 框架 ShellExecuteTool（1，framework-drive=true 时经沙箱 Pod 执行） ============

        // execute: AgentScope ShellExecuteTool，K8s 沙箱 Pod 内执行 shell/python 命令。
        // MEDIUM + 审批：沙箱隔离了宿主风险，但任意代码执行仍需用户知情确认。
        map.put("execute", ToolRiskInfo.builder()
                .toolName("execute")
                .toolType(ToolType.CODE_EXEC)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("AgentScope ShellExecuteTool，在 K8s 沙箱 Pod 内执行命令，需用户确认")
                .category("code")
                .needApproval(true)
                .sandboxExecution(true)
                .build());

        // ============ 框架 FilesystemTool 拆分（6，走沙箱文件系统） ============

        map.put("read_file", ToolRiskInfo.builder()
                .toolName("read_file")
                .toolType(ToolType.FILE_OPS)
                .riskLevel(RiskLevel.LOW)
                .riskReason("读取沙箱工作区文件内容，只读操作，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        map.put("list_files", ToolRiskInfo.builder()
                .toolName("list_files")
                .toolType(ToolType.FILE_OPS)
                .riskLevel(RiskLevel.LOW)
                .riskReason("列出沙箱工作区文件，只读操作，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        map.put("grep_files", ToolRiskInfo.builder()
                .toolName("grep_files")
                .toolType(ToolType.FILE_OPS)
                .riskLevel(RiskLevel.LOW)
                .riskReason("沙箱工作区文件内容搜索，只读查询，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        map.put("glob_files", ToolRiskInfo.builder()
                .toolName("glob_files")
                .toolType(ToolType.FILE_OPS)
                .riskLevel(RiskLevel.LOW)
                .riskReason("沙箱工作区文件模式搜索，只读查询，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        map.put("write_file", ToolRiskInfo.builder()
                .toolName("write_file")
                .toolType(ToolType.FILE_OPS)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("写入沙箱工作区文件，产生数据变更，需用户确认")
                .category("file")
                .needApproval(true)
                .sandboxExecution(true)
                .build());

        map.put("edit_file", ToolRiskInfo.builder()
                .toolName("edit_file")
                .toolType(ToolType.FILE_OPS)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("编辑沙箱工作区已有文件内容，产生数据变更，需审批")
                .category("file")
                .needApproval(true)
                .sandboxExecution(true)
                .build());

        // ============ 框架 Subagent / Task 工具（7） ============

        map.put("agent_spawn", ToolRiskInfo.builder()
                .toolName("agent_spawn")
                .toolType(ToolType.AGENT)
                .riskLevel(RiskLevel.LOW)
                .riskReason("框架内部子智能体生成调度，无外部副作用，无需审批")
                .category("agent")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("agent_list", ToolRiskInfo.builder()
                .toolName("agent_list")
                .toolType(ToolType.AGENT)
                .riskLevel(RiskLevel.LOW)
                .riskReason("列出子智能体，只读查询，无数据变更风险")
                .category("agent")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("agent_send", ToolRiskInfo.builder()
                .toolName("agent_send")
                .toolType(ToolType.AGENT)
                .riskLevel(RiskLevel.LOW)
                .riskReason("向子智能体发送消息，内部调度，无外部副作用")
                .category("agent")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("task_list", ToolRiskInfo.builder()
                .toolName("task_list")
                .toolType(ToolType.ASYNC)
                .riskLevel(RiskLevel.LOW)
                .riskReason("列出后台任务，只读查询，无数据变更风险")
                .category("task")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("task_output", ToolRiskInfo.builder()
                .toolName("task_output")
                .toolType(ToolType.ASYNC)
                .riskLevel(RiskLevel.LOW)
                .riskReason("读取后台任务输出，只读查询，无数据变更风险")
                .category("task")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("task_cancel", ToolRiskInfo.builder()
                .toolName("task_cancel")
                .toolType(ToolType.ASYNC)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("取消正在运行的后台任务，产生执行中断副作用，需用户确认")
                .category("task")
                .needApproval(true)
                .sandboxExecution(false)
                .build());

        map.put("wait_async_results", ToolRiskInfo.builder()
                .toolName("wait_async_results")
                .toolType(ToolType.ASYNC)
                .riskLevel(RiskLevel.LOW)
                .riskReason("等待异步任务结果，内部调度，无外部副作用")
                .category("task")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // ============ 框架 Memory / Session 工具（6） ============

        map.put("memory_search", ToolRiskInfo.builder()
                .toolName("memory_search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("记忆搜索，只读查询，无数据变更风险")
                .category("memory")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("memory_get", ToolRiskInfo.builder()
                .toolName("memory_get")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("读取记忆条目，只读查询，无数据变更风险")
                .category("memory")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("memory_save", ToolRiskInfo.builder()
                .toolName("memory_save")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.LOW)
                .riskReason("保存会话记忆，仅写入内部记忆库，不影响用户数据")
                .category("memory")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("session_search", ToolRiskInfo.builder()
                .toolName("session_search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("会话搜索，只读查询，无数据变更风险")
                .category("session")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("session_list", ToolRiskInfo.builder()
                .toolName("session_list")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("列出历史会话，只读查询，无数据变更风险")
                .category("session")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        map.put("session_history", ToolRiskInfo.builder()
                .toolName("session_history")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("读取会话历史，只读查询，无数据变更风险")
                .category("session")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // ============ 框架 Skill 工具（2） ============

        map.put("skill_creator", ToolRiskInfo.builder()
                .toolName("skill_creator")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("创建技能会注册新工具到系统，扩大后续执行能力，需审批")
                .category("skill")
                .needApproval(true)
                .sandboxExecution(false)
                .build());

        map.put("load_skill_through_path", ToolRiskInfo.builder()
                .toolName("load_skill_through_path")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("按路径加载技能，只读加载，无数据变更风险")
                .category("skill")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // ============ MCP 场景兜底（1） ============

        map.put("browser_use", ToolRiskInfo.builder()
                .toolName("browser_use")
                .toolType(ToolType.EXTERNAL_NETWORK)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("浏览器自动化操作，可能涉及登录提交等有副作用的操作")
                .category("browser")
                .needApproval(true)
                .sandboxExecution(false)
                .build());

        BUILTIN_TOOLS = Collections.unmodifiableMap(map);
    }

    /**
     * 获取全部内置工具风险配置的只读快照。
     *
     * @return 内置工具名 → 风险信息 的不可变 Map
     */
    public static Map<String, ToolRiskInfo> getAllTools() {
        return BUILTIN_TOOLS;
    }

    /**
     * 根据工具名称获取内置风险配置。
     *
     * @param name 工具名称（小写）
     * @return 风险信息，非内置工具返回 null
     */
    public static ToolRiskInfo getRiskInfo(String name) {
        if (name == null) {
            return null;
        }
        return BUILTIN_TOOLS.get(name.toLowerCase());
    }

    /**
     * 判断工具是否为内置工具。
     *
     * @param name 工具名称
     * @return 是否为内置工具
     */
    public static boolean isBuiltinTool(String name) {
        return name != null && BUILTIN_TOOLS.containsKey(name.toLowerCase());
    }

    /**
     * 根据工具名和参数动态评估风险等级。
     *
     * <p>在基础风险信息上叠加参数级规则评估，例如：
     * <ul>
     *   <li>write_file 覆盖写配置文件 → 升级为 CRITICAL</li>
     *   <li>execute 包含 rm/del 等破坏性命令 → 升级为 CRITICAL</li>
     * </ul>
     *
     * @param name   工具名称
     * @param params 工具参数
     * @return 动态评估后的风险信息
     */
    public static ToolRiskInfo evaluateRiskWithParams(String name, Map<String, Object> params) {
        ToolRiskInfo baseInfo = getRiskInfo(name);
        if (baseInfo == null) {
            return ToolRiskInfo.defaultHighRisk(name);
        }

        if (params == null || params.isEmpty()) {
            return baseInfo;
        }

        RiskLevel currentLevel = baseInfo.getRiskLevel();
        String currentReason = baseInfo.getRiskReason();

        if ("write_file".equals(name)) {
            Object pathValue = params.get("path");
            if (pathValue != null) {
                String path = String.valueOf(pathValue).toLowerCase();
                if (path.endsWith(".env") || path.endsWith(".yaml") || path.endsWith(".yml")
                        || path.endsWith(".toml") || path.contains("config")) {
                    currentLevel = RiskLevel.CRITICAL;
                    currentReason = "写入配置文件（" + path + "），可能影响系统行为，需强制审批";
                }
            }
            Object overwriteValue = params.get("overwrite");
            if (Boolean.FALSE.equals(overwriteValue)) {
                currentLevel = RiskLevel.LOW;
                currentReason = "创建新文件（不覆盖），风险降低";
            }
        }

        // execute（框架 ShellExecuteTool）：破坏性命令升级 CRITICAL
        if ("execute".equals(name)) {
            Object cmdValue = params.get("command");
            if (cmdValue != null) {
                String cmd = String.valueOf(cmdValue).toLowerCase();
                if (containsDestructiveCommand(cmd)) {
                    currentLevel = RiskLevel.CRITICAL;
                    currentReason = "执行包含破坏性命令的指令，需强制审批";
                }
            }
        }

        // http_request 动态评估：根据HTTP方法区分只读与写操作
        if ("http_request".equals(name)) {
            return evaluateHttpRequestRisk(params);
        }

        if (currentLevel != baseInfo.getRiskLevel()) {
            return ToolRiskInfo.builder()
                    .toolName(baseInfo.getToolName())
                    .toolType(baseInfo.getToolType())
                    .riskLevel(currentLevel)
                    .riskReason(currentReason)
                    .category(baseInfo.getCategory())
                    .needApproval(currentLevel.ordinal() >= RiskLevel.MEDIUM.ordinal())
                    .sandboxExecution(baseInfo.isSandboxExecution())
                    .paramRiskRules(baseInfo.getParamRiskRules())
                    .build();
        }

        return baseInfo;
    }

    private static final Pattern DESTRUCTIVE_CMD_PATTERN = Pattern.compile(
            "\\b(rm|del|delete|drop|truncate|shutdown|reboot|format|mkfs)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static boolean containsDestructiveCommand(String cmd) {
        return DESTRUCTIVE_CMD_PATTERN.matcher(cmd).find();
    }

    /**
     * HTTP 请求动态风险评估。
     *
     * <p>根据 HTTP 方法区分只读与写操作：
     * GET / HEAD / OPTIONS → 只读 LOW，无需审批
     * POST / PUT / DELETE / PATCH → 有副作用 MEDIUM，需审批
     *
     * @param params 工具参数（包含 method、url 等）
     * @return 动态评估后的风险信息
     */
    private static ToolRiskInfo evaluateHttpRequestRisk(Map<String, Object> params) {
        String method = params.get("method") != null
                ? String.valueOf(params.get("method")).toUpperCase()
                : "GET";

        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return ToolRiskInfo.builder()
                    .toolName("http_request")
                    .toolType(ToolType.EXTERNAL_NETWORK)
                    .riskLevel(RiskLevel.LOW)
                    .riskReason("HTTP只读请求（" + method + "），无数据变更风险")
                    .category("network")
                    .needApproval(false)
                    .sandboxExecution(false)
                    .build();
        }

        return ToolRiskInfo.builder()
                .toolName("http_request")
                .toolType(ToolType.EXTERNAL_NETWORK)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("HTTP写请求（" + method + "），产生数据变更，需用户确认")
                .category("network")
                .needApproval(true)
                .sandboxExecution(false)
                .build();
    }
}
