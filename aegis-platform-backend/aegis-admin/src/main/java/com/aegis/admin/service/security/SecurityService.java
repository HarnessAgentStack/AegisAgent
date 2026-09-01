package com.aegis.admin.service.security;

import com.aegis.dal.mapper.security.MaskRuleMapper;
import com.aegis.dal.mapper.security.OutboundPolicyMapper;
import com.aegis.dal.mapper.security.SensitiveWordMapper;
import com.aegis.dal.mapper.security.ToolPolicyMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.security.MaskRule;
import com.aegis.core.domain.security.OutboundPolicy;
import com.aegis.core.domain.security.SensitiveWord;
import com.aegis.core.domain.security.ToolPolicy;
import com.aegis.core.dto.security.MaskRuleCreateRequest;
import com.aegis.core.dto.security.MaskRuleUpdateRequest;
import com.aegis.core.dto.security.MaskRuleVO;
import com.aegis.core.dto.security.OutboundPolicyCreateRequest;
import com.aegis.core.dto.security.OutboundPolicyUpdateRequest;
import com.aegis.core.dto.security.OutboundPolicyVO;
import com.aegis.core.dto.security.SensitiveWordCreateRequest;
import com.aegis.core.dto.security.SensitiveWordUpdateRequest;
import com.aegis.core.dto.security.SensitiveWordVO;
import com.aegis.core.dto.security.ToolPolicyCreateRequest;
import com.aegis.core.dto.security.ToolPolicyUpdateRequest;
import com.aegis.core.dto.security.ToolPolicyVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

