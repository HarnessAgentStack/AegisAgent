package com.aegis.runtime.service.sandbox;



import com.aegis.runtime.integration.config.RuntimeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 沙箱执行器：收敛 framework-drive / pool executor 双路径。
 * <p>
 * fail-closed：沙箱不可用时拒绝执行。
 * 实际执行委托给 AegisExecuteTool 自身的双路径逻辑（已正确实现），
 * 本执行器仅做"判定 + 统一入口"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxExecutor {

    private final RuntimeProperties runtimeProperties;
    private final SandboxPolicyResolver policyResolver;
    private final ApplicationContext applicationContext;
    private final Map<String, SandboxToolHandler> handlers;

    /**
     * 判定工具是否应该进沙箱（供中间件使用）。
     */
    public boolean shouldUseSandbox(Long tenantId, String toolName) {
        Boolean policy = policyResolver.resolve(tenantId, toolName);
        if (Boolean.TRUE.equals(policy)) {
            return true;
        }
        // 未配置时，对已知执行类工具默认进沙箱
        return switch (toolName) {
            case "aegis_execute", "aegis_generate_file", "shell", "run_script" -> true;
            default -> false;
        };
    }

    /**
     * 构建沙箱内命令（供后续扩展直接执行时使用）。
     */
    public String buildSandboxCommand(String toolName, Map<String, Object> params) {
        SandboxToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            log.warn("SandboxExecutor: 未找到 {} 的 SandboxToolHandler", toolName);
            return params.toString();
        }
        return handler.toSandboxCommand(params);
    }
}
