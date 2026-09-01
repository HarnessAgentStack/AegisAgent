package com.aegis.mcp.demo.client;

import com.aegis.mcp.demo.dto.McpServiceRegisterRequest;
import com.aegis.mcp.demo.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 调用 aegis-admin MCP 服务注册接口的 Feign Client。
 *
 * <p>对齐 admin 的 {@code POST /api/admin/resource/mcp/services/register}
 * （Service-to-Service 免鉴权端点），同时支持上送 MCP 工具列表。
 */
@FeignClient(name = "aegis-admin", url = "${aegis.mcp.demo.admin-base-url}")
public interface AdminMcpClient {

    /**
     * 向 admin 提交 MCP 服务自注册（Service-to-Service）。
     *
     * <p>该端点在 admin SecurityConfig 中已配置为 permitAll。
     * 请求体包含 MCP 服务元信息和工具列表，admin 会自动完成：
     * <ol>
     *   <li>创建 MCP 服务记录（DRAFT 态）</li>
     *   <li>将 tools 注册到 res_tool 表</li>
     *   <li>提交审核流程</li>
     * </ol>
     */
    @PostMapping("/api/admin/resource/mcp/services/register")
    Result<Long> registerService(@RequestBody McpServiceRegisterRequest request,
                                 @RequestHeader(value = "X-Server-Key", required = false) String serverKey);
}
