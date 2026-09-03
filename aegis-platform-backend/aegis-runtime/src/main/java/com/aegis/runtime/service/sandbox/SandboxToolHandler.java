package com.aegis.runtime.service.sandbox;

import java.util.Map;

/**
 * 沙箱内命令转换器。
 * <p>
 * 业务参数 → 沙箱内执行命令。Spring 自动收集 Map<toolName, handler>。
 */
public interface SandboxToolHandler {

    /** 对应工具名 */
    String toolName();

    /** 业务参数 → 沙箱内命令 */
    String toSandboxCommand(Map<String, Object> params);
}
