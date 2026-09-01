package com.aegis.admin.service.tenant;

import com.aegis.dal.mapper.org.RoleMapper;
import com.aegis.dal.mapper.tenant.TenantMapper;
import com.aegis.dal.mapper.tenant.TenantQuotaMapper;
import com.aegis.dal.mapper.tenant.TenantUsageMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.org.Role;
import com.aegis.core.domain.tenant.Tenant;
import com.aegis.core.domain.tenant.TenantQuota;
import com.aegis.core.domain.tenant.TenantUsage;
import com.aegis.core.dto.tenant.TenantCreateRequest;
import com.aegis.core.dto.tenant.TenantQuotaUpdateRequest;
import com.aegis.core.dto.tenant.TenantUpdateRequest;
import com.aegis.core.dto.tenant.TenantUsageVO;
import com.aegis.core.dto.tenant.TenantVO;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.role.RoleCode;
import com.aegis.core.enums.tenant.RoleType;
import com.aegis.core.enums.tenant.TenantStatus;
import com.aegis.core.enums.tenant.TenantType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 租户管理领域服务。
 *
 * 编排租户 CRUD、配额管理与预置角色初始化。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>停用隔离：租户冻结后拒绝新会话，存量会话优雅结束</li>
 *   <li>权限：创建/配额/计费平台管理员；用量查询租户管理员可查本租户</li>
 * </ul>
 *
 * @author wang.zhen
 * @see Tenant
 * @see TenantQuota
 * @see TenantUsage
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantManageService {

    private final TenantMapper tenantMapper;
    private final TenantQuotaMapper tenantQuotaMapper;
    private final TenantUsageMapper tenantUsageMapper;
    private final RoleMapper roleMapper;

    /**
     * 创建租户并初始化默认配额与预置平台角色。
     *
     * <p>在同一事务内完成：
     * <ol>
     *   <li>校验 tenantCode 全局唯一</li>
     *   <li>插入 Tenant（默认状态 NORMAL）</li>
     *   <li>插入 TenantQuota（按 tenantType 分档默认配额）</li>
     *   <li>初始化7个预置平台角色</li>
     * </ol>
     *
     * @param req 创建请求
     * @return 租户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(TenantCreateRequest req) {
        if (req.getTenantCode() == null || req.getTenantCode().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户编码不能为空");
        }
        if (req.getTenantName() == null || req.getTenantName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户名称不能为空");
        }
        // 编码全局唯一
        Long exists = tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantCode, req.getTenantCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "租户编码已存在: " + req.getTenantCode());
        }

        TenantType tenantType = req.getTenantType() != null ? req.getTenantType() : TenantType.DIVISION;

        // 1. 插入租户
        Tenant tenant = Tenant.builder()
                .tenantCode(req.getTenantCode())
                .tenantName(req.getTenantName())
                .tenantType(tenantType)
                .status(TenantStatus.NORMAL)
                .contactName(req.getContactName())
                .contactPhone(req.getContactPhone())
                .expireTime(req.getExpireTime())
                .remark(req.getRemark())
                .build();
        tenantMapper.insert(tenant);
        log.info("Tenant created: id={}, code={}", tenant.getId(), tenant.getTenantCode());

        // 2. 插入默认配额（按租户类型分档）
        TenantQuota quota = buildDefaultQuota(tenant.getId(), tenantType);
        tenantQuotaMapper.insert(quota);
        log.info("TenantQuota initialized: tenantId={}, maxAgents={}, maxTokenPerDay={}",
                tenant.getId(), quota.getMaxAgents(), quota.getMaxTokenPerDay());

        // 3. 初始化7个预置平台角色
        initPresetPlatformRoles(tenant.getId());

        return tenant.getId();
    }

    /**
     * 更新租户信息（不允许修改 tenantCode）。
     *
     * @param id  租户ID
     * @param req 租户更新请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, TenantUpdateRequest req) {
        Tenant existing = requireTenant(id);
        Tenant tenant = new Tenant();
        BeanUtils.copyProperties(req, tenant);
        tenant.setId(id);
        // 不允许修改编码
        tenant.setTenantCode(existing.getTenantCode());
        tenantMapper.updateById(tenant);
        log.info("Tenant updated: id={}", id);
    }

    /**
     * 更新租户配额。
     *
     * @param tenantId 租户ID
     * @param req      配额更新请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuota(Long tenantId, TenantQuotaUpdateRequest req) {
        requireTenant(tenantId);
        TenantQuota existing = getQuota(tenantId);
        TenantQuota quota = new TenantQuota();
        BeanUtils.copyProperties(req, quota);
        if (existing == null) {
            // 不存在则新建
            quota.setTenantId(tenantId);
            tenantQuotaMapper.insert(quota);
        } else {
            quota.setId(existing.getId());
            quota.setTenantId(tenantId);
            tenantQuotaMapper.updateById(quota);
        }
        log.info("TenantQuota updated: tenantId={}", tenantId);
    }

    /**
     * 冻结租户（status → FROZEN）。
     *
     * @param tenantId 租户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void freeze(Long tenantId) {
        requireTenant(tenantId);
        tenantMapper.update(null, new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, tenantId)
                .set(Tenant::getStatus, TenantStatus.FROZEN));
        log.info("Tenant frozen: id={}", tenantId);
    }

    /**
     * 解冻租户（status → NORMAL）。
     *
     * @param tenantId 租户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void unfreeze(Long tenantId) {
        requireTenant(tenantId);
        tenantMapper.update(null, new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, tenantId)
                .set(Tenant::getStatus, TenantStatus.NORMAL));
        log.info("Tenant unfrozen: id={}", tenantId);
    }

    /**
     * 查询租户配额。
     *
     * @param tenantId 租户ID
     * @return 配额记录，不存在返回 null
     */
    public TenantQuota getQuota(Long tenantId) {
        return tenantQuotaMapper.selectOne(new LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId)
                .last("LIMIT 1"));
    }

    /**
     * 查询租户最新用量（按统计日期倒序取最新一条）。
     *
     * @param tenantId 租户ID
     * @return 最新用量视图对象，不存在返回 null
     */
    public TenantUsageVO getUsage(Long tenantId) {
        TenantUsage usage = tenantUsageMapper.selectOne(new LambdaQueryWrapper<TenantUsage>()
                .eq(TenantUsage::getTenantId, tenantId)
                .orderByDesc(TenantUsage::getStatDate)
                .last("LIMIT 1"));
        if (usage == null) {
            return null;
        }
        TenantUsageVO vo = new TenantUsageVO();
        BeanUtils.copyProperties(usage, vo);
        return vo;
    }

    /**
     * 分页查询租户。
     *
     * @param keyword 关键词（可选，匹配名称/编码）
     * @param status  状态过滤（可选，NORMAL/FROZEN）
     * @param page    页码
     * @param size    每页条数
     * @return 分页结果
     */
    public Page<TenantVO> page(String keyword, String status, int page, int size) {
        Page<Tenant> pageObj = new Page<>(page, size);
        TenantStatus statusEnum = parseTenantStatus(status);
        LambdaQueryWrapper<Tenant> wrapper = new LambdaQueryWrapper<Tenant>()
                .eq(statusEnum != null, Tenant::getStatus, statusEnum)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(Tenant::getTenantName, keyword)
                        .or().like(Tenant::getTenantCode, keyword))
                .orderByDesc(Tenant::getCreateTime);
        Page<Tenant> entityPage = tenantMapper.selectPage(pageObj, wrapper);
        Page<TenantVO> voPage = new Page<>(page, size);
        voPage.setRecords(entityPage.getRecords().stream().map(this::toTenantVO).collect(Collectors.toList()));
        voPage.setTotal(entityPage.getTotal());
        return voPage;
    }

    /**
     * 查询租户详情。
     *
     * @param id 租户ID
     * @return 租户视图对象
     */
    public TenantVO detail(Long id) {
        return toTenantVO(requireTenant(id));
    }

    // ============ 内部方法 ============

    private TenantVO toTenantVO(Tenant entity) {
        TenantVO vo = new TenantVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private Tenant requireTenant(Long id) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "租户不存在: " + id);
        }
        return tenant;
    }

    /**
     * 按租户类型构建默认配额档位。
     * <ul>
     *   <li>HQ（集团总部）：最高配额</li>
     *   <li>SUBSIDIARY（子公司）：中等配额</li>
     *   <li>DIVISION（事业部）：基础配额</li>
     * </ul>
     */
    private TenantQuota buildDefaultQuota(Long tenantId, TenantType tenantType) {
        return switch (tenantType) {
            case HQ -> TenantQuota.builder()
                    .tenantId(tenantId)
                    .maxAgents(200)
                    .maxResources(500)
                    .maxConcurrentSessions(500)
                    .maxTokenPerDay(50_000_000L)
                    .maxTokenPerMonth(1_000_000_000L)
                    .maxSandboxes(50)
                    .maxStorageGb(1000)
                    .build();
            case SUBSIDIARY -> TenantQuota.builder()
                    .tenantId(tenantId)
                    .maxAgents(100)
                    .maxResources(200)
                    .maxConcurrentSessions(200)
                    .maxTokenPerDay(20_000_000L)
                    .maxTokenPerMonth(400_000_000L)
                    .maxSandboxes(20)
                    .maxStorageGb(500)
                    .build();
            case DIVISION -> TenantQuota.builder()
                    .tenantId(tenantId)
                    .maxAgents(50)
                    .maxResources(100)
                    .maxConcurrentSessions(100)
                    .maxTokenPerDay(5_000_000L)
                    .maxTokenPerMonth(100_000_000L)
                    .maxSandboxes(10)
                    .maxStorageGb(200)
                    .build();
        };
    }

    /**
     * 初始化 7 个预置角色（与 org_role 种子数据保持一致）。
     * <p>角色编码统一使用 {@link RoleCode} 常量引用，
     * 不再创建 PLATFORM_ADMIN / TENANT_ADMIN 等兼容别名——
     * 别名由 {@code JwtAuthenticationToken.buildAuthorities()} 在 Spring Security 层自动注入。
     */
    private void initPresetPlatformRoles(Long tenantId) {
        List<Role> presets = new ArrayList<>();
        presets.add(buildPresetRole(tenantId, RoleCode.SUPER_ADMIN,      "超级管理员",   "系统级最高权限，可管理所有租户与全局配置", 1));
        presets.add(buildPresetRole(tenantId, RoleCode.ENTERPRISE_ADMIN, "企业/租户管理员", "租户内最高权限，管理本租户用户/部门/角色/资源", 2));
        presets.add(buildPresetRole(tenantId, RoleCode.SECURITY_ADMIN,   "安全管理员",    "安全策略与审计管理，配置脱敏/外发/敏感词", 3));
        presets.add(buildPresetRole(tenantId, RoleCode.RESOURCE_ADMIN,   "资源管理员",    "SKILL/MCP/KB/TOOL 审核发布", 4));
        presets.add(buildPresetRole(tenantId, RoleCode.AGENT_REVIEWER,   "智能体审核员",  "智能体发布审核", 5));
        presets.add(buildPresetRole(tenantId, RoleCode.AGENT_CREATOR,    "智能体创建者",  "创建/编辑/发布智能体", 6));
        presets.add(buildPresetRole(tenantId, RoleCode.EMPLOYEE,         "普通员工",      "使用通用智能体", 7));

        for (Role role : presets) {
            roleMapper.insert(role);
        }
        log.info("Preset platform roles initialized: tenantId={}, count={}", tenantId, presets.size());
    }

    private Role buildPresetRole(Long tenantId, String code, String name, String desc, int sort) {
        Role role = Role.builder()
                .roleCode(code)
                .roleName(name)
                .roleType(RoleType.PLATFORM)
                .description(desc)
                .sort(sort)
                .status(CommonStatus.NORMAL)
                .build();
        role.setTenantId(tenantId);
        return role;
    }

    /** 解析租户状态字符串为枚举，空值或非法值返回 null */
    private TenantStatus parseTenantStatus(String status) {
        if (status == null || status.isEmpty()) return null;
        try {
            return TenantStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid TenantStatus value: {}, ignore filter", status);
            return null;
        }
    }
}
