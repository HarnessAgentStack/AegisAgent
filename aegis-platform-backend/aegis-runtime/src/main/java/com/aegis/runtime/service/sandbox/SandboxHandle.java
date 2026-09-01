package com.aegis.runtime.service.sandbox;

/**
 * 沙箱就绪句柄（T1 沙箱惰性分配）。
 *
 * <p>由 {@link SandboxReadinessGate#awaitSandboxReady} 返回，封装已分配沙箱实例的
 * K8s 定位信息（instanceId / podName / namespace），供沙箱类工具
 * （{@code AegisExecuteTool} 等）直接 {@code sandboxBackend.exec} 使用，
 * 替代原先工具内独立 {@code allocateSlot} 的双分配路径。
 *
 * <p>不可变 record，保证跨线程发布的 happens-before 语义（§6.3）。
 *
 * @author wang.zhen
 */
public record SandboxHandle(String instanceId, String podName, String namespace,
                             String slotKey, String sessionId) {
}
