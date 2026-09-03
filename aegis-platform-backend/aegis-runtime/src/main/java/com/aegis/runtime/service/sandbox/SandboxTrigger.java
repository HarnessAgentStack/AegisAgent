package com.aegis.runtime.service.sandbox;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 沙箱触发判定器（周期 3，能力白名单模式）。
 *
 * <p>判定工具调用是否需要沙箱：执行任意/不可信代码需沙箱；
 * LLM/RAG/读 Workspace/受信 MCP/DB 不需要。{@code aegis_execute} 是成员非唯一。</p>
 *
 * <h3>白名单（需沙箱）</h3>
 * <ul>
 *   <li>{@code aegis_execute} — 代码执行</li>
 *   <li>{@code execute}/{@code shell} — Shell 命令（如启用）</li>
 *   <li>{@code run_script} — 运行脚本</li>
 *   <li>{@code build_test} — 构建测试</li>
 *   <li>{@code exec_attachment} — 不可信附件执行</li>
 * </ul>
 *
 * <h3>黑名单（不需沙箱）</h3>
 * <ul>
 *   <li>{@code web_search}/{@code image_search} — 外部 API 调用</li>
 *   <li>{@code memory_search}/{@code session_search} — 内存检索</li>
 *   <li>{@code file_read}/{@code file_write}/{@code file_list} — 读自身 Workspace（RemoteFS）</li>
 *   <li>{@code kb_query} — 知识库 RAG</li>
 *   <li>{@code mcp_*} — 受信 MCP 服务</li>
 * </ul>
 *
 * <p>设计：白名单显式枚举沙箱能力工具，新增沙箱工具只需加入 {@link #SANDBOX_TOOLS}，
 * 不再在中间件 prompt 里硬编码工具名（解耦 M3 问题）。</p>
 *
 * @author wang.zhen
 */
@Component
public class SandboxTrigger {

    /** 沙箱能力工具白名单（执行不可信代码的场景） */
    private static final Set<String> SANDBOX_TOOLS = Set.of(
            "aegis_execute",
            "execute",
            "shell",
            "sh",
            "bash",
            "run_script",
            "build_test",
            "exec_attachment",
            "generate_file"
    );

    /**
     * 判定工具调用是否需触发沙箱分配。
     *
     * @param toolName 工具名（大小写不敏感）
     * @return true 表示需沙箱
     */
    public boolean requiresSandbox(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return SANDBOX_TOOLS.contains(toolName.toLowerCase());
    }

    /**
     * 判定工具调用是否需触发沙箱分配（带工具类型辅助判断）。
     *
     * <p>CODE_EXEC/SHELL_EXEC 类型工具恒需沙箱；其他类型走工具名白名单。</p>
     *
     * @param toolName 工具名
     * @param toolType 工具类型（CODE_EXEC/SHELL_EXEC/SEARCH/MEMORY 等，可 null）
     * @return true 表示需沙箱
     */
    public boolean requiresSandbox(String toolName, String toolType) {
        if (toolType != null) {
            String upper = toolType.toUpperCase();
            if (upper.contains("CODE_EXEC") || upper.contains("SHELL_EXEC")
                    || upper.contains("SCRIPT") || upper.contains("BUILD")) {
                return true;
            }
        }
        return requiresSandbox(toolName);
    }
}
