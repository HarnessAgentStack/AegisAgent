package com.aegis.admin.service.agent;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.dal.mapper.agent.AgentConfigMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 智能体版本历史与 Diff 服务（从 {@link AgentPublishService} 拆出，职责单一）。
 *
 * <p>负责：查询版本历史、比较两个版本配置差异。
 *
 * @author wang.zhen
 * @see AgentConfig
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentVersionService {

    private final AgentDefMapper agentDefMapper;
    private final AgentConfigMapper agentConfigMapper;

    /**
     * 查询智能体版本历史（所有版本配置列表，按版本降序）。
     */
    public List<AgentConfig> getVersionHistory(Long tenantId, Long agentId) {
        requireAgent(agentId, tenantId);
        return agentConfigMapper.selectList(new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getAgentId, agentId)
                .orderByDesc(AgentConfig::getVersion));
    }

    // ============ 版本 Diff ============

    /**
     * 比较两个版本配置差异。
     */
    public List<Map<String, Object>> versionDiff(Long agentId, String v1, String v2) {
        AgentConfig c1 = agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getAgentId, agentId)
                .eq(AgentConfig::getVersion, v1));
        AgentConfig c2 = agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getAgentId, agentId)
                .eq(AgentConfig::getVersion, v2));
        if (c1 == null || c2 == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "版本配置不存在");
        }
        List<Map<String, Object>> diffs = new ArrayList<>();
        addDiff(diffs, "systemPrompt", c1.getSystemPrompt(), c2.getSystemPrompt());
        addDiff(diffs, "modelTier", c1.getModelTier() != null ? c1.getModelTier().name() : null,
                c2.getModelTier() != null ? c2.getModelTier().name() : null);
        addDiff(diffs, "temperature", c1.getTemperature(), c2.getTemperature());
        addDiff(diffs, "maxTurns", c1.getMaxTurns(), c2.getMaxTurns());
        addDiff(diffs, "enabledTools", c1.getEnabledTools(), c2.getEnabledTools());
        return diffs;
    }

    private void addDiff(List<Map<String, Object>> diffs, String field, Object oldVal, Object newVal) {
        if (!Objects.equals(oldVal, newVal)) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("field", field);
            diff.put("oldValue", oldVal);
            diff.put("newValue", newVal);
            diffs.add(diff);
        }
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
