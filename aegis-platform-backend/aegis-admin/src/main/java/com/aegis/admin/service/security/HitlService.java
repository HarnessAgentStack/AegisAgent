package com.aegis.admin.service.security;

import com.aegis.dal.mapper.security.HitlHistoryMapper;
import com.aegis.dal.mapper.security.HitlNodeMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.security.HitlHistory;
import com.aegis.core.domain.security.HitlNode;
import com.aegis.core.dto.security.HitlHistoryVO;
import com.aegis.core.dto.security.HitlNodeCreateRequest;
import com.aegis.core.dto.security.HitlNodeUpdateRequest;
import com.aegis.core.dto.security.HitlNodeVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
import com.aegis.admin.web.agent.AgentAdminController;

/**
 * HITL 管理领域服务。
 *
 * <p>提供人工介入（Human-In-The-Loop）节点管理、审批工单处理与审批历史查询。
 * 支撑平台高风险操作的人工审批流程：节点配置、工单审批/驳回、审批追溯。
 *
 * <p>同时提供智能体维度的 HITL 节点 CRUD 能力，供 {@code AgentAdminController} 复用。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlService {

    private final HitlNodeMapper hitlNodeMapper;
    private final HitlHistoryMapper hitlHistoryMapper;

    /** 策略变更事件发布器（HITL 节点变更实时刷新缓存） */
    @org.springframework.beans.factory.annotation.Autowired
    private com.aegis.core.dto.security.SecurityConfigPublisher securityConfigPublisher;

    // ==================== HITL 节点（管理平面） ====================

    /**
     * 分页查询 HITL 节点。
     */
    public Page<HitlNodeVO> listNodes(Long agentId, Boolean enabled, int page, int size) {
        Page<HitlNode> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<HitlNode> wrapper = new LambdaQueryWrapper<HitlNode>()
                .eq(agentId != null, HitlNode::getAgentId, agentId)
                .eq(enabled != null, HitlNode::getEnabled, enabled)
                .orderByDesc(HitlNode::getId);
        Page<HitlNode> entityPage = hitlNodeMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toHitlNodeVO, page, size);
    }

    /**
     * 创建 HITL 节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public HitlNodeVO createNode(HitlNodeCreateRequest req, Long tenantId) {
        HitlNode entity = new HitlNode();
        BeanUtils.copyProperties(req, entity);
        if (tenantId != null) {
            entity.setTenantId(tenantId);
        }
        hitlNodeMapper.insert(entity);
        publishHitlNodeChanged(tenantId, entity.getId(), "CREATE");
        return toHitlNodeVO(entity);
    }

    /**
     * 更新 HITL 节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateNode(Long id, HitlNodeUpdateRequest req, Long tenantId) {
        HitlNode existing = hitlNodeMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "HITL节点不存在");
        }
        HitlNode entity = new HitlNode();
        BeanUtils.copyProperties(req, entity);
        entity.setId(id);
        if (entity.getTenantId() == null) {
            entity.setTenantId(tenantId);
        }
        hitlNodeMapper.updateById(entity);
        publishHitlNodeChanged(tenantId, id, "UPDATE");
    }

    /**
     * 删除 HITL 节点（补租户校验）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(Long id, Long tenantId) {
        HitlNode existing = hitlNodeMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "HITL节点不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该HITL节点");
        }
        hitlNodeMapper.deleteById(id);
        publishHitlNodeChanged(tenantId, id, "DELETE");
    }

    // ==================== HITL 节点（智能体维度） ====================

    /**
     * 查询智能体的 HITL 节点列表。
     */
    public List<HitlNode> listNodesByAgent(Long agentId) {
        return hitlNodeMapper.selectList(new LambdaQueryWrapper<HitlNode>()
                .eq(HitlNode::getAgentId, agentId)
                .orderByDesc(HitlNode::getId));
    }

    /**
     * 创建智能体的 HITL 节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public HitlNode createNodeForAgent(Long agentId, HitlNode entity, Long tenantId) {
        if (entity.getNodeName() == null || entity.getNodeName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "HITL节点名称不能为空");
        }
        entity.setAgentId(agentId);
        if (entity.getTenantId() == null && tenantId != null) {
            entity.setTenantId(tenantId);
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        try {
            hitlNodeMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "该智能体下已存在同名HITL节点");
        }
        log.info("创建智能体 HITL 节点: agentId={}, nodeId={}", agentId, entity.getId());
        publishHitlNodeChanged(tenantId, entity.getId(), "CREATE");
        return entity;
    }

    /**
     * 更新智能体的 HITL 节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateNodeForAgent(Long agentId, Long nodeId, HitlNode entity, Long tenantId) {
        HitlNode existing = hitlNodeMapper.selectById(nodeId);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "HITL节点不存在: " + nodeId);
        }
        if (!existing.getAgentId().equals(agentId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "HITL节点不属于该智能体");
        }
        entity.setId(nodeId);
        if (entity.getTenantId() == null && tenantId != null) {
            entity.setTenantId(tenantId);
        }
        hitlNodeMapper.updateById(entity);
        log.info("更新智能体 HITL 节点: agentId={}, nodeId={}", agentId, nodeId);
        publishHitlNodeChanged(tenantId, nodeId, "UPDATE");
    }

    /**
     * 删除智能体的 HITL 节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteNodeForAgent(Long agentId, Long nodeId) {
        HitlNode existing = hitlNodeMapper.selectById(nodeId);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "HITL节点不存在: " + nodeId);
        }
        if (!existing.getAgentId().equals(agentId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "HITL节点不属于该智能体");
        }
        hitlNodeMapper.deleteById(nodeId);
        log.info("删除智能体 HITL 节点: agentId={}, nodeId={}", agentId, nodeId);
        publishHitlNodeChanged(null, nodeId, "DELETE");
    }

    /**
     * 切换 HITL 节点启停状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void toggleNodeForAgent(Long agentId, Long nodeId) {
        HitlNode existing = hitlNodeMapper.selectById(nodeId);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "HITL节点不存在: " + nodeId);
        }
        if (!existing.getAgentId().equals(agentId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "HITL节点不属于该智能体");
        }
        existing.setEnabled(existing.getEnabled() == null || !existing.getEnabled());
        hitlNodeMapper.updateById(existing);
        log.info("切换智能体 HITL 节点状态: agentId={}, nodeId={}, enabled={}", agentId, nodeId, existing.getEnabled());
        publishHitlNodeChanged(existing.getTenantId(), nodeId, "UPDATE");
    }

    // ==================== 审批历史 ====================

    /**
     * 分页查询审批历史。
     */
    public Page<HitlHistoryVO> listHistory(Long nodeId, Long agentId, String action, int page, int size) {
        Page<HitlHistory> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<HitlHistory> wrapper = new LambdaQueryWrapper<HitlHistory>()
                .eq(nodeId != null, HitlHistory::getNodeId, nodeId)
                .eq(agentId != null, HitlHistory::getAgentId, agentId)
                .eq(action != null && !action.isEmpty(), HitlHistory::getAction, action)
                .orderByDesc(HitlHistory::getOccurTime);
        Page<HitlHistory> entityPage = hitlHistoryMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toHitlHistoryVO, page, size);
    }

    // ============ Entity -> VO 转换 ============

    private HitlNodeVO toHitlNodeVO(HitlNode entity) {
        HitlNodeVO vo = new HitlNodeVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private HitlHistoryVO toHitlHistoryVO(HitlHistory entity) {
        HitlHistoryVO vo = new HitlHistoryVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    // ============ 通用分页转换 ============

    private <E, V> Page<V> convertPage(Page<E> entityPage, java.util.function.Function<E, V> converter, int page, int size) {
        Page<V> voPage = new Page<>(page, size);
        voPage.setRecords(entityPage.getRecords().stream().map(converter).collect(Collectors.toList()));
        voPage.setTotal(entityPage.getTotal());
        return voPage;
    }

    /**
     * 发布 HITL 节点变更事件，驱动集群内缓存秒级刷新。
     *
     * @param tenantId   租户 ID
     * @param nodeId     HITL 节点 ID
     * @param operation  操作类型（CREATE / UPDATE / DELETE）
     */
    private void publishHitlNodeChanged(Long tenantId, Long nodeId, String operation) {
        if (securityConfigPublisher == null) {
            log.debug("SecurityConfigPublisher 未注入，跳过 HITL 事件发布: nodeId={}, op={}", nodeId, operation);
            return;
        }
        try {
            securityConfigPublisher.publishPolicyChangedEvent(
                    tenantId != null ? tenantId : 0L,
                    "HITL",
                    nodeId,
                    operation);
            log.info("v4.0 HITL 节点变更事件已发布: tenantId={}, nodeId={}, op={}",
                    tenantId, nodeId, operation);
        } catch (Exception e) {
            log.error("v4.0 HITL 节点变更事件发布失败: nodeId={}, op={}", nodeId, operation, e);
        }
    }

    /**
     * 强制刷新指定 Agent 的 HITL 规则缓存。
     *
     * @param agentId   智能体 ID
     * @param tenantId  租户 ID
     */
    public void forceReloadHitlRules(Long agentId, Long tenantId) {
        if (securityConfigPublisher == null) {
            log.warn("SecurityConfigPublisher 未注入，无法 forceReload: agentId={}", agentId);
            return;
        }
        try {
            securityConfigPublisher.publishPolicyChangedEvent(
                    tenantId != null ? tenantId : 0L,
                    "HITL",
                    agentId,
                    "RELOAD");
            log.info("v4.0 HITL 规则强制刷新已发布: agentId={}, tenantId={}", agentId, tenantId);
        } catch (Exception e) {
            log.error("v4.0 HITL 规则强制刷新失败: agentId={}", agentId, e);
            throw new com.aegis.core.common.error.BusinessException(
                    com.aegis.core.common.web.ResultCode.INTERNAL_ERROR, "强制刷新失败，请稍后重试");
        }
    }
}
