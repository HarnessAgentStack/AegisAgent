package com.aegis.admin.service.agent;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.dal.mapper.agent.AgentBindingMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
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
 * 绑定变更后通知 Runtime 失效模板缓存（行为与原 AgentPublishService 一致）。
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
     */
    @Transactional(rollbackFor = Exception.class)
    public void addBinding(AgentBinding binding) {
        AgentDef existing = requireAgent(binding.getAgentId(), binding.getTenantId());
        if (existing.getLifeStatus() == AgentLifeStatus.ARCHIVED) {
            throw new BusinessException(ResultCode.CONFLICT, "智能体已归档，不可新增绑定");
        }
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
                    agentId, tenantId, def.getTenantId());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该智能体");
        }
        return def;
    }
}
