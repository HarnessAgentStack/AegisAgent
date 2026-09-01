package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * MCP工具协议。
 *
 * <p>遵循 MCP (Model Context Protocol) 规范的传输协议类型：
 * <ul>
 *   <li>{@link #STDIO} - 标准输入输出，用于本地 MCP 服务</li>
 *   <li>{@link #SSE} - Server-Sent Events，旧版远程传输</li>
 *   <li>{@link #STREAMABLE_HTTP} - 可流式 HTTP，MCP 2025 规范推荐传输</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Getter
public enum McpProtocol {
    STDIO("标准输入输出（本地）"),
    SSE("Server-Sent Events"),
    STREAMABLE_HTTP("可流式 HTTP（MCP 2025）");

    private final String desc;

    McpProtocol(String desc) {
        this.desc = desc;
    }
}
