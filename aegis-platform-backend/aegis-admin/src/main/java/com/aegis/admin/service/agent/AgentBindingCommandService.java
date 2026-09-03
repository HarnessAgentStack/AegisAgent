package com.aegis.admin.service.agent;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.domain.resource.McpService;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.dal.mapper.agent.AgentBindingMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.aegis.dal.mapper.resource.KnowledgeBaseMapper;
import com.aegis.dal.mapper.resource.McpServiceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 智能体资源绑定命令服务（从 {@link AgentPublishService} 拆出，职责单一）。
 *
 * <p>负责：新增 / 移除 / 查询 智能体的资源绑定。
 * 绑定变更后通知 Runtime 失效模板缓存（行为与原 AgentPublishService 一致）。</p>
 *
 * <p>P1-C：新增 {@link #validateBindable(AgentBinding)} 守护"可绑定 = 已发布且启用"不变式，
 * 覆盖 KNOWLEDGE_BASE（lifeStatus=PUBLISHED）与 MCP_SERVICE（lifeStatus=PUBLISHED + status=ACTIVE）两类。
 * 防止 DRAFT/REVIEWING/REJECTED 的资源被绑到智能体导致运行时找不到资源。</p>
 *
 * @author wang.zhen
 * @see AgentBinding
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBindingCommandService {

    private final AgentDefMapper agentDefMapper;
    private final AgentBindingMapper agentBindingMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final McpServiceMapper mcpServiceMapper;

    /** Runtime 服务地址，用于通知模板缓存失效（须在 application.yml 或 Nacos 显式配置 aegis.runtime.base-url） */
    @org.springframework.beans.factory.annotation.Value("${aegis.runtime.base-url}")
    private String runtimeBaseUrl;

    /** WebClient 用于调用 Runtime 内部 API（懒初始化） */
    private volatile org.springframework.web.reactive.function.client.WebClient runtimeWebClient;

    private org.springframework.web.reactive.function.client.WebClient getRuntimeWebClient() {
        if (runtimeWebClient == null) {
            synchronized (this) {
                if (runtimeWebClient == null) {
                    runtimeWebClient = org.springframework.web.reactive.function.client.WebClient.builder()
                            .baseUrl(runtimeBaseUrl).build();
                }
            }
        }
        return runtimeWebClient;
    }

    /**
     * 通知 Runtime 服务失效指定智能体的模板缓存。
     */
    private void notifyTemplateInvalidation(Long agentId, String version, Long tenantId) {
        try {
            var spec = getRuntimeWebClient().delete()
                    .uri(uriBuilder -> uriBuilder.path("/api/runtime/internal/template-cache/{agentId}")
                            .queryParam("tenantId", tenantId)
                            .queryParamIfPresent("version", java.util.Optional.ofNullable(version))
                            .build(agentId));
            spec.retrieve().toBodilessEntity().subscribe(
                    resp -> log.debug("Template cache invalidation notified: agentId={}", agentId),
                    err -> log.warn("Failed to notify template cache invalidation: agentId={}, error={}", agentId, err.getMessage())
            );
        } catch (Exception e) {
            log.warn("Template cache invalidation notification error: agentId={}, error={}", agentId, e.getMessage());
        }
    }

    /**
     * 新增资源绑定。
     *
     * <p>P1-C：插入前强制 {@link #validateBindable(AgentBinding)} 校验资源"已发布且启用"不变式。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void addBinding(AgentBinding binding) {
        AgentDef existing = requireAgent(binding.getAgentId(), binding.getTenantId());
        if (existing.getLifeStatus() == AgentLifeStatus.ARCHIVED) {
            throw new BusinessException(ResultCode.CONFLICT, "智能体已归档，不可新增绑定");
        }
        // P1-C：守护"可绑定 = 已发布且启用"不变式（防止 DRAFT/REVIEWING/REJECTED 资源绑到智能体）
        validateBindable(binding);

        Long exists = agentBindingMapper.selectCount(new LambdaQueryWrapper<AgentBinding>()
                .eq(AgentBinding::getAgentId, binding.getAgentId())
                .eq(AgentBinding::getResourceType, binding.getResourceType())
                .eq(AgentBinding::getResourceId, binding.getResourceId()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "资源已绑定: " + binding.getResourceType() + "/" + binding.getResourceId());
        }
        binding.setAgentVersion(existing.getVersion());
        if (binding.getEnabled() == null) binding.setEnabled(true);
        agentBindingMapper.insert(binding);
        log.info("AgentBinding added: agentId={}, resourceType={}, resourceId={}",
                binding.getAgentId(), binding.getResourceType(), binding.getResourceId());
        notifyTemplateInvalidation(binding.getAgentId(), null, binding.getTenantId());
    }

    /**
     * P1-C：校验待绑定资源满足"可绑定 = 已发布且启用"不变式。
     *
     * <p>当前覆盖两类常见资源：</p>
     * <ul>
     *   <li>{@code KNOWLEDGE_BASE}：res_knowledge_base.life_status 必须为 PUBLISHED</li>
     *   <li>{@code MCP_SERVICE}：res_mcp_service.life_status 必须为 PUBLISHED 且 status=ACTIVE</li>
     * </ul>
     * <p>其他类型（TOOL/SKILL）当前直接放行（系统内置工具和 GLOBAL 系统技能无需校验），
     * 后续如需可扩展。</p>
     *
     * @throws BusinessException 资源不存在或未发布时抛 CONFLICT
     */
    private void validateBindable(AgentBinding binding) {
        com.aegis.core.enums.resource.ResourceType resourceType = binding.getResourceType();
        Long resourceId = binding.getResourceId();
        if (resourceType == null || resourceId == null) {
            return; // 由其他校验处理
        }
        switch (resourceType) {
            case KNOWLEDGE_BASE: {
                KnowledgeBase kb = knowledgeBaseMapper.selectById(resourceId);
                if (kb == null) {
                    throw new BusinessException(ResultCode.CONFLICT, "知识库不存在: " + resourceId);
                }
                if (!"PUBLISHED".equals(kb.getLifeStatus())) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "知识库未发布，不可绑定：id=" + resourceId + ", lifeStatus=" + kb.getLifeStatus());
                }
                break;
            }
            case MCP_SERVICE: {
                McpService mcp = mcpServiceMapper.selectById(resourceId);
                if (mcp == null) {
                    throw new BusinessException(ResultCode.CONFLICT, "MCP服务不存在: " + resourceId);
                }
                if (!"PUBLISHED".equals(mcp.getLifeStatus())) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "MCP服务未发布，不可绑定：id=" + resourceId + ", lifeStatus=" + mcp.getLifeStatus());
                }
                if (mcp.getStatus() != null && !"ACTIVE".equals(mcp.getStatus())) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "MCP服务未启用，不可绑定：id=" + resourceId + ", status=" + mcp.getStatus());
                }
                break;
            }
            default:
                // TOOL/SKILL 等类型当前直接放行（系统内置工具无需校验生命状态）
                log.debug("validateBindable: resourceType={} 跳过校验（非 KNOWLEDGE_BASE/MCP_SERVICE）", resourceType);
        }
    }

    /**
     * 移除资源绑定。
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeBinding(Long tenantId, Long agentId, Long bindingId) {
        AgentBinding b = agentBindingMapper.selectById(bindingId);
        if (b == null || !agentId.equals(b.getAgentId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "绑定不存在");
        }
        if (tenantId != null && !tenantId.equals(b.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该智能体");
        }
        agentBindingMapper.deleteById(bindingId);
        log.info("AgentBinding removed: agentId={}, bindingId={}", agentId, bindingId);
        notifyTemplateInvalidation(agentId, null, b.getTenantId());
    }

    /**
     * 查询资源绑定列表。
     */
    public List<AgentBinding> listBindings(Long tenantId, Long agentId) {
        requireAgent(agentId, tenantId);
        return agentBindingMapper.selectList(new LambdaQueryWrapper<AgentBinding>()
                .eq(AgentBinding::getAgentId, agentId));
    }

    // ============ 内部方法 ============

    private AgentDef requireAgent(Long agentId, Long tenantId) {
        AgentDef def = agentDefMapper.selectById(agentId);
        if (def == null) {
            log.warn("Agent not found: agentId={}, tenantId={}", agentId, tenantId);
            throw new BusinessException(ResultCode.NOT_FOUND, "智能体不存在: " + agentId);
        }
        if (tenantId != null && def.getTenantId() != null && !tenantId.equals(def.getTenantId())) {
            log.warn("Tenant mismatch: agentId={}, expectedTenant={}, actualTenant={}",
                    agentId, def.getTenantId());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该智能体");
        }
        return def;
    }
}
