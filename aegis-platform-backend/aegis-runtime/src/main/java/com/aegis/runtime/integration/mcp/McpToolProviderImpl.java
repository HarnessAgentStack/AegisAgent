package com.aegis.runtime.integration.mcp;

import com.aegis.core.dto.resource.ToolVO;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.core.spi.McpToolProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具动态查询提供者实现。
 *
 * <p>基于 {@link McpInvoker} 通过 SSE/HTTP 协议动态查询 MCP 服务暴露的工具列表，
 * 不再依赖数据库持久化存储。实现 {@link McpToolProvider} 接口，
 * 供 admin 模块的 {@link com.aegis.admin.service.resource.McpManageService} 调用。
 *
 * <p>核心机制：
 * <ul>
 *   <li>通过 McpInvoker 获取或创建 MCP 客户端连接</li>
 *   <li>调用 MCP 协议的 tools/list 方法实时获取工具列表</li>
 *   <li>将 McpSchema.Tool 转换为 ToolVO 返回</li>
 *   <li>连接缓存由 McpInvoker 管理，30 分钟空闲过期</li>
 * </ul>
 *
 * @author wang.zhen
 * @see McpInvoker
 * @see McpToolProvider
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolProviderImpl implements McpToolProvider {

    private final McpInvoker mcpInvoker;

    @Override
    public List<ToolVO> queryTools(Long mcpServiceId) {
        if (mcpServiceId == null) {
            log.warn("queryTools: mcpServiceId 为 null");
            return List.of();
        }

        try {
            // 单次调用 listTools（内部已 REST 优先 + AgentScope 回退），避免再加一层
            // listToolVOs 回退造成同端点 2 次 RPC。listTools 返回 McpSchema.Tool，
            // 这里转换为 ToolVO（保留 inputSchema 等字段）。
            List<McpSchema.Tool> mcpTools = mcpInvoker.listTools(mcpServiceId.toString());
            if (mcpTools != null && !mcpTools.isEmpty()) {
                List<ToolVO> result = new ArrayList<>(mcpTools.size());
                for (McpSchema.Tool mcpTool : mcpTools) {
                    result.add(convertToToolVO(mcpTool));
                }
                log.info("queryTools: 获取 MCP 工具列表成功, serviceId={}, count={}",
                        mcpServiceId, result.size());
                return result;
            }

            log.info("queryTools: MCP 服务无可用工具, serviceId={}", mcpServiceId);
            return List.of();
        } catch (Exception e) {
            log.error("queryTools: 查询 MCP 工具列表失败, serviceId={}, error={}",
                    mcpServiceId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 将 MCP Schema 工具转换为 ToolVO。
     */
    private ToolVO convertToToolVO(McpSchema.Tool mcpTool) {
        ToolVO.ToolVOBuilder builder = ToolVO.builder()
                .toolCode(mcpTool.name())
                .toolName(mcpTool.name())
                .description(mcpTool.description())
                .sourceType(ToolSourceType.MCP)
                .toolType(parseToolType(mcpTool))
                .readOnly(determineReadOnly(mcpTool))
                .inputSchema(serializeInputSchema(mcpTool));

        return builder.build();
    }

    /**
     * 解析工具类型。
     */
    private ToolType parseToolType(McpSchema.Tool tool) {
        if (tool == null || tool.inputSchema() == null) {
            return ToolType.READONLY;
        }
        // McpSchema.JsonSchema 有 type() 方法返回 schema 类型
        McpSchema.JsonSchema schema = tool.inputSchema();
        if (schema != null && schema.type() != null) {
            String type = schema.type();
            if ("object".equalsIgnoreCase(type)) {
                return ToolType.READONLY;
            }
        }
        return ToolType.READONLY;
    }

    /**
     * 判断工具是否为只读。
     */
    private Boolean determineReadOnly(McpSchema.Tool tool) {
        // MCP 工具默认视为只读，实际权限由 MCP 服务端控制
        return Boolean.TRUE;
    }

    /**
     * 序列化输入 Schema 为 JSON 字符串。
     */
    private String serializeInputSchema(McpSchema.Tool tool) {
        if (tool == null || tool.inputSchema() == null) {
            return null;
        }
        try {
            return com.alibaba.fastjson2.JSON.toJSONString(tool.inputSchema());
        } catch (Exception e) {
            log.warn("序列化 inputSchema 失败: tool={}", tool.name(), e);
            return null;
        }
    }
}
