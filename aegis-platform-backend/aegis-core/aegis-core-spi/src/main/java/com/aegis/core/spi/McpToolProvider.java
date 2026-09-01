package com.aegis.core.spi;

import com.aegis.core.dto.resource.ToolVO;

import java.util.List;

/**
 * MCP 工具动态查询提供者接口。
 *
 * <p>解耦 admin 模块与 runtime 模块，通过接口动态查询 MCP 服务暴露的工具列表。
 * 实现类由 runtime 模块提供（基于 McpInvoker 的 SSE/HTTP 动态查询）。
 *
 * <p>设计原则：
 * <ul>
 *   <li>MCP 工具是服务的动态属性，不应持久化到数据库</li>
 *   <li>智能体运行时通过 SSE 连接实时发现 MCP 工具</li>
 *   <li>管理端展示工具详情时也通过此接口动态获取</li>
 * </ul>
 *
 *  @author wang.zhen
 */
public interface McpToolProvider {

    /**
     * 动态查询指定 MCP 服务暴露的工具列表。
     *
     * <p>通过 MCP 协议（SSE / HTTP）实时获取服务当前发布的工具，
     * 不依赖数据库持久化存储。
     *
     * @param mcpServiceId MCP 服务ID
     * @return 工具列表；服务不可用或查询失败时返回空列表
     */
    List<ToolVO> queryTools(Long mcpServiceId);

    /**
     * 查询 MCP 服务的工具数量（轻量级，用于列表展示）。
     *
     * <p>优先使用数据库缓存的 tool_count 字段，
     * 仅在缓存失效时才进行动态查询。
     *
     * @param mcpServiceId MCP 服务ID
     * @return 工具数量；查询失败返回 -1
     */
    default int queryToolCount(Long mcpServiceId) {
        List<ToolVO> tools = queryTools(mcpServiceId);
        return tools != null ? tools.size() : -1;
    }
}
