package com.aegis.runtime.integration.tool;

import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.integration.config.RuntimeProperties;
import com.aegis.runtime.integration.sandbox.AegisSandbox;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.integration.agent.ToolResultCache;
import com.aegis.runtime.service.sandbox.AegisSandboxPoolExecutor;
import com.aegis.runtime.service.sandbox.SandboxSessionHolder;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.sandbox.ExecResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * execute 工具（AgentScope 2.0 ToolBase 子类模式）。
 *
 * <p>在沙箱环境中执行代码（主要是 Python），用于计算、数据处理等场景。
 * 沙箱组件不可用时 fail-closed 拒绝执行（P0-3：禁止降级裸跑宿主）。
 *
 * <h3>执行路径（周期4 灰度双路径）</h3>
 * <pre>
 *   aegis.runtime.sandbox.framework-drive.enabled=true
 *     → SandboxSessionHolder.acquireIfNeeded（惰性会话分配）
 *       → AegisSandbox.exec（委托 AegisSandboxAllocator + ISandboxBackend，心跳回写 + op_log 审计）
 *
 *   aegis.runtime.sandbox.framework-drive.enabled=false（默认）
 *     → AegisSandboxPoolExecutor.exec（Phase 2 补完路径：查池 → 探活复用实例 → exec；无可用实例时池内扩容）
 * </pre>
 *
 * <h3>功能</h3>
 * <p>执行 Python 代码，返回执行结果和标准输出。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class AegisExecuteTool extends ToolBase {

    private final ToolResultCache toolResultCache;
    private final ISandboxBackend sandboxBackend;
    /** 沙箱池极简执行器：查池 → 探活复用实例 → exec（Phase 2 减法后的补完路径，灰度关闭时的回退路径） */
    @Deprecated
    private final AegisSandboxPoolExecutor sandboxPoolExecutor;
    /** 周期3：会话级沙箱持有器（灰度开启时，执行路径经此获取 Sandbox 实例） */
    private final SandboxSessionHolder sandboxSessionHolder;
    /** 运行时配置（含框架驱动灰度开关） */
    private final RuntimeProperties runtimeProperties;

    /** execute 的 inputSchema（JSON Schema）— 同时支持 code 与 command 两种参数 */
    private static final Map<String, Object> INPUT_SCHEMA = JSON.parseObject(
            "{"
                    + "\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"code\":{\"type\":\"string\",\"description\":\"要执行的代码（Python 代码片段）\"},"
                    + "\"command\":{\"type\":\"string\",\"description\":\"要执行的 Python 代码片段（兼容参数，等同于 code）\"},"
                    + "\"language\":{\"type\":\"string\",\"description\":\"编程语言，默认python\"}"
                    + "},"
                    + "\"anyOf\":["
                    + "{\"required\":[\"code\"]},"
                    + "{\"required\":[\"command\"]}"
                    + "],"
                    + "\"additionalProperties\":false"
                    + "}");

    /** 代码执行超时时间（秒） */
    private static final long EXEC_TIMEOUT_SEC = 30;

    /**
     * 构造函数：注入 Spring Bean 并描述工具元数据。
     *
     * @param toolResultCache      工具结果缓存（用于填充 tool_result SSE 事件）
     * @param sandboxBackend       沙箱后端（fail-closed 检查用；执行路径由灰度开关决定）
     * @param sandboxPoolExecutor  Phase 2 补完执行器（灰度关闭时回退路径）
     * @param sandboxSessionHolder 周期3会话持有器（灰度开启时，acquireIfNeeded 惰性分配）
     * @param runtimeProperties    运行时配置（含 framework-drive 灰度开关）
     */
    public AegisExecuteTool(ToolResultCache toolResultCache,
                             ISandboxBackend sandboxBackend,
                             AegisSandboxPoolExecutor sandboxPoolExecutor,
                             SandboxSessionHolder sandboxSessionHolder,
                             RuntimeProperties runtimeProperties) {
        super(ToolBase.builder()
                .name("aegis_execute")
                .description("【Aegis 代码执行 - Python 计算与数据处理】\n"
                        + "触发场景: 用户要求进行数学计算、数据处理、生成序列等编程任务时。\n"
                        + "调用规则:\n"
                        + "- code 或 command 为必填参数，传递 Python 代码片段。\n"
                        + "- 代码将在 Aegis 后台沙箱池环境中执行，使用隔离的 K8s Pod，不会影响系统。\n"
                        + "- 请勿执行包含文件删除、系统修改等危险操作的代码。\n"
                        + "返回: {result, stdout, stderr, language}。")
                .inputSchema(INPUT_SCHEMA));
        this.toolResultCache = toolResultCache;
        this.sandboxBackend = sandboxBackend;
        this.sandboxPoolExecutor = sandboxPoolExecutor;
        this.sandboxSessionHolder = sandboxSessionHolder;
        this.runtimeProperties = runtimeProperties;
    }

    /**
     * 覆盖权限检查：execute工具为低风险工具（在沙箱中执行代码），工具自检返回 ALLOW。
     *
     * <p>注：AS PermissionEngine 评估序为 deny → ask → 工具自检 → allow，
     * ask/deny 规则先于工具自检评估——此处 ALLOW 不"跳过"审批，仅表示工具自身不触发 ASK。
     * 真正的审批由上层 sec_tool_policy/HitlNode 的 ASK 规则驱动（命中则先于本自检生效）。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(PermissionDecision.builder()
                .behavior(PermissionBehavior.ALLOW)
                .message("Execute tool allowed by Aegis framework")
                .decisionReason("execute tool - low risk, sandbox execution, managed by Aegis policy engine")
                .build());
    }

    /**
     * 异步执行代码。
     *
     * <p>优先使用 K8s 沙箱执行，沙箱不可用时降级为本地执行。
     *
     * @param param 工具调用参数
     * @return 执行结果块
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String toolCallId = extractToolCallId(param);

        Long tenantId = resolveTenantId(param);
        Long userId = resolveUserId(param);
        Long agentId = resolveAgentId(param);
        String sessionId = resolveSessionId(param);
        // A1：解析智能体类型，用于构建与框架一致的 scope/slotKey（复用智能体级沙箱）
        String agentType = resolveAgentType(param);

        // 同时支持 code 与 command 两种入参（LLM 可能传 command 作为代码容器）
        String code = input != null ? getString(input, "code") : null;
        if (code == null || code.isEmpty()) {
            code = input != null ? getString(input, "command") : null;
        }
        String language = input != null ? getString(input, "language") : "python";

        if (code == null || code.isEmpty()) {
            String errorResult = "{\"error\": \"Parameter 'code' or 'command' is required\"}";
            toolResultCache.put(toolCallId, errorResult);
            return Mono.just(errorResult(toolCallId, "Parameter 'code' or 'command' is required"));
        }

        // 若 command 形式为 "python xxx.py" 或 "python -c '...'"，自动提取为代码
        String actualCode = normalizeCode(code);

        final String codeToExecute = actualCode;
        final String lang = language != null ? language : "python";
        final Long tid = tenantId;
        final Long uid = userId;
        final Long aid = agentId;
        final String sid = sessionId;
        final String atype = agentType;

        return Mono.fromCallable(() -> {
            log.info("execute 工具开始执行: language={}, codeLen={}, tenantId={}, userId={}, agentId={}, sessionId={}, agentType={}",
                    lang, codeToExecute.length(), tid, uid, aid, sid, atype);

            Map<String, Object> result = new HashMap<>(4);
            result.put("language", lang);

            // P0-3：fail-closed —— 沙箱不可用时不降级宿主执行（原降级会裸跑宿主 Python，
            // 继承父进程环境/权限且无独立工作目录，沙箱故障窗口 = 无审批直通宿主）。
            // 灰度开启：framework-drive 路径需要 SandboxSessionHolder + ISandboxBackend；
            // 灰度关闭：Phase 2 补完路径需要 ISandboxBackend + AegisSandboxPoolExecutor；
            // 共同必须：tenantId（沙箱池所有查询均绑定租户，缺租户上下文 = 无法路由）。
            boolean frameworkDrive = isFrameworkDriveEnabled();
            boolean sandboxReady = frameworkDrive
                    ? (sandboxSessionHolder != null && sandboxBackend != null && tid != null)
                    : (sandboxBackend != null && sandboxPoolExecutor != null && tid != null);
            if (!sandboxReady) {
                log.error("execute 工具沙箱组件不可用，fail-closed 拒绝执行: frameworkDrive={}, backend={}, poolExec={}, sessionHolder={}, tenantId={}",
                        frameworkDrive, sandboxBackend != null, sandboxPoolExecutor != null,
                        sandboxSessionHolder != null, tid);
                result.put("result", null);
                result.put("stdout", "");
                result.put("stderr", "沙箱不可用：执行环境未配置或租户上下文缺失，代码未执行");
                result.put("success", false);
                String errorJson = JSON.toJSONString(result);
                toolResultCache.put(toolCallId, errorJson);
                return errorResult(toolCallId, "沙箱不可用，代码未执行");
            }

            try {
                String executionResult = executeInSandbox(tid, uid, aid, sid, atype, lang, codeToExecute);
                result.put("result", executionResult);
                result.put("stdout", executionResult);
                result.put("stderr", "");
                result.put("success", true);
                log.info("execute 工具沙箱执行成功: language={}, resultLen={}",
                        lang, executionResult.length());
            } catch (Exception e) {
                log.error("execute 工具沙箱执行失败，fail-closed 不降级宿主: language={}, error={}", lang, e.getMessage());
                result.put("result", null);
                result.put("stdout", "");
                result.put("stderr", "沙箱执行失败: " + e.getMessage());
                result.put("success", false);
                String errorJson = JSON.toJSONString(result);
                toolResultCache.put(toolCallId, errorJson);
                return errorResult(toolCallId, "沙箱执行失败: " + e.getMessage());
            }

            String json = JSON.toJSONString(result);
            toolResultCache.put(toolCallId, json);
            return successResult(toolCallId, json);
        });
    }

    /**
     * 在沙箱中执行代码（周期4 灰度双路径）。
     *
     * <pre>
     *  framework-drive=true:  SandboxSessionHolder.acquireIfNeeded（惰性会话分配）
     *                          → AegisSandbox.exec（心跳回写 + allocator.touch）
     *  framework-drive=false: AegisSandboxPoolExecutor.exec（Phase 2 补完路径）
     * </pre>
     *
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @param agentId   Agent ID
     * @param sessionId 会话ID
     * @param agentType 智能体类型（UNIVERSAL/APPLICATION/SYSTEM）
     * @param language  编程语言
     * @param code      代码内容
     * @return 执行结果 stdout（stderr 为空时）或 stderr
     */
    private String executeInSandbox(Long tenantId, Long userId, Long agentId,
                                     String sessionId, String agentType,
                                     String language, String code) throws Exception {
        if (!"python".equalsIgnoreCase(language)) {
            throw new UnsupportedOperationException(
                    "Unsupported language: " + language + ". Currently only Python is supported.");
        }

        // 1. 包装脚本：Base64 用户代码 + stdout 捕获 + 无输出时表达式求值回退
        String wrapped = wrapPythonCode(code);
        // 2. 二次 Base64 传输，规避 shell 引号转义
        String b64Script = java.util.Base64.getEncoder()
                .encodeToString(wrapped.getBytes(StandardCharsets.UTF_8));
        String command = "echo " + b64Script + " | base64 -d | python3 -";

        if (isFrameworkDriveEnabled()) {
            // ========== 路径A：周期4 框架驱动（灰度开启） ==========
            log.info("execute[framework] 走框架驱动路径: sessionId={}, agentType={}", sessionId, agentType);
            AegisSandbox sandbox = sandboxSessionHolder.acquireIfNeeded(
                    sessionId, tenantId, userId, agentId, agentType);
            ExecResult r = sandbox.exec(null, command, (int) EXEC_TIMEOUT_SEC);
            String stdout = r.stdout() != null ? r.stdout().trim() : "";
            String stderr = r.stderr() != null ? r.stderr().trim() : "";
            if (r.exitCode() != 0) {
                throw new IllegalStateException("沙箱执行退出码 " + r.exitCode() + ": "
                        + (stderr.isEmpty() ? stdout : stderr));
            }
            return stdout.isEmpty() ? stderr : stdout;
        } else {
            // ========== 路径B：Phase 2 补完路径（灰度关闭，默认） ==========
            log.info("execute[legacy] 走 Phase 2 补完路径: sessionId={}", sessionId);
            ISandboxBackend.ExecResult result =
                    sandboxPoolExecutor.exec(tenantId, userId, agentId, sessionId, agentType,
                            command, EXEC_TIMEOUT_SEC);
            if (result == null) {
                throw new IllegalStateException("沙箱执行返回空结果");
            }
            String stdout = result.stdout != null ? result.stdout.trim() : "";
            String stderr = result.stderr != null ? result.stderr.trim() : "";
            if (result.exitCode != 0) {
                throw new IllegalStateException("沙箱执行退出码 " + result.exitCode + ": "
                        + (stderr.isEmpty() ? stdout : stderr));
            }
            return stdout.isEmpty() ? stderr : stdout;
        }
    }

    /**
     * 是否启用框架驱动执行（周期4 灰度开关）。
     *
     * <p>读取 {@code aegis.runtime.sandbox.framework-drive.enabled}；
     * 配置缺失/未注入时安全返回 false（保守降级旧路径）。</p>
     */
    private boolean isFrameworkDriveEnabled() {
        try {
            return runtimeProperties != null
                    && runtimeProperties.getSandbox() != null
                    && runtimeProperties.getSandbox().getFrameworkDrive() != null
                    && runtimeProperties.getSandbox().getFrameworkDrive().isEnabled();
        } catch (Exception e) {
            log.debug("execute: 读取 framework-drive 灰度开关异常，安全降级: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 包装 Python 代码以捕获输出。
     *
     * <p>修复：原实现将用户代码通过 {@code %s} 模板注入 {@code try:} 块内但未缩进，
     * 导致 {@code IndentationError}；且 {@code .formatted()} 会被用户代码中的
     * {@code %} / {@code {}} 字符破坏。
     *
     * <p>新实现将用户代码 Base64 编码后传入，由包装脚本解码并经
     * {@code exec(compile(...))} 在独立命名空间中执行，彻底消除模板拼接的
     * 缩进与转义问题；无 print 输出时回退为对最后一个非注释行做表达式求值
     * （与 exec 共享同一 globals，支持 {@code total = 1+1} 后单独输出 {@code total} 的场景）。
     */
    private String wrapPythonCode(String code) {
        String b64 = java.util.Base64.getEncoder()
                .encodeToString(code.getBytes(StandardCharsets.UTF_8));
        return """
                import sys, io, base64, traceback
                _src = base64.b64decode("%s").decode("utf-8")
                _g = {"__name__": "__main__"}
                _buf = io.StringIO()
                _old = sys.stdout
                sys.stdout = _buf
                try:
                    exec(compile(_src, "<user_code>", "exec"), _g)
                except BaseException as e:
                    _buf.write("Error: " + str(e) + "\\n")
                    traceback.print_exc(file=_buf)
                finally:
                    sys.stdout = _old
                _out = _buf.getvalue()
                if not _out.strip():
                    try:
                        _lines = [l.strip() for l in _src.strip().splitlines()
                                  if l.strip() and not l.strip().startswith("#")]
                        if _lines:
                            _r = eval(_lines[-1], _g)
                            if _r is not None:
                                print(_r)
                    except BaseException:
                        pass
                else:
                    print(_out, end="")
                """.formatted(b64);
    }

    /**
     * 规范化代码输入。
     *
     * <p>LLM 可能以以下几种形式传入：
     * <ul>
     *   <li>纯 Python 代码片段 → 直接返回</li>
     *   <li>{@code python -c '...'} 形式 → 提取 -c 后的代码</li>
     *   <li>{@code python filename.py} 形式 → 读取文件内容（若文件存在）</li>
     * </ul>
     *
     * @param input 原始输入
     * @return 规范化后的 Python 代码
     */
    private String normalizeCode(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        // 形如 python -c "print('hello')" 或 python -c 'print("hello")'
        if (trimmed.startsWith("python ")) {
            String rest = trimmed.substring("python ".length()).trim();
            if (rest.startsWith("-c ")) {
                String codePart = rest.substring(3).trim();
                // 去掉最外层引号
                if ((codePart.startsWith("\"") && codePart.endsWith("\""))
                        || (codePart.startsWith("'") && codePart.endsWith("'"))) {
                    codePart = codePart.substring(1, codePart.length() - 1);
                }
                // 去掉 shell 引号与转义
                return codePart.replace("\\\"", "\"").replace("\\'", "'");
            }
            // python xxx.py 形式 —— 尝试读取文件
            String filename = rest.trim();
            java.io.File file = new java.io.File(filename);
            if (file.exists() && file.isFile()) {
                try {
                    return java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn("读取 Python 文件失败: {}", e.getMessage());
                }
            }
            // 文件不存在则回退为原字符串（可能是未在本地生成的文件名）
            return "# [文件未找到，无法执行] 原命令: " + trimmed;
        }

        // 不是以 python 开头，视为纯代码直接返回
        return trimmed;
    }

    // ============ 辅助方法 ============

    /**
     * 从 ToolCallParam 中提取工具调用 ID。
     */
    private String extractToolCallId(ToolCallParam param) {
        if (param == null || param.getToolUseBlock() == null) {
            return null;
        }
        return param.getToolUseBlock().getId();
    }

    /**
     * 从 Map 中安全提取 String 值。
     */
    private String getString(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * 从 ToolCallParam 的 RuntimeContext 解析 tenantId。
     */
    private Long resolveTenantId(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) {
                return null;
            }
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            if (taskCtx == null) {
                return null;
            }
            return taskCtx.getTenantId();
        } catch (Exception e) {
            log.warn("execute: 解析 tenantId 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 ToolCallParam 的 RuntimeContext 解析 userId。
     */
    private Long resolveUserId(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) {
                return null;
            }
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            if (taskCtx == null) {
                return null;
            }
            return taskCtx.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 ToolCallParam 的 RuntimeContext 解析 agentId。
     */
    private Long resolveAgentId(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) {
                return null;
            }
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            if (taskCtx == null) {
                return null;
            }
            return taskCtx.getAgentId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 ToolCallParam 的 RuntimeContext 解析 sessionId。
     */
    private String resolveSessionId(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) {
                return null;
            }
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            if (taskCtx == null) {
                return null;
            }
            return taskCtx.getSessionId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A1：从 ToolCallParam 的 RuntimeContext 解析智能体类型。
     *
     * <p>取自 {@link AegisTaskContext#getAgentDef()} 的 agentType（枚举名），
     * 用于与框架一致的 scope/slotKey 构建和 Coordinator 池路由决策。
     */
    private String resolveAgentType(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) {
                return null;
            }
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            if (taskCtx == null || taskCtx.getAgentDef() == null
                    || taskCtx.getAgentDef().getAgentType() == null) {
                return null;
            }
            return taskCtx.getAgentDef().getAgentType().name();
        } catch (Exception e) {
            log.debug("execute: 解析 agentType 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构造成功结果块。
     */
    private ToolResultBlock successResult(String toolCallId, String text) {
        return new ToolResultBlock(
                toolCallId,
                "aegis_execute",
                List.of(TextBlock.builder().text(text != null ? text : "").build()),
                Map.of(),
                ToolResultState.SUCCESS);
    }

    /**
     * 构造错误结果块。
     */
    private ToolResultBlock errorResult(String toolCallId, String errorMessage) {
        return new ToolResultBlock(
                toolCallId,
                "aegis_execute",
                List.of(TextBlock.builder().text("Error: " + errorMessage).build()),
                Map.of(),
                ToolResultState.ERROR);
    }
}
