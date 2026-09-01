package com.aegis.admin.service.sandbox;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.sandbox.SandboxBaseImage;
import com.aegis.core.enums.sandbox.SandboxRegistryType;
import com.aegis.dal.mapper.sandbox.SandboxBaseImageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 基础镜像领域服务。
 *
 * <p>管理 Docker Image 注册信息，支持平台公共镜像（tenant_id=0）与租户私有镜像。
 * 镜像引用（registry/repository:tag）由 {@code ImageRegistryRouter} 解析。
 *
 * @author wang.zhen
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxBaseImageService {

    private final SandboxBaseImageMapper baseImageMapper;

    /**
     * 分页查询基础镜像（含系统公共镜像 + 当前租户私有镜像）。
     */
    public Page<SandboxBaseImage> page(long current, long size, String imageCode, String imageName, String status) {
        Long tenantId = TenantContextHolder.getTenantId();
        LambdaQueryWrapper<SandboxBaseImage> wrapper = new LambdaQueryWrapper<SandboxBaseImage>()
                .and(w -> w.eq(SandboxBaseImage::getTenantId, 0L)   // 系统公共
                           .or()
                           .eq(SandboxBaseImage::getTenantId, tenantId == null ? 0L : tenantId))
                .orderByDesc(SandboxBaseImage::getCreateTime);
        if (StringUtils.hasText(imageCode)) {
            wrapper.like(SandboxBaseImage::getImageCode, imageCode);
        }
        if (StringUtils.hasText(imageName)) {
            wrapper.like(SandboxBaseImage::getImageName, imageName);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SandboxBaseImage::getStatus, status);
        }
        return baseImageMapper.selectPage(new Page<>(current, size), wrapper);
    }

    /**
     * 列出所有启用的镜像（含系统公共 + 当前租户私有）。
     */
    public List<SandboxBaseImage> listEnabled() {
        Long tenantId = TenantContextHolder.getTenantId();
        return baseImageMapper.selectList(new LambdaQueryWrapper<SandboxBaseImage>()
                .and(w -> w.eq(SandboxBaseImage::getTenantId, 0L)
                           .or()
                           .eq(SandboxBaseImage::getTenantId, tenantId == null ? 0L : tenantId))
                .eq(SandboxBaseImage::getStatus, "ENABLED")
                .orderByAsc(SandboxBaseImage::getImageName));
    }

    /**
     * 按 ID 查询。
     */
    public SandboxBaseImage getById(Long id) {
        return baseImageMapper.selectById(id);
    }

    /**
     * 新建镜像（租户私有，tenant_id > 0；tenant_id 为空时默认 0）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SandboxBaseImage create(SandboxBaseImage image) {
        Long tenantId = TenantContextHolder.getTenantId();
        image.setTenantId(tenantId == null ? 0L : tenantId);
        if (image.getStatus() == null) {
            image.setStatus("ENABLED");
        }
        if (image.getRegistryType() == null) {
            image.setRegistryType(SandboxRegistryType.DOCKER_HUB);
        }
        // 校验唯一性（tenant_id + image_code + tag）
        Long count = baseImageMapper.selectCount(new LambdaQueryWrapper<SandboxBaseImage>()
                .eq(SandboxBaseImage::getTenantId, image.getTenantId())
                .eq(SandboxBaseImage::getImageCode, image.getImageCode())
                .eq(SandboxBaseImage::getTag, image.getTag()));
        if (count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "镜像编码+标签已存在");
        }
        baseImageMapper.insert(image);
        log.info("[SandboxImage] 新建镜像成功: tenant={}, code={}, tag={}", image.getTenantId(), image.getImageCode(), image.getTag());
        return image;
    }

    /**
     * 更新镜像。
     */
    @Transactional(rollbackFor = Exception.class)
    public SandboxBaseImage update(SandboxBaseImage image) {
        SandboxBaseImage existing = baseImageMapper.selectById(image.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "镜像不存在");
        }
        // 系统公共镜像（tenant_id=0）仅平台管理员可改，租户用户仅能改自己的
        Long currentTenantId = TenantContextHolder.getTenantId();
        if (existing.getTenantId() == 0L && (currentTenantId == null || currentTenantId > 0L)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权修改系统公共镜像");
        }
        image.setTenantId(existing.getTenantId()); // 不允许改归属
        baseImageMapper.updateById(image);
        log.info("[SandboxImage] 更新镜像成功: id={}, code={}", image.getId(), image.getImageCode());
        return image;
    }

    /**
     * 启用/停用镜像。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status) {
        SandboxBaseImage existing = baseImageMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "镜像不存在");
        }
        SandboxBaseImage update = new SandboxBaseImage();
        update.setId(id);
        update.setStatus(status);
        baseImageMapper.updateById(update);
        log.info("[SandboxImage] 镜像状态变更: id={}, status={}", id, status);
    }

    /**
     * 删除镜像（逻辑删除）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SandboxBaseImage existing = baseImageMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "镜像不存在");
        }
        Long currentTenantId = TenantContextHolder.getTenantId();
        if (existing.getTenantId() == 0L && (currentTenantId == null || currentTenantId > 0L)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权删除系统公共镜像");
        }
        baseImageMapper.deleteById(id);
        log.info("[SandboxImage] 删除镜像成功: id={}", id);
    }
}