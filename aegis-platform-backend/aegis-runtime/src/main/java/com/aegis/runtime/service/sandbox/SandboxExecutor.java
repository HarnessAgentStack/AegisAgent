package com.aegis.runtime.service.sandbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 沙箱执行器：判定工具是否应该进沙箱 + 工具自身沙箱执行能力检查。
 *
 * <p>职责边界：
 * <ul>
 *   <li>{@link #shouldUseSandbox} — 判定工具是否应该走沙箱（策略优先 + 默认降级）</li>
 *   <li>{@link #toolHasSandboxCapability} — 工具自身是否具备沙箱执行路径</li>
 *   <li>策略数据源：{@link SandboxPolicyResolver}（sec_sandbox_policy 表 + Caffeine 缓存）</li>
 * </ul>
 *
 * <p>与 AgentScope 2.0.2 框架的集成点（framework-drive.enabled=true，生产默认）：
 * 框架 HarnessAgent 自动注册 ShellExecuteTool("execute") + FilesystemTool 拆分
 * （read_file / write_file / list_files / grep_files / glob_files / edit_file），
 * 文件系统为 SandboxBackedFilesystem（implements AbstractSandboxFilesystem），
 * 上述 7 个工具经框架 SandboxLifecycleMiddleware → AegisSandboxClient → K8s Pod 执行。
 * 自建 aegis_execute 已删除（与框架 ShellExecuteTool 重复造轮子）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxExecutor {

    private final SandboxPolicyResolver policyResolver;

    /**
     * 判定工具是否应该进沙箱。
     *
     * <p>决策顺序：
     * <ol>
     *   <li>策略配置 {@code sec_sandbox_policy.sandbox_execution=true} → 强制进沙箱</li>
     *   <li>策略配置 {@code sandbox_execution=false} → 明确不进</li>
     *   <li>策略未配置（null）→ 默认决策：框架执行/文件类工具进，其余不进</li>
     * </ol>
     *
     * @param tenantId 租户ID（用于查策略）
     * @param toolName 工具编码（与 res_tool.tool_code 对齐）
     */
    public boolean shouldUseSandbox(Long tenantId, String toolName) {
        Boolean policy = policyResolver.resolve(tenantId, toolName);
        if (policy != null) {
            return policy; // 策略已明确
        }
        // 未配置时的默认决策（防御性兜底，正常情况策略表已有全部 27 个种子的行）
        return toolHasSandboxCapability(toolName);
    }

    /**
     * 工具自身是否具备沙箱执行能力。
     *
     * <p>运营配置了 {@code sandbox_execution=true} 但工具自身没有沙箱执行路径时，
     * 策略会"静默失效"——本方法让 SandboxRoutingMiddleware 能识别这种白配场景并告警。
     *
     * <p>能力来源（与 BuiltinToolRiskConfig.sandboxExecution 对齐）：
     * <ul>
     *   <li>框架硬编码（7 项）：execute / read_file / write_file / list_files /
     *       grep_files / glob_files / edit_file —— 通过 SandboxBackedFilesystem
     *       自动经 SandboxLifecycleMiddleware 走 K8s 沙箱 Pod</li>
     *   <li>其余工具（web_search / image_search / generate_file / http_request /
     *       内部调度与检索类）在宿主安全执行（Java 实现 + SSRF 防护），无沙箱路径</li>
     * </ul>
     *
     * @param toolName 工具编码
     * @return true=工具自身会走沙箱执行；false=宿主执行或 MCP 外部服务执行
     */
    public boolean toolHasSandboxCapability(String toolName) {
        if (toolName == null) return false;
        return switch (toolName) {
            // 框架 Harness 内置：自动通过 SandboxLifecycleMiddleware 走沙箱
            case "execute", "read_file", "write_file", "list_files",
                 "grep_files", "glob_files", "edit_file" -> true;
            // 自建工具宿主安全执行（SSRF 防护/POI 生成），内部调度无外部副作用
            default -> false;
        };
    }
}
