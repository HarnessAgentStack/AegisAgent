package com.aegis.runtime.integration.workspace;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.domain.workspace.AgentWorkspaceMaterial;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.runtime.service.agent.ResourceQueryService;
import com.aegis.runtime.service.workspace.WorkspaceMaterialService;
import com.aegis.dal.mapper.workspace.AgentWorkspaceMaterialMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.aegis.core.domain.resource.McpService;
import com.aegis.runtime.integration.workspace.BindingSyncMiddleware;

/**
 * 工作区物化器（P0 改造：RedisStore 替代本地磁盘）。
 *
 * <p><b>P0 改造</b>：废弃本地磁盘写入，改为通过 {@link RedisStore} 写入远程 store。
 * AgentScope 的 {@link io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec}
 * 按 {@link IsolationScope} 路由从 Redis 读取工作区文件，实现多实例部署下的 workspace 共享。
 *
 * <h3>命名空间路由</h3>
 * <p>与 {@code RemoteFilesystemSpec.storeNamespace()} 对齐：
 * <ul>
 *   <li>USER scope: {@code ["agents", agentId, "users", userId]} + routeSegment</li>
 *   <li>AGENT scope: {@code ["agents", agentId, "shared"]} + routeSegment</li>
 *   <li>GLOBAL scope: {@code ["global"]} + routeSegment</li>
 * </ul>
 *
 * <p>routeSegment 按文件类型区分：
 * <ul>
 *   <li>tools.json, AGENTS.md -> "root"</li>
 *   <li>skills/ -> "skills"</li>
 * </ul>
 *
 * <h3>值格式</h3>
 * <p>与 {@code RemoteFilesystem.fileDataToStoreValue()} 对齐：
 * <pre>{@code
 * Map.of("content", fileContent, "encoding", "utf-8")
 * }</pre>
 *
 * <h3>指纹机制</h3>
 * <p>基于绑定列表的 resourceType + resourceId + resourceVersion 计算 SHA-256 指纹，
 * 写入 {@link AgentWorkspaceMaterial} 表（upsert），供 {@code BindingSyncMiddleware}
 * 在 Agent 执行前对比指纹，决定是否需要重新物化。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceMaterializer {

    private final ResourceQueryService resourceQueryService;
    private final WorkspaceMaterialService workspaceMaterialService;
    private final BaseStore redisStore;

    /** RedisStore 值中的 content 字段名 */
    private static final String FIELD_CONTENT = "content";

    /** RedisStore 值中的 encoding 字段名 */
    private static final String FIELD_ENCODING = "encoding";

    /** root 路由段（tools.json / AGENTS.md / MEMORY.md） */
    private static final String ROUTE_ROOT = "root";

    /** skills 路由段 */
    private static final String ROUTE_SKILLS = "skills";

    /**
     * 计算指定智能体当前绑定列表的指纹（不写文件、不落库）。
     *
     * <p>供 {@code BindingSyncMiddleware} 在 Agent 执行前对比已存储指纹，
     * 决定是否需要触发重新物化。
     *
     * @param agentId 智能体ID
     * @return 当前绑定指纹（SHA-256 十六进制字符串）
     */
    public String computeFingerprint(long agentId) {
        List<AgentBinding> bindings = resourceQueryService.listEnabledBindings(agentId);
        return computeFingerprint(bindings);
    }

    /**
     * 物化指定智能体的绑定资源到 RedisStore（P0：替代本地磁盘）。
     *
     * <p>按 {@link IsolationScope} 路由到不同的 RedisStore 命名空间，
     * AgentScope 的 {@link io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec}
     * 从相同命名空间读取，实现分布式工作区。
     *
     * @param agentType 智能体类型：UNIVERSAL / APPLICATION / SYSTEM
     * @param agentId   智能体ID
     * @param userId    用户ID（通用智能体按用户隔离，应用/系统智能体为 0）
     * @param bindings  绑定列表（若为 null 则从数据库加载）
     * @return 物化指纹（SHA-256 十六进制字符串）
     */
    public String materialize(String agentType, long agentId, long userId,
                              List<AgentBinding> bindings) {
        IsolationScope scope = resolveIsolationScope(agentType);

        // 查询绑定列表（如未传入则从数据库加载），
        if (bindings == null) {
            bindings = resourceQueryService.listEnabledBindings(agentId);
        }

        // 计算指纹
        String fingerprint = computeFingerprint(bindings);

        // 构建命名空间前缀（不含 routeSegment）
        List<String> nsBase = buildNamespaceBase(scope, String.valueOf(agentId), String.valueOf(userId));

        // 物化各类型资源到 RedisStore
        try {
            materializeTools(nsBase, bindings);
            materializeAgentsMd(nsBase, agentId, bindings);
            log.info("工作区物化完成（RedisStore）: agentId={}, scope={}, fingerprint={}, bindings={}",
                    agentId, scope, fingerprint, bindings.size());
        } catch (Exception e) {
            log.error("物化工作区失败: agentId={}, scope={}", agentId, scope, e);
            throw new RuntimeException("物化工作区失败: " + e.getMessage(), e);
        }

        // upsert 物化指纹记录
        String namespaceStr = String.join("/", nsBase);
        upsertMaterialRecord(agentId, userId, scope.name(), namespaceStr, fingerprint, bindings);

        return fingerprint;
    }

    /**
     * 构建命名空间前缀（与 RemoteFilesystemSpec.storeNamespace() 对齐）。
     *
     * @param scope   隔离作用域
     * @param agentId 智能体ID（字符串形式）
     * @param userId  用户ID（字符串形式）
     * @return 命名空间前缀（不含 routeSegment）
     */
    private List<String> buildNamespaceBase(IsolationScope scope, String agentId, String userId) {
        return switch (scope) {
            case USER -> List.of("agents", agentId, "users", userId);
            case AGENT -> List.of("agents", agentId, "shared");
            case GLOBAL -> List.of("global");
            case SESSION -> List.of("agents", agentId, "sessions", userId);
        };
    }

    /**
     * 向命名空间追加路由段，生成完整命名空间。
     */
    private List<String> withRoute(List<String> nsBase, String routeSegment) {
        List<String> ns = new ArrayList<>(nsBase);
        ns.add(routeSegment);
        return ns;
    }

    /**
     * 物化 TOOL 类型绑定到 tools.json（写入 RedisStore root 命名空间）。
     *
     * <p>tools.json 遵循 AgentScope {@link io.agentscope.harness.agent.tools.ToolsConfig} 格式，
     * 包含 allow 列表（声明允许的工具编码）和 mcpServers 映射（声明 MCP 服务连接）。
     * 工具的实际定义（name/description/inputSchema）由 {@link AegisToolBridge} 注册，
     * 此处仅声明过滤策略与 MCP 服务器配置。
     *
     * <p>值格式：{@code Map.of("content", jsonString, "encoding", "utf-8")}
     */
    private void materializeTools(List<String> nsBase, List<AgentBinding> bindings) {
        List<String> allowTools = new ArrayList<>();
        Map<String, JSONObject> mcpServers = new LinkedHashMap<>();

        for (AgentBinding binding : bindings) {
            if (binding.getResourceType() != ResourceType.TOOL) {
                continue;
            }
            Tool tool = resourceQueryService.findToolById(binding.getResourceId());
            if (tool == null) {
                log.warn("工具不存在，跳过: resourceId={}", binding.getResourceId());
                continue;
            }
            allowTools.add(tool.getToolCode());

            // P1-01: 对 MCP 来源的工具，查询 MCP 服务连接信息并填充 mcpServers
            if (tool.getSourceType() == ToolSourceType.MCP && tool.getMcpServiceId() != null) {
                fillMcpServer(mcpServers, tool.getMcpServiceId());
            }
        }

        // 构建 ToolsConfig 兼容格式
        JSONObject toolsConfig = new JSONObject(new LinkedHashMap<>());
        if (!allowTools.isEmpty()) {
            toolsConfig.put("allow", allowTools);
        }
        if (!mcpServers.isEmpty()) {
            toolsConfig.put("mcpServers", mcpServers);
        }

        List<String> ns = withRoute(nsBase, ROUTE_ROOT);
        Map<String, Object> value = new HashMap<>();
        value.put(FIELD_CONTENT, toolsConfig.toJSONString());
        value.put(FIELD_ENCODING, "utf-8");
        redisStore.put(ns, "tools.json", value);
        log.debug("tools.json 已写入 RedisStore: namespace={}, allowTools={}", ns, allowTools);
    }

    /**
     * P1-01: 查询 MCP 服务连接信息并填充 mcpServers 映射。
     */
    private void fillMcpServer(Map<String, JSONObject> mcpServers, Long mcpServiceId) {
        try {
            McpService mcpService = resourceQueryService.findMcpServiceById(mcpServiceId);
            if (mcpService == null) {
                log.warn("P1-01: MCP 服务不存在: mcpServiceId={}", mcpServiceId);
                return;
            }
            String mcpCode = mcpService.getMcpCode();
            if (mcpCode == null || mcpCode.isBlank()) {
                return;
            }
            JSONObject serverConfig = new JSONObject(new LinkedHashMap<>());
            serverConfig.put("url", mcpService.getEndpoint() != null ? mcpService.getEndpoint() : "");
            if (mcpService.getProtocol() != null) {
                serverConfig.put("transport", mcpService.getProtocol().name().toLowerCase());
            }
            mcpServers.put(mcpCode, serverConfig);
            log.debug("P1-01: mcpServers 填充: mcpCode={}", mcpCode);
        } catch (Exception e) {
            log.warn("P1-01: 查询 MCP 服务失败: mcpServiceId={}", mcpServiceId, e);
        }
    }

    /**
     * 物化 AGENTS.md（写入 RedisStore root 命名空间）。
     *
     * <p>AGENTS.md 包含智能体的基本信息和绑定资源清单，
     * 供 AgentScope 框架的 WorkspaceManager 读取。
     */
    private void materializeAgentsMd(List<String> nsBase, long agentId, List<AgentBinding> bindings) {
        StringBuilder md = new StringBuilder();
        md.append("# Agent ").append(agentId).append("\n\n");
        md.append("## Bound Resources\n\n");

        for (AgentBinding b : bindings) {
            md.append("- **").append(b.getResourceType()).append("**: resourceId=")
              .append(b.getResourceId()).append(", version=").append(b.getResourceVersion())
              .append("\n");
        }

        List<String> ns = withRoute(nsBase, ROUTE_ROOT);
        Map<String, Object> value = new HashMap<>();
        value.put(FIELD_CONTENT, md.toString());
        value.put(FIELD_ENCODING, "utf-8");
        redisStore.put(ns, "AGENTS.md", value);
        log.debug("AGENTS.md 已写入 RedisStore: namespace={}", ns);
    }

    /**
     * 智能体类型 -> IsolationScope 映射。
     *
     * <ul>
     *   <li>UNIVERSAL -> USER（用户级资源）</li>
     *   <li>APPLICATION -> AGENT（智能体级资源）</li>
     *   <li>SYSTEM -> GLOBAL（全局共享资源）</li>
     * </ul>
     */
    private IsolationScope resolveIsolationScope(String agentType) {
        return switch (agentType) {
            case "UNIVERSAL" -> IsolationScope.USER;
            case "APPLICATION" -> IsolationScope.AGENT;
            case "SYSTEM" -> IsolationScope.AGENT;
            // P1-10: 原 SESSION 被 validateScope 拒绝，改为 AGENT
            default -> IsolationScope.AGENT;
        };
    }

    /**
     * 计算绑定列表指纹（委托统一实现）。
     *
     * @see BindingFingerprinter#fingerprint
     */
    private String computeFingerprint(List<AgentBinding> bindings) {
        return BindingFingerprinter.fingerprint(bindings);
    }

    /**
     * upsert 物化指纹记录到 agent_workspace_material 表。
     */
    private void upsertMaterialRecord(long agentId, long userId, String isolationScope,
                                      String namespace, String fingerprint,
                                      List<AgentBinding> bindings) {
        AgentWorkspaceMaterial existing = workspaceMaterialService.findByAgentAndUser(agentId, userId);

        String bindingSnapshot = serializeBindings(bindings);
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            AgentWorkspaceMaterial record = AgentWorkspaceMaterial.builder()
                    .agentId(agentId)
                    .userId(userId)
                    .isolationScope(isolationScope)
                    .workspacePath(namespace)
                    .materialFingerprint(fingerprint)
                    .bindingSnapshot(bindingSnapshot)
                    .lastMaterializedAt(now)
                    .build();
            workspaceMaterialService.insert(record);
        } else {
            existing.setIsolationScope(isolationScope);
            existing.setWorkspacePath(namespace);
            existing.setMaterialFingerprint(fingerprint);
            existing.setBindingSnapshot(bindingSnapshot);
            existing.setLastMaterializedAt(now);
            workspaceMaterialService.updateById(existing);
        }
    }

    /**
     * 序列化绑定列表为 JSON 字符串，作为绑定快照存储。
     */
    private String serializeBindings(List<AgentBinding> bindings) {
        List<JSONObject> snapshot = new ArrayList<>();
        for (AgentBinding b : bindings) {
            JSONObject obj = new JSONObject();
            obj.put("resourceType", b.getResourceType() != null ? b.getResourceType().name() : null);
            obj.put("resourceId", b.getResourceId());
            obj.put("resourceVersion", b.getResourceVersion());
            obj.put("bindingType", b.getBindingType() != null ? b.getBindingType().name() : null);
            snapshot.add(obj);
        }
        return JSON.toJSONString(snapshot);
    }
}
