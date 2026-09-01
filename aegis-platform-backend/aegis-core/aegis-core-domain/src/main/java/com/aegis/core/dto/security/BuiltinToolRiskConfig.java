package com.aegis.core.dto.security;

import com.aegis.core.dto.security.ToolRiskInfo.RiskLevel;
import com.aegis.core.enums.resource.ToolType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 内置工具风险配置。
 *
 * <p>集中管理平台内置工具的静态风险配置，供 {@link com.aegis.runtime.service.ToolRiskService}
 * 查询与评估。内置工具的风险等级为代码硬编码，确保核心工具的风险基线不可被数据库配置覆盖。</p>
 *
 * <h3>内置工具风险分级</h3>
 * <ul>
 *   <li>read_file / search / list_files → LOW，无需审批</li>
 *   <li>write_file / network_request → MEDIUM，需审批</li>
 *   <li>execute_command / delete_file → HIGH，需审批</li>
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

        map.put("read_file", ToolRiskInfo.builder()
                .toolName("read_file")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("读取文件内容，只读操作，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        map.put("search", ToolRiskInfo.builder()
                .toolName("search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("搜索查询，只读操作，无数据变更风险")
                .category("query")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        map.put("list_files", ToolRiskInfo.builder()
                .toolName("list_files")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("列出文件列表，只读操作，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        map.put("write_file", ToolRiskInfo.builder()
                .toolName("write_file")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("写入文件，产生数据变更，需用户确认")
                .category("file")
                .needApproval(true)
                .sandboxExecution(true)
                .build());

        map.put("execute_command", ToolRiskInfo.builder()
                .toolName("execute_command")
                .toolType(ToolType.CODE_EXEC)
                .riskLevel(RiskLevel.HIGH)
                .riskReason("执行系统命令，可能影响系统环境，需审批")
                .category("system")
                .needApproval(true)
                .sandboxExecution(true)
                .build());

        map.put("delete_file", ToolRiskInfo.builder()
                .toolName("delete_file")
                .toolType(ToolType.HIGH_RISK)
                .riskLevel(RiskLevel.HIGH)
                .riskReason("删除文件，不可逆操作，需审批")
                .category("file")
                .needApproval(true)
                .sandboxExecution(true)
                .build());

        map.put("network_request", ToolRiskInfo.builder()
                .toolName("network_request")
                .toolType(ToolType.EXTERNAL_NETWORK)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("网络请求，存在数据出境风险，需审批")
                .category("network")
                .needApproval(true)
                .sandboxExecution(false)
                .build());

        // ============ AgentScope 框架内置工具 ============

        map.put("web_search", ToolRiskInfo.builder()
                .toolName("web_search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("互联网搜索，只读查询，无数据变更风险")
                .category("search")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        map.put("image_search", ToolRiskInfo.builder()
                .toolName("image_search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("图片搜索，只读查询，无数据变更风险")
                .category("search")
                .needApproval(false)
                .sandboxExecution(true)
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

        // file_write: 与 generate_file 类似，产出文件，安全写操作
        map.put("file_write", ToolRiskInfo.builder()
                .toolName("file_write")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.LOW)
                .riskReason("文件产出工具，安全写操作，无需审批")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // file_read: 读取文件内容，只读操作
        map.put("file_read", ToolRiskInfo.builder()
                .toolName("file_read")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("读取文件内容，只读操作，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // file_list: 列出文件列表，只读操作
        map.put("file_list", ToolRiskInfo.builder()
                .toolName("file_list")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("列出文件列表，只读操作，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // write_file: AgentScope 内置写入工具
        map.put("write_file", ToolRiskInfo.builder()
                .toolName("write_file")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.LOW)
                .riskReason("文件产出工具，安全写操作，无需审批")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // ============ AgentScope 框架内置工具 ============

        // glob_files: 只读文件搜索
        map.put("glob_files", ToolRiskInfo.builder()
                .toolName("glob_files")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("文件搜索，只读查询，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // list_dir: 列出目录内容
        map.put("list_dir", ToolRiskInfo.builder()
                .toolName("list_dir")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("列出目录内容，只读操作，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // read_file_content: 读取文件内容
        map.put("read_file_content", ToolRiskInfo.builder()
                .toolName("read_file_content")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("读取文件内容，只读操作，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // write_file_content: 写入文件内容
        map.put("write_file_content", ToolRiskInfo.builder()
                .toolName("write_file_content")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.LOW)
                .riskReason("文件产出工具，安全写操作，无需审批")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // create_directory: 创建目录
        map.put("create_directory", ToolRiskInfo.builder()
                .toolName("create_directory")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.LOW)
                .riskReason("创建目录，安全操作，无需审批")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // move_file: 移动/重命名文件
        map.put("move_file", ToolRiskInfo.builder()
                .toolName("move_file")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.LOW)
                .riskReason("移动文件位置，无内容变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // copy_file: 复制文件
        map.put("copy_file", ToolRiskInfo.builder()
                .toolName("copy_file")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.LOW)
                .riskReason("复制文件，安全操作")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
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

        map.put("browser_use", ToolRiskInfo.builder()
                .toolName("browser_use")
                .toolType(ToolType.EXTERNAL_NETWORK)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("浏览器自动化操作，可能涉及登录提交等有副作用的操作")
                .category("browser")
                .needApproval(true)
                .sandboxExecution(false)
                .build());

        map.put("code_interpreter", ToolRiskInfo.builder()
                .toolName("code_interpreter")
                .toolType(ToolType.CODE_EXEC)
                .riskLevel(RiskLevel.HIGH)
                .riskReason("代码解释器，在沙箱中执行代码，存在资源风险")
                .category("code")
                .needApproval(true)
                .sandboxExecution(true)
                .build());

        map.put("task", ToolRiskInfo.builder()
                .toolName("task")
                .toolType(ToolType.INTERNAL_API)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("子代理调度，消耗额外资源，需确认")
                .category("agent")
                .needApproval(true)
                .sandboxExecution(false)
                .build());

        // grep_files: 只读内容搜索（框架内置）
        map.put("grep_files", ToolRiskInfo.builder()
                .toolName("grep_files")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("文件内容搜索，只读查询，无数据变更风险")
                .category("file")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // edit_file: 修改已有文件内容，变更用户数据
        map.put("edit_file", ToolRiskInfo.builder()
                .toolName("edit_file")
                .toolType(ToolType.WRITE)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("编辑已有文件内容，产生数据变更，需审批")
                .category("file")
                .needApproval(true)
                .sandboxExecution(true)
                .build());

        // aegis_execute: Aegis 代码执行入口（在 Aegis 后台沙箱池中执行，无高危风险，无需审批）
        map.put("aegis_execute", ToolRiskInfo.builder()
                .toolName("aegis_execute")
                .toolType(ToolType.CODE_EXEC)
                .riskLevel(RiskLevel.LOW)
                .riskReason("在 Aegis 后台沙箱池中执行代码，环境隔离，无高危风险")
                .category("code")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // execute: AgentScope 内置 shell 执行工具（保留配置，标记为低风险）
        map.put("execute", ToolRiskInfo.builder()
                .toolName("execute")
                .toolType(ToolType.CODE_EXEC)
                .riskLevel(RiskLevel.LOW)
                .riskReason("AgentScope 内置 shell 执行工具，用于通用命令执行")
                .category("code")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // image_generation: AI 图像生成，只产出内容无外部副作用
        map.put("image_generation", ToolRiskInfo.builder()
                .toolName("image_generation")
                .toolType(ToolType.INTERNAL_API)
                .riskLevel(RiskLevel.MEDIUM)
                .riskReason("AI 图像生成，消耗算力资源，只产出内容无外部副作用")
                .category("media")
                .needApproval(false)
                .sandboxExecution(false)
                .build());

        // memory_search: 记忆搜索，只读查询（LLM可能产生的工具名）
        map.put("memory_search", ToolRiskInfo.builder()
                .toolName("memory_search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("记忆搜索，只读查询，无数据变更风险")
                .category("search")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // session_search: 会话搜索，只读查询（LLM可能产生的工具名）
        map.put("session_search", ToolRiskInfo.builder()
                .toolName("session_search")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("会话搜索，只读查询，无数据变更风险")
                .category("search")
                .needApproval(false)
                .sandboxExecution(true)
                .build());

        // search_history: 历史搜索，只读查询（LLM可能产生的工具名）
        map.put("search_history", ToolRiskInfo.builder()
                .toolName("search_history")
                .toolType(ToolType.READONLY)
                .riskLevel(RiskLevel.LOW)
                .riskReason("历史搜索，只读查询，无数据变更风险")
                .category("search")
                .needApproval(false)
                .sandboxExecution(true)
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
     *   <li>execute_command 包含 rm/del → 升级为 CRITICAL</li>
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

        if ("execute_command".equals(name)) {
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