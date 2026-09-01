package com.aegis.admin.service.resource;

import com.aegis.core.domain.resource.McpService;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.McpProtocol;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.dal.mapper.resource.McpServiceMapper;
import com.aegis.dal.mapper.resource.ToolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具同步服务 — 将 MCP Server 暴露的工具同步到 res_tool 表。
 *
 * <p>res_tool 表作为 MCP 工具的缓存层，提供：
 * <ul>
 *   <li>工作台资源面板的完整工具 Schema 展示</li>
 *   <li>工具搜索与索引能力</li>
 *   <li>MCP Server 暂时不可用时的降级数据源</li>
 * </ul>
 *
 * <p>同步策略：MCP Server 激活/重启时触发增量同步，对比现有记录进行增/改/删。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolSyncService {

    private final ToolMapper toolMapper;
    private final McpServiceMapper mcpServiceMapper;
    private final McpClientService mcpClientService;

    /**
     * 同步指定 MCP 服务的工具到 res_tool。
     *
     * @param serviceId     MCP 服务ID
     * @param endpoint      MCP 服务端点
     * @param protocol      协议类型
     * @param securityLevel 安全等级（继承自 MCP 服务）
     */
    public void syncTools(Long serviceId, String endpoint,
                          McpProtocol protocol, SecurityLevel securityLevel) {
        log.info("[MCP-ToolSync] 开始同步工具, serviceId={}, endpoint={}, protocol={}",
                serviceId, endpoint, protocol);

        List<com.aegis.core.dto.resource.ToolVO> remoteTools = mcpClientService.queryTools(endpoint, protocol);
        if (remoteTools == null || remoteTools.isEmpty()) {
            log.warn("[MCP-ToolSync] 远程 MCP 服务无工具, serviceId={}", serviceId);
            deleteAllByService(serviceId);
            updateServiceMeta(serviceId, 0);
            return;
        }

        List<Tool> existingTools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getMcpServiceId, serviceId)
                .eq(Tool::getSourceType, ToolSourceType.MCP));

        Map<String, Tool> existingByCode = existingTools.stream()
                .collect(Collectors.toMap(Tool::getToolCode, t -> t, (a, b) -> a));

        int inserted = 0, updated = 0, deleted = 0;

        for (com.aegis.core.dto.resource.ToolVO remote : remoteTools) {
            String toolCode = remote.getToolCode();
            if (toolCode == null || toolCode.isBlank()) continue;

            Tool existing = existingByCode.get(toolCode);
            if (existing != null) {
                updateTool(existing, remote, securityLevel);
                updated++;
            } else {
                insertTool(serviceId, remote, securityLevel);
                inserted++;
            }
            existingByCode.remove(toolCode);
        }

        for (Tool stale : existingByCode.values()) {
            toolMapper.deleteById(stale.getId());
            deleted++;
        }

        updateServiceMeta(serviceId, remoteTools.size());

        log.info("[MCP-ToolSync] 同步完成, serviceId={}, total={}, inserted={}, updated={}, deleted={}",
                serviceId, remoteTools.size(), inserted, updated, deleted);
    }

    /**
     * 异步同步（用于 MCP 服务激活后不阻塞主流程）。
     */
    @Async
    public void asyncSyncTools(Long serviceId, String endpoint,
                               McpProtocol protocol, SecurityLevel securityLevel) {
        try {
            syncTools(serviceId, endpoint, protocol, securityLevel);
        } catch (Exception e) {
            log.error("[MCP-ToolSync] 异步同步失败, serviceId={}", serviceId, e);
        }
    }

    /**
     * 从 res_tool 获取某 MCP 服务的工具列表（缓存降级路径）。
     */
    public List<Tool> getCachedTools(Long serviceId) {
        return toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getMcpServiceId, serviceId)
                .eq(Tool::getSourceType, ToolSourceType.MCP)
                .eq(Tool::getStatus, CommonStatus.NORMAL));
    }

    private void insertTool(Long serviceId, com.aegis.core.dto.resource.ToolVO remote,
                            SecurityLevel securityLevel) {
        Tool tool = new Tool();
        tool.setToolCode(remote.getToolCode());
        tool.setToolName(remote.getToolName() != null ? remote.getToolName() : remote.getToolCode());
        tool.setDescription(remote.getDescription());
        tool.setToolType(remote.getToolType() != null ? remote.getToolType() : ToolType.READONLY);
        tool.setSourceType(ToolSourceType.MCP);
        tool.setMcpServiceId(serviceId);
        tool.setReadOnly(remote.getReadOnly() != null ? remote.getReadOnly() : true);
        tool.setInputSchema(remote.getInputSchema());
        tool.setOutputSchema(remote.getOutputSchema());
        tool.setSecurityLevel(securityLevel != null ? securityLevel : SecurityLevel.L1);
        tool.setStatus(CommonStatus.NORMAL);
        tool.setCreateTime(LocalDateTime.now());
        tool.setUpdateTime(LocalDateTime.now());
        toolMapper.insert(tool);
    }

    private void updateTool(Tool existing, com.aegis.core.dto.resource.ToolVO remote,
                            SecurityLevel securityLevel) {
        boolean changed = false;
        if (!safeEquals(existing.getToolName(), remote.getToolName())) {
            existing.setToolName(remote.getToolName());
            changed = true;
        }
        if (!safeEquals(existing.getDescription(), remote.getDescription())) {
            existing.setDescription(remote.getDescription());
            changed = true;
        }
        if (remote.getToolType() != null && existing.getToolType() != remote.getToolType()) {
            existing.setToolType(remote.getToolType());
            changed = true;
        }
        if (!safeEquals(existing.getInputSchema(), remote.getInputSchema())) {
            existing.setInputSchema(remote.getInputSchema());
            changed = true;
        }
        if (securityLevel != null && existing.getSecurityLevel() != securityLevel) {
            existing.setSecurityLevel(securityLevel);
            changed = true;
        }
        if (changed) {
            existing.setUpdateTime(LocalDateTime.now());
            toolMapper.updateById(existing);
        }
    }

    private void deleteAllByService(Long serviceId) {
        toolMapper.delete(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getMcpServiceId, serviceId)
                .eq(Tool::getSourceType, ToolSourceType.MCP));
    }

    private void updateServiceMeta(Long serviceId, int count) {
        mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                .eq(McpService::getId, serviceId)
                .set(McpService::getToolCount, count));
    }

    private boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
