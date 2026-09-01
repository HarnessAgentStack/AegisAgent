package com.aegis.mcp.demo.controller;

import com.aegis.mcp.demo.collector.McpToolCollector;
import com.aegis.mcp.demo.dto.McpToolDefinition;
import com.aegis.mcp.demo.dto.Result;
import com.aegis.mcp.demo.tools.IndustrialTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具 REST 控制器（精简版）。
 *
 * <p>仅 2 个面向业务的核心工具：
 * <ol>
 *   <li>{@code query_production_plan} — 生产计划与执行进度</li>
 *   <li>{@code query_device_status} — 设备实时运行状态</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpToolController {

    private final McpToolCollector toolCollector;
    private final IndustrialTools industrialTools;

    private List<McpToolDefinition> cachedTools;

    @GetMapping("/tools")
    public Result<List<McpToolDefinition>> listTools() {
        if (cachedTools == null) {
            cachedTools = toolCollector.collect();
        }
        return Result.success(cachedTools);
    }

    @GetMapping("/tools/{code}")
    public Result<McpToolDefinition> getTool(@PathVariable String code) {
        if (cachedTools == null) {
            cachedTools = toolCollector.collect();
        }
        return cachedTools.stream()
                .filter(t -> t.getToolCode().equals(code))
                .findFirst()
                .map(Result::success)
                .orElse(Result.error(404, "工具不存在: " + code));
    }

    /**
     * 调用工具 — switch 分支数量与 IndustrialTools 中 @McpTool 注解方法一一对应。
     */
    @PostMapping("/tools/{code}/invoke")
    public Result<Object> invokeTool(@PathVariable String code, @RequestBody Map<String, Object> params) {
        log.info("调用 MCP 工具: code={}, params={}", code, params);

        Object result = switch (code) {
            case "query_production_plan" -> invokeProductionPlan(params);
            case "query_device_status"  -> invokeDeviceStatus(params);
            default -> throw new IllegalArgumentException("未知工具: " + code);
        };

        return Result.success(result);
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of(
                "status", "UP",
                "tools", cachedTools != null ? cachedTools.size() : 0
        ));
    }

    // ============ 工具调用实现 ============

    private Object invokeProductionPlan(Map<String, Object> params) {
        String lineCode = getString(params, "lineCode", null);
        String date     = getString(params, "date", null);
        String status   = getString(params, "status", null);
        return industrialTools.queryProductionPlan(lineCode, date, status);
    }

    private Object invokeDeviceStatus(Map<String, Object> params) {
        String deviceCode   = getString(params, "deviceCode", null);
        String statusFilter = getString(params, "statusFilter", null);
        return industrialTools.queryDeviceStatus(deviceCode, statusFilter);
    }

    // ============ 参数解析 ============

    private String getString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        if (val != null) return val.toString();
        return defaultValue;
    }
}