/**
 * 安全管理领域服务。
 *
 * <p>提供安全策略、敏感词、脱敏规则、出站策略的管理能力。
 * 支撑平台安全运营：策略配置、敏感词维护、脱敏规则配置、出站网络管控。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityService {

    private final ToolPolicyMapper toolPolicyMapper;
    private final SensitiveWordMapper sensitiveWordMapper;
    private final MaskRuleMapper maskRuleMapper;
    private final OutboundPolicyMapper outboundPolicyMapper;

    /** 策略变更事件发布器 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.aegis.core.dto.security.SecurityConfigPublisher securityConfigPublisher;

    // ==================== 工具策略 ====================

    public Page<ToolPolicyVO> listToolPolicies(String toolType, Integer securityLevel, Boolean enabled,
                                             Long tenantId, int page, int size) {
        Page<ToolPolicy> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<ToolPolicy> wrapper = new LambdaQueryWrapper<ToolPolicy>()
                .eq(tenantId != null, ToolPolicy::getTenantId, tenantId)
                .eq(toolType != null && !toolType.isEmpty(), ToolPolicy::getToolType, toolType)
                .eq(securityLevel != null, ToolPolicy::getSecurityLevel, securityLevel)
                .eq(enabled != null, ToolPolicy::getEnabled, enabled)
                .orderByDesc(ToolPolicy::getId);
        Page<ToolPolicy> entityPage = toolPolicyMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toToolPolicyVO, page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public ToolPolicyVO createToolPolicy(ToolPolicyCreateRequest req, Long tenantId) {
        if (req.getToolType() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "工具类型不能为空");
        }
        if (req.getSecurityLevel() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "安全等级不能为空");
        }
        ToolPolicy entity = new ToolPolicy();
        BeanUtils.copyProperties(req, entity);
        if (tenantId != null) {
            entity.setTenantId(tenantId);
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        try {
            toolPolicyMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "该工具类型和安全等级的策略已存在");
        }
        publishPolicyChanged(tenantId, "TOOL", entity.getId(), "CREATE");
        return toToolPolicyVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateToolPolicy(Long id, ToolPolicyUpdateRequest req, Long tenantId) {
        ToolPolicy existing = toolPolicyMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工具策略不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该工具策略");
        }
        ToolPolicy entity = new ToolPolicy();
        BeanUtils.copyProperties(req, entity);
        entity.setId(id);
        if (entity.getTenantId() == null) {
            entity.setTenantId(tenantId);
        }
        toolPolicyMapper.updateById(entity);
        publishPolicyChanged(tenantId, "TOOL", id, "UPDATE");
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteToolPolicy(Long id, Long tenantId) {
        ToolPolicy existing = toolPolicyMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工具策略不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该工具策略");
        }
        toolPolicyMapper.deleteById(id);
        publishPolicyChanged(tenantId, "TOOL", id, "DELETE");
    }

    // ==================== 敏感词 ====================

    public Page<SensitiveWordVO> listSensitiveWords(String category, String matchMode, String action,
                                                  Boolean enabled, Long tenantId, int page, int size) {
        Page<SensitiveWord> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<SensitiveWord> wrapper = new LambdaQueryWrapper<SensitiveWord>()
                .eq(tenantId != null, SensitiveWord::getTenantId, tenantId)
                .eq(category != null && !category.isEmpty(), SensitiveWord::getCategory, category)
                .eq(matchMode != null && !matchMode.isEmpty(), SensitiveWord::getMatchMode, matchMode)
                .eq(action != null && !action.isEmpty(), SensitiveWord::getAction, action)
                .eq(enabled != null, SensitiveWord::getEnabled, enabled)
                .orderByDesc(SensitiveWord::getId);
        Page<SensitiveWord> entityPage = sensitiveWordMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toSensitiveWordVO, page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveWordVO createSensitiveWord(SensitiveWordCreateRequest req, Long tenantId) {
        if (req.getWord() == null || req.getWord().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "敏感词不能为空");
        }
        SensitiveWord entity = new SensitiveWord();
        BeanUtils.copyProperties(req, entity);
        if (tenantId != null) {
            entity.setTenantId(tenantId);
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        try {
            sensitiveWordMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "该敏感词已存在");
        }
        publishPolicyChanged(tenantId, "SENSITIVE_WORD", entity.getId(), "CREATE");
        return toSensitiveWordVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateSensitiveWord(Long id, SensitiveWordUpdateRequest req, Long tenantId) {
        SensitiveWord existing = sensitiveWordMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "敏感词不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该敏感词");
        }
        SensitiveWord entity = new SensitiveWord();
        BeanUtils.copyProperties(req, entity);
        entity.setId(id);
        if (entity.getTenantId() == null) {
            entity.setTenantId(tenantId);
        }
        sensitiveWordMapper.updateById(entity);
        publishPolicyChanged(tenantId, "SENSITIVE_WORD", id, "UPDATE");
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSensitiveWord(Long id, Long tenantId) {
        SensitiveWord existing = sensitiveWordMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "敏感词不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该敏感词");
        }
        sensitiveWordMapper.deleteById(id);
        publishPolicyChanged(tenantId, "SENSITIVE_WORD", id, "DELETE");
    }

    // ==================== 脱敏规则 ====================

    public Page<MaskRuleVO> listMaskRules(String dataType, String maskWay, Boolean enabled,
                                        Long tenantId, int page, int size) {
        Page<MaskRule> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<MaskRule> wrapper = new LambdaQueryWrapper<MaskRule>()
                .eq(tenantId != null, MaskRule::getTenantId, tenantId)
                .eq(dataType != null && !dataType.isEmpty(), MaskRule::getDataType, dataType)
                .eq(maskWay != null && !maskWay.isEmpty(), MaskRule::getMaskWay, maskWay)
                .eq(enabled != null, MaskRule::getEnabled, enabled)
                .orderByDesc(MaskRule::getId);
        Page<MaskRule> entityPage = maskRuleMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toMaskRuleVO, page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public MaskRuleVO createMaskRule(MaskRuleCreateRequest req, Long tenantId) {
        if (req.getDataType() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "数据类型不能为空");
        }
        MaskRule entity = new MaskRule();
        BeanUtils.copyProperties(req, entity);
        if (tenantId != null) {
            entity.setTenantId(tenantId);
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        try {
            maskRuleMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "该数据类型+脱敏方式的规则已存在");
        }
        publishPolicyChanged(tenantId, "MASK_RULE", entity.getId(), "CREATE");
        return toMaskRuleVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateMaskRule(Long id, MaskRuleUpdateRequest req, Long tenantId) {
        MaskRule existing = maskRuleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "脱敏规则不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该脱敏规则");
        }
        MaskRule entity = new MaskRule();
        BeanUtils.copyProperties(req, entity);
        entity.setId(id);
        if (entity.getTenantId() == null) {
            entity.setTenantId(tenantId);
        }
        maskRuleMapper.updateById(entity);
        publishPolicyChanged(tenantId, "MASK_RULE", id, "UPDATE");
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMaskRule(Long id, Long tenantId) {
        MaskRule existing = maskRuleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "脱敏规则不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该脱敏规则");
        }
        maskRuleMapper.deleteById(id);
        publishPolicyChanged(tenantId, "MASK_RULE", id, "DELETE");
    }

    // ==================== 出站策略 ====================

    public Page<OutboundPolicyVO> listOutboundPolicies(String policyType, String applicableScope,
                                                     Boolean enabled, Long tenantId, int page, int size) {
        Page<OutboundPolicy> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<OutboundPolicy> wrapper = new LambdaQueryWrapper<OutboundPolicy>()
                .eq(tenantId != null, OutboundPolicy::getTenantId, tenantId)
                .eq(policyType != null && !policyType.isEmpty(), OutboundPolicy::getPolicyType, policyType)
                .eq(applicableScope != null && !applicableScope.isEmpty(), OutboundPolicy::getApplicableScope, applicableScope)
                .eq(enabled != null, OutboundPolicy::getEnabled, enabled)
                .orderByDesc(OutboundPolicy::getId);
        Page<OutboundPolicy> entityPage = outboundPolicyMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toOutboundPolicyVO, page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public OutboundPolicyVO createOutboundPolicy(OutboundPolicyCreateRequest req, Long tenantId) {
        OutboundPolicy entity = new OutboundPolicy();
        BeanUtils.copyProperties(req, entity);
        if (tenantId != null) {
            entity.setTenantId(tenantId);
        }
        outboundPolicyMapper.insert(entity);
        publishPolicyChanged(tenantId, "OUTBOUND", entity.getId(), "CREATE");
        return toOutboundPolicyVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateOutboundPolicy(Long id, OutboundPolicyUpdateRequest req, Long tenantId) {
        OutboundPolicy existing = outboundPolicyMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "出站策略不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该出站策略");
        }
        OutboundPolicy entity = new OutboundPolicy();
        BeanUtils.copyProperties(req, entity);
        entity.setId(id);
        if (entity.getTenantId() == null) {
            entity.setTenantId(tenantId);
        }
        outboundPolicyMapper.updateById(entity);
        publishPolicyChanged(tenantId, "OUTBOUND", id, "UPDATE");
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteOutboundPolicy(Long id, Long tenantId) {
        OutboundPolicy existing = outboundPolicyMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "出站策略不存在");
        }
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该出站策略");
        }
        outboundPolicyMapper.deleteById(id);
        publishPolicyChanged(tenantId, "OUTBOUND", id, "DELETE");
    }

    // ============ Entity -> VO 转换 ============

    private ToolPolicyVO toToolPolicyVO(ToolPolicy entity) {
        ToolPolicyVO vo = new ToolPolicyVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private SensitiveWordVO toSensitiveWordVO(SensitiveWord entity) {
        SensitiveWordVO vo = new SensitiveWordVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private MaskRuleVO toMaskRuleVO(MaskRule entity) {
        MaskRuleVO vo = new MaskRuleVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private OutboundPolicyVO toOutboundPolicyVO(OutboundPolicy entity) {
        OutboundPolicyVO vo = new OutboundPolicyVO();
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
     * 统一发布策略变更事件（触发集群内缓存失效）。
     *
     * <p>所有安全策略的 Create / Update / Delete 写操作完成后调用，
     * 使 {@link com.aegis.runtime.service.SecurityConfigPublisher} 向 Redis 发布
     * 策略变更频道，驱动 {@link com.aegis.runtime.service.SecurityPolicyCacheInvalidator}
     * 在全集群节点同步刷新本地缓存，实现 5s 内策略生效。
     *
     * @param tenantId    租户 ID（null 表示全局）
     * @param policyType  策略类型（TOOL / SENSITIVE_WORD / MASK_RULE / OUTBOUND）
     * @param policyId    策略实体 ID
     * @param operation   操作类型（CREATE / UPDATE / DELETE）
     */
    private void publishPolicyChanged(Long tenantId, String policyType, Long policyId, String operation) {
        if (securityConfigPublisher == null) {
            log.debug("SecurityConfigPublisher 未注入，跳过事件发布: policyType={}, policyId={}, op={}",
                    policyType, policyId, operation);
            return;
        }
        try {
            securityConfigPublisher.publishPolicyChangedEvent(
                    tenantId != null ? tenantId : 0L,
                    policyType,
                    policyId,
                    operation);
            log.info("v4.0 策略变更事件已发布: tenantId={}, type={}, id={}, op={}",
                    tenantId, policyType, policyId, operation);
        } catch (Exception e) {
            // 事件发布失败不影响主流程
            log.error("v4.0 策略变更事件发布失败: tenantId={}, type={}, id={}, op={}",
                    tenantId, policyType, policyId, operation, e);
        }
    }
}
