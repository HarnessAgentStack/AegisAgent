package com.aegis.mcp.demo.collector;

import com.aegis.mcp.demo.annotation.McpTool;
import com.aegis.mcp.demo.annotation.McpToolParam;
import com.aegis.mcp.demo.dto.McpToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * MCP 工具元信息采集器。
 *
 * <p>扫描所有 {@link McpTool} 自定义注解，获取完整元数据
 * （含 readOnly、toolType、inputSchema 等），
 * 用于自动注册到 admin 和 REST 端点暴露。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolCollector {

    private final ApplicationContext applicationContext;

    /**
     * 采集所有 MCP 工具定义。
     *
     * @return 工具定义列表，按发现顺序排列
     */
    public List<McpToolDefinition> collect() {
        List<McpToolDefinition> tools = new ArrayList<>();
        Set<String> collectedCodes = new HashSet<>();

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Component.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            Class<?> beanClass = bean.getClass();

            Map<Method, McpTool> mcpToolMethods = MethodIntrospector.selectMethods(beanClass,
                    (MethodIntrospector.MetadataLookup<McpTool>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, McpTool.class));

            for (Map.Entry<Method, McpTool> methodEntry : mcpToolMethods.entrySet()) {
                Method method = methodEntry.getKey();
                McpTool annotation = methodEntry.getValue();

                McpToolDefinition definition = buildDefinition(annotation, method, beanName);
                tools.add(definition);
                collectedCodes.add(definition.getToolCode());

                log.info("采集 MCP 工具: bean={}, method={}, toolCode={}, readOnly={}",
                        beanName, method.getName(), definition.getToolCode(), definition.getReadOnly());
            }
        }

        log.info("MCP 工具采集完成: 共发现 {} 个工具", tools.size());
        return tools;
    }

    private McpToolDefinition buildDefinition(McpTool annotation, Method method, String beanName) {
        String toolCode = annotation.name();
        String toolName = annotation.name();
        String description = annotation.description();

        Map<String, Object> inputSchema = buildInputSchema(method);
        String inputSchemaJson = toJson(inputSchema);

        Map<String, Object> outputSchema = buildOutputSchema(method);
        String outputSchemaJson = toJson(outputSchema);

        boolean readOnly = isReadOnly(beanName, toolCode);
        String toolType = resolveToolType(beanName, toolCode, readOnly);

        return McpToolDefinition.builder()
                .toolCode(toolCode)
                .toolName(toolName)
                .description(description)
                .toolType(toolType)
                .readOnly(readOnly)
                .inputSchema(inputSchemaJson)
                .outputSchema(outputSchemaJson)
                .build();
    }

    private Map<String, Object> buildInputSchema(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : method.getParameters()) {
            McpToolParam paramAnno = AnnotatedElementUtils.findMergedAnnotation(param, McpToolParam.class);
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", mapJavaTypeToJsonSchema(param.getType()));
            if (paramAnno != null && !paramAnno.description().isBlank()) {
                prop.put("description", paramAnno.description());
            }
            if (paramAnno != null && paramAnno.required()) {
                required.add(param.getName());
            }
            properties.put(param.getName(), prop);
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private Map<String, Object> buildOutputSchema(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            schema.put("type", "null");
        } else if (Map.class.isAssignableFrom(returnType)) {
            schema.put("type", "object");
        } else if (List.class.isAssignableFrom(returnType)) {
            schema.put("type", "array");
        } else {
            schema.put("type", mapJavaTypeToJsonSchema(returnType));
        }
        return schema;
    }

    private String mapJavaTypeToJsonSchema(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class) return "number";
        if (type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (List.class.isAssignableFrom(type)) return "array";
        if (Map.class.isAssignableFrom(type)) return "object";
        return "object";
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();
            if (val instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (val instanceof List) {
                sb.append("[]");
            } else if (val instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) val;
                sb.append(toJson(m));
            } else {
                sb.append(String.valueOf(val));
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private boolean isReadOnly(String beanName, String toolCode) {
        return switch (toolCode) {
            case "query_production_data", "query_device_status", "query_alarm_logs" -> true;
            default -> true;
        };
    }

    private String resolveToolType(String beanName, String toolCode, boolean readOnly) {
        return switch (toolCode) {
            case "query_production_data" -> "EXTERNAL_NETWORK";
            case "query_device_status" -> "INTERNAL_API";
            case "query_alarm_logs" -> "EXTERNAL_NETWORK";
            default -> readOnly ? "READONLY" : "WRITE";
        };
    }
}
