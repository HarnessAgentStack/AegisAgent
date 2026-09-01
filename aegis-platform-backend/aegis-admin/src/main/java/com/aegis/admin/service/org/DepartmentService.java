package com.aegis.admin.service.org;

import com.aegis.dal.mapper.org.DepartmentMapper;
import com.aegis.dal.mapper.org.UserMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.org.Department;
import com.aegis.core.domain.org.User;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.common.SyncSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import com.aegis.core.base.TenantEntity;

/**
 * 部门管理领域服务。
 *
 * <p>编排租户内树形组织架构的创建、更新、删除与查询。
 * 部门通过 parentId 构建层级，deptPath 记录完整路径便于祖先/后代查询。
 * 继承 TenantEntity，按租户隔离，所有查询强制带 tenantId 条件。
 *
 * @author wang.zhen
 * @see Department
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;

    /**
     * 查询租户内全部部门（扁平列表）。
     *
     * @param tenantId 租户ID
     * @return 部门列表，按 sort 升序、createTime 升序
     */
    public List<Department> tree(Long tenantId) {
        return departmentMapper.selectList(new LambdaQueryWrapper<Department>()
                .eq(Department::getTenantId, tenantId)
                .orderByAsc(Department::getSort)
                .orderByAsc(Department::getCreateTime));
    }

    /**
     * 创建部门，自动计算 deptPath 与 deptLevel。
     *
     * <p>deptPath 格式 /root/parent/self/，deptLevel 根部门为1，子部门递增。
     * parentId 为0或null时作为根部门。
     *
     * @param dept 部门实体（tenantId、deptName 必填）
     * @return 部门ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Department dept) {
        if (dept.getTenantId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (dept.getDeptName() == null || dept.getDeptName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "部门名称不能为空");
        }

        Long parentId = dept.getParentId();
        if (parentId == null || parentId <= 0) {
            // 根部门
            dept.setParentId(0L);
            dept.setDeptLevel(1);
            dept.setStatus(CommonStatus.NORMAL);
            if (dept.getSyncSource() == null) {
                dept.setSyncSource(SyncSource.MANUAL);
            }
            if (dept.getSort() == null) {
                dept.setSort(0);
            }
            departmentMapper.insert(dept);
            // 回填 deptPath：/ + id + /
            dept.setDeptPath("/" + dept.getId() + "/");
            departmentMapper.updateById(dept);
        } else {
            // 子部门：校验父部门存在且同租户
            Department parent = departmentMapper.selectById(parentId);
            if (parent == null || !dept.getTenantId().equals(parent.getTenantId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "父部门不存在: " + parentId);
            }
            if (parent.getDeptLevel() != null && parent.getDeptLevel() >= 5) {
                throw new BusinessException(ResultCode.CONFLICT, "部门层级超过5级上限");
            }
            dept.setDeptLevel(parent.getDeptLevel() + 1);
            dept.setStatus(CommonStatus.NORMAL);
            if (dept.getSyncSource() == null) {
                dept.setSyncSource(SyncSource.MANUAL);
            }
            if (dept.getSort() == null) {
                dept.setSort(0);
            }
            departmentMapper.insert(dept);
            // deptPath = 父path + id + /
            dept.setDeptPath(parent.getDeptPath() + dept.getId() + "/");
            departmentMapper.updateById(dept);
        }
        log.info("Department created: id={}, tenantId={}, parentId={}",
                dept.getId(), dept.getTenantId(), dept.getParentId());
        return dept.getId();
    }

    /**
     * 更新部门信息（不允许修改 deptPath/deptLevel/parentId）。
     *
     * @param dept 部门实体（id、tenantId 必填）
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Department dept) {
        Department existing = requireDepartment(dept.getId(), dept.getTenantId());
        // 不允许修改层级相关字段
        dept.setParentId(existing.getParentId());
        dept.setDeptPath(existing.getDeptPath());
        dept.setDeptLevel(existing.getDeptLevel());
        departmentMapper.updateById(dept);
        log.info("Department updated: id={}", dept.getId());
    }

    /**
     * 删除部门（校验无子部门、无关联用户）。
     *
     * @param tenantId 租户ID
     * @param id       部门ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long tenantId, Long id) {
        requireDepartment(id, tenantId);
        // 校验无子部门
        Long childCount = departmentMapper.selectCount(new LambdaQueryWrapper<Department>()
                .eq(Department::getTenantId, tenantId)
                .eq(Department::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "存在子部门，无法删除");
        }
        // 校验无关联用户
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getDeptId, id));
        if (userCount != null && userCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "部门下存在关联用户，无法删除");
        }
        departmentMapper.deleteById(id);
        log.info("Department deleted: id={}, tenantId={}", id, tenantId);
    }

    // ============ 内部方法 ============

    private Department requireDepartment(Long id, Long tenantId) {
        Department dept = departmentMapper.selectById(id);
        if (dept == null || !tenantId.equals(dept.getTenantId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在: " + id);
        }
        return dept;
    }
}
