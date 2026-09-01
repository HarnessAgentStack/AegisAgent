package com.aegis.runtime.service.sandbox;

/**
 * 沙箱未就绪异常（T1 沙箱惰性分配）。
 *
 * <p>当 {@link SandboxReadinessGate#awaitSandboxReady} 同步兜底分配失败
 * （池满 SERVICE_UNAVAILABLE / 配额超限 / 分配异常）时抛出。
 * 沙箱类工具门控层捕获后转为结构化 {@code ToolResultBlock(ERROR)} 回传 LLM，
 * <b>不阻塞 SSE 流</b>（流已在产出首 Token）。
 *
 * <p>携带 sessionId / slotKey 便于日志追踪与可观测（§12）。
 *
 * @author wang.zhen
 */
public class SandboxNotReadyException extends RuntimeException {

    private final String sessionId;
    private final String slotKey;

    public SandboxNotReadyException(String sessionId, String slotKey, String message) {
        super(message);
        this.sessionId = sessionId;
        this.slotKey = slotKey;
    }

    public SandboxNotReadyException(String sessionId, String slotKey, String message, Throwable cause) {
        super(message, cause);
        this.sessionId = sessionId;
        this.slotKey = slotKey;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSlotKey() {
        return slotKey;
    }
}
