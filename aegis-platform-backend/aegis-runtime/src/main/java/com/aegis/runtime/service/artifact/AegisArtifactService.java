package com.aegis.runtime.service.artifact;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.session.AegisArtifact;
import com.aegis.dal.mapper.artifact.AegisArtifactMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产物服务。
 *
 * <p>管理会话产物的 CRUD 操作，供 {@link ArtifactPreviewService}、
 * {@link com.aegis.runtime.web.ArtifactPreviewController} 等上游模块调用。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AegisArtifactService {

    private final AegisArtifactMapper artifactMapper;

    /**
     * 创建产物记录。
     *
     * @param artifact 产物实体
     * @return 已持久化的产物实体
     */
    public AegisArtifact createArtifact(AegisArtifact artifact) {
        if (artifact == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产物不能为空");
        }
        if (!StringUtils.hasText(artifact.getArtifactId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产物业务ID不能为空");
        }
        if (artifact.getVersion() == null) {
            artifact.setVersion(1);
        }
        if (artifact.getCreatedAt() == null) {
            artifact.setCreatedAt(LocalDateTime.now());
        }
        if (artifact.getArchived() == null) {
            artifact.setArchived(false);
        }
        artifactMapper.insert(artifact);
        log.info("产物已创建: artifactId={}, sessionId={}, type={}",
                artifact.getArtifactId(), artifact.getSessionId(), artifact.getType());
        return artifact;
    }

    /**
     * 根据会话ID列出所有产物。
     *
     * @param sessionId 会话ID
     * @return 产物列表
     */
    public List<AegisArtifact> listBySession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "会话ID不能为空");
        }
        LambdaQueryWrapper<AegisArtifact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AegisArtifact::getSessionId, sessionId)
                .orderByDesc(AegisArtifact::getCreatedAt);
        return artifactMapper.selectList(wrapper);
    }

    /**
     * 根据产物业务ID查询产物。
     *
     * @param artifactId 产物业务ID
     * @return 产物实体，不存在返回 null
     */
    public AegisArtifact findByArtifactId(String artifactId) {
        if (!StringUtils.hasText(artifactId)) {
            return null;
        }
        LambdaQueryWrapper<AegisArtifact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AegisArtifact::getArtifactId, artifactId);
        return artifactMapper.selectOne(wrapper);
    }

    /**
     * 根据产物业务ID获取产物（不存在则抛异常）。
     *
     * @param artifactId 产物业务ID
     * @return 产物实体
     */
    public AegisArtifact getById(String artifactId) {
        AegisArtifact artifact = findByArtifactId(artifactId);
        if (artifact == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "产物不存在: " + artifactId);
        }
        return artifact;
    }

    /**
     * 根据产物业务ID删除产物。
     *
     * @param artifactId 产物业务ID
     */
    public void deleteById(String artifactId) {
        if (!StringUtils.hasText(artifactId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产物业务ID不能为空");
        }
        LambdaQueryWrapper<AegisArtifact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AegisArtifact::getArtifactId, artifactId);
        int deleted = artifactMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("产物已删除: artifactId={}", artifactId);
        }
    }
}