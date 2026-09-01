package com.aegis.admin.service.resource;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.SkillVersion;
import com.aegis.core.dto.resource.SkillRollbackRequest;
import com.aegis.core.dto.resource.SkillVersionPublishRequest;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.SkillVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能版本管理领域服务。
 *
 * <p>负责技能版本快照创建、指针发布、版本回滚、灰度发布与语义化版本推导。
 * 技能版本采用指针式设计：{@code activeVersion} 为当前生效版本，
 * {@code canaryVersion} 为灰度版本指针，{@code latestVersion} 为最新版本。</p>
 *
 * @author wang.zhen
 * @see Skill
 * @see SkillVersion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillVersionService {

    private final SkillMapper skillMapper;
    private final SkillVersionMapper skillVersionMapper;

    /**
     * 版本指针发布（发布前创建快照）。
     *
     * <p>将技能的 activeVersion 指向目标版本，并将 lifeStatus 更新为 PUBLISHED。
     * 若目标版本对应的快照不存在，则先从当前技能内容生成快照，再发布。</p>
     *
     * @param tenantId 租户ID
     * @param req      版本发布请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long tenantId, SkillVersionPublishRequest req) {
        Skill skill = requireSkill(req.getSkillId(), tenantId);

        if (skill.getLifeStatus() != AgentLifeStatus.REVIEWING
                && skill.getLifeStatus() != AgentLifeStatus.PUBLISHED
                && skill.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "技能当前状态不可发布: " + skill.getLifeStatus());
        }

        String targetVersion = req.getTargetVersion();
        if (targetVersion == null || targetVersion.isEmpty()) {
            targetVersion = skill.getLatestVersion();
        }
        if (targetVersion == null || targetVersion.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "目标版本号不能为空");
        }

        // 发布前确保目标版本快照存在，不存在则从当前内容创建
        ensureVersionSnapshot(skill, targetVersion);

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<Skill> wrapper = new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skill.getId())
                .set(Skill::getActiveVersion, targetVersion)
                .set(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .set(Skill::getPublishedTime, now);

        if (Boolean.TRUE.equals(req.getGrayRelease())) {
            Integer percent = req.getGrayPercent() != null ? req.getGrayPercent() : 10;
            wrapper.set(Skill::getCanaryVersion, targetVersion);
            // 灰度比例持久化到 DB
            wrapper.set(Skill::getCanaryPercent, percent);
            log.info("Gray release set: skillId={}, version={}, percent={}",
                    skill.getId(), targetVersion, percent);
        } else {
            wrapper.set(Skill::getCanaryVersion, null);
            wrapper.set(Skill::getCanaryPercent, null);
        }

        skillMapper.update(null, wrapper);

        log.info("Skill version published: skillId={}, activeVersion={}, gray={}",
                skill.getId(), targetVersion, req.getGrayRelease());
    }

    /**
     * 版本回滚（激活历史版本）。
     *
     * <p>将 activeVersion 指针回滚到指定历史版本，canaryVersion 清空。
     * 回滚前校验目标版本快照必须存在于 res_skill_version 表中。</p>
     *
     * @param tenantId 租户ID
     * @param req      回滚请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long tenantId, SkillRollbackRequest req) {
        Skill skill = requireSkill(req.getSkillId(), tenantId);

        if (req.getTargetVersion() == null || req.getTargetVersion().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "回滚目标版本号不能为空");
        }

        String targetVersion = req.getTargetVersion();
        String currentActive = skill.getActiveVersion();
        if (targetVersion.equals(currentActive)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "目标版本与当前生效版本相同，无需回滚");
        }

        // 校验目标版本快照存在，防止回滚到不存在的版本
        long count = skillVersionMapper.selectCount(
                new LambdaQueryWrapper<SkillVersion>()
                        .eq(SkillVersion::getSkillId, skill.getId())
                        .eq(SkillVersion::getVersion, targetVersion));
        if (count == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    "目标版本不存在: " + targetVersion);
        }

        LambdaUpdateWrapper<Skill> wrapper = new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skill.getId())
                .set(Skill::getActiveVersion, targetVersion)
                .set(Skill::getCanaryVersion, null)
                // 回滚时同步清除灰度比例
                .set(Skill::getCanaryPercent, null)
                .set(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .set(Skill::getPublishedTime, LocalDateTime.now());

        skillMapper.update(null, wrapper);

        log.info("Skill rolled back: skillId={}, {} -> {}, reason={}",
                skill.getId(), currentActive, targetVersion, req.getReason());
    }

    /**
     * 灰度发布（逐步放量）。
     *
     * <p>将 canaryVersion 指向目标版本，设置灰度百分比。
     * 灰度版本不影响 activeVersion，仅用于 A/B 对比测试。
     * 灰度前校验目标版本快照存在。</p>
     *
     * @param tenantId 租户ID
     * @param skillId  技能ID
     * @param version  灰度版本号
     * @param percent  灰度百分比（1-100）
     */
    @Transactional(rollbackFor = Exception.class)
    public void grayRelease(Long tenantId, Long skillId, String version, Integer percent) {
        Skill skill = requireSkill(skillId, tenantId);

        if (percent == null || percent < 1 || percent > 100) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "灰度百分比必须在 1-100 之间");
        }
        if (version == null || version.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "灰度版本号不能为空");
        }

        // 校验目标版本快照存在
        long count = skillVersionMapper.selectCount(
                new LambdaQueryWrapper<SkillVersion>()
                        .eq(SkillVersion::getSkillId, skillId)
                        .eq(SkillVersion::getVersion, version));
        if (count == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    "目标版本不存在: " + version);
        }

        LambdaUpdateWrapper<Skill> wrapper = new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skillId)
                .set(Skill::getCanaryVersion, version)
                // 灰度比例持久化到 DB
                .set(Skill::getCanaryPercent, percent);

        skillMapper.update(null, wrapper);

        log.info("Gray release activated: skillId={}, canaryVersion={}, percent={}",
                skillId, version, percent);
    }

    /**
     * 语义化版本推导（自动递增 patch / minor / major）。
     *
     * @param currentVersion 当前版本号
     * @param bumpType       递增类型：PATCH / MINOR / MAJOR
     * @return 下一版本号
     */
    public String deriveNextVersion(String currentVersion, String bumpType) {
        if (currentVersion == null || currentVersion.isEmpty()) {
            return "1.0.0";
        }
        try {
            String[] parts = currentVersion.split("\\.");
            if (parts.length < 3) {
                return "1.0.0";
            }
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);

            if ("MAJOR".equalsIgnoreCase(bumpType)) {
                major++;
                minor = 0;
                patch = 0;
            } else if ("MINOR".equalsIgnoreCase(bumpType)) {
                minor++;
                patch = 0;
            } else {
                patch++;
            }
            return major + "." + minor + "." + patch;
        } catch (NumberFormatException e) {
            log.warn("Invalid version format: {}, fallback to 1.0.0", currentVersion);
            return "1.0.0";
        }
    }

    /**
     * 为技能推导下一版本并更新 latestVersion 字段。
     *
     * @param tenantId 租户ID
     * @param skillId  技能ID
     * @param bumpType 递增类型
     * @return 新版本号
     */
    @Transactional(rollbackFor = Exception.class)
    public String bumpAndSetNextVersion(Long tenantId, Long skillId, String bumpType) {
        Skill skill = requireSkill(skillId, tenantId);
        String current = skill.getLatestVersion() != null
                ? skill.getLatestVersion()
                : (skill.getVersion() != null ? skill.getVersion() : "0.0.1");
        String next = deriveNextVersion(current, bumpType);

        LambdaUpdateWrapper<Skill> wrapper = new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skillId)
                .set(Skill::getLatestVersion, next);
        skillMapper.update(null, wrapper);

        log.info("Skill version bumped: skillId={}, {} -> {}", skillId, current, next);
        return next;
    }

    /**
     * 查询版本历史列表（从 res_skill_version 真实查询）。
     *
     * <p>按版本号降序排列（最新版本在前），同时附带当前生效版本与灰度版本标记。
     * 最多返回 20 条历史记录。</p>
     *
     * @param tenantId 租户ID
     * @param skillId  技能ID
     * @return 版本历史列表
     */
    public List<Map<String, Object>> getVersionHistory(Long tenantId, Long skillId) {
        Skill skill = requireSkill(skillId, tenantId);

        List<SkillVersion> versions = skillVersionMapper.selectList(
                new LambdaQueryWrapper<SkillVersion>()
                        .eq(SkillVersion::getSkillId, skillId)
                        .orderByDesc(SkillVersion::getCreateTime)
                        .last("LIMIT 20"));

        List<Map<String, Object>> result = new ArrayList<>();
        String activeVersion = skill.getActiveVersion();
        String canaryVersion = skill.getCanaryVersion();

        for (SkillVersion v : versions) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", v.getId());
            item.put("version", v.getVersion());
            item.put("skillName", v.getSkillName());
            item.put("description", v.getDescription());
            item.put("category", v.getCategory());
            item.put("tags", v.getTags());
            item.put("securityLevel", v.getSecurityLevel());
            item.put("isActive", v.getVersion().equals(activeVersion));
            item.put("isCanary", v.getVersion().equals(canaryVersion));
            item.put("isSystem", v.getIsSystem());
            item.put("createBy", v.getCreateBy());
            item.put("createTime", v.getCreateTime());
            result.add(item);
        }

        // 如果没有任何快照（老数据），至少返回当前版本指针信息
        if (result.isEmpty() && activeVersion != null) {
            Map<String, Object> current = new HashMap<>();
            current.put("version", activeVersion);
            current.put("isActive", true);
            current.put("isCanary", false);
            current.put("skillName", skill.getSkillName());
            current.put("description", skill.getDescription());
            current.put("createTime", skill.getPublishedTime());
            current.put("isPointerOnly", true);
            result.add(current);
        }

        return result;
    }

    /**
     * 版本差异对比（两个版本的关键字段差异）。
     *
     * @param tenantId    租户ID
     * @param skillId     技能ID
     * @param versionA    版本A
     * @param versionB    版本B
     * @return 差异字段 Map
     */
    public Map<String, Object> getVersionDiff(Long tenantId, Long skillId,
                                               String versionA, String versionB) {
        requireSkill(skillId, tenantId);

        SkillVersion va = getSnapshotOrThrow(skillId, versionA);
        SkillVersion vb = getSnapshotOrThrow(skillId, versionB);

        Map<String, Object> diff = new HashMap<>();
        diff.put("versionA", versionA);
        diff.put("versionB", versionB);

        Map<String, Object> fields = new HashMap<>();
        fields.put("skillName", diffField(va.getSkillName(), vb.getSkillName()));
        fields.put("description", diffField(va.getDescription(), vb.getDescription()));
        fields.put("category", diffField(va.getCategory(), vb.getCategory()));
        fields.put("instructions", diffField(va.getInstructions(), vb.getInstructions()));
        fields.put("bindingTools", diffField(va.getBindingTools(), vb.getBindingTools()));
        fields.put("mappingConfig", diffField(va.getMappingConfig(), vb.getMappingConfig()));
        fields.put("execConfig", diffField(va.getExecConfig(), vb.getExecConfig()));
        fields.put("securityLevel", diffField(va.getSecurityLevel(), vb.getSecurityLevel()));
        fields.put("tags", diffField(va.getTags(), vb.getTags()));
        diff.put("fields", fields);

        return diff;
    }

    /**
     * 提交审核时创建版本快照（审核流程中自动生成快照）。
     *
     * <p>从当前技能内容生成新版本快照，版本号使用 latestVersion（若为空则推导为 1.0.0）。
     * 若该版本快照已存在则幂等跳过。</p>
     *
     * @param skill   技能实体
     * @param version 目标版本号
     * @return 创建的版本快照（或已存在的快照）
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillVersion createSnapshot(Skill skill, String version) {
        if (version == null || version.isEmpty()) {
            version = skill.getLatestVersion() != null
                    ? skill.getLatestVersion()
                    : (skill.getVersion() != null ? skill.getVersion() : "1.0.0");
        }
        return ensureVersionSnapshot(skill, version);
    }

    // ============ 内部方法 ============

    /**
     * 确保指定版本的快照存在，不存在则从当前技能内容创建。
     *
     * @return 版本快照实体
     */
    private SkillVersion ensureVersionSnapshot(Skill skill, String version) {
        SkillVersion existing = skillVersionMapper.selectOne(
                new LambdaQueryWrapper<SkillVersion>()
                        .eq(SkillVersion::getSkillId, skill.getId())
                        .eq(SkillVersion::getVersion, version)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        SkillVersion snapshot = SkillVersion.builder()
                .tenantId(skill.getTenantId())
                .skillId(skill.getId())
                .skillCode(skill.getSkillCode())
                .version(version)
                .skillName(skill.getSkillName())
                .description(skill.getDescription())
                .category(skill.getCategory() != null ? skill.getCategory().name() : null)
                .tags(skill.getTags())
                .securityLevel(skill.getSecurityLevel() != null ? skill.getSecurityLevel().name() : null)
                .instructions(skill.getInstructions())
                .referencesManifest(skill.getReferencesManifest())
                .triggerExamples(skill.getTriggerExamples())
                .bindingTools(skill.getBindingTools())
                .mappingConfig(skill.getMappingConfig())
                .execConfig(skill.getExecConfig())
                .isSystem(Boolean.TRUE.equals(skill.getIsSystem()) ? 1 : 0)
                .createBy(skill.getAuthorUserId())
                .createTime(LocalDateTime.now())
                .deleted(0)
                .build();
        skillVersionMapper.insert(snapshot);

        log.info("Skill version snapshot created: skillId={}, version={}",
                skill.getId(), version);
        return snapshot;
    }

    /** 获取版本快照，不存在则抛出 NOT_FOUND 异常 */
    private SkillVersion getSnapshotOrThrow(Long skillId, String version) {
        SkillVersion v = skillVersionMapper.selectOne(
                new LambdaQueryWrapper<SkillVersion>()
                        .eq(SkillVersion::getSkillId, skillId)
                        .eq(SkillVersion::getVersion, version)
                        .last("LIMIT 1"));
        if (v == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "版本不存在: " + version);
        }
        return v;
    }

    /** 比较两个字段值，返回差异信息 */
    private Map<String, Object> diffField(Object a, Object b) {
        Map<String, Object> result = new HashMap<>();
        boolean changed = (a == null && b != null) || (a != null && !a.equals(b));
        result.put("changed", changed);
        result.put("from", a);
        result.put("to", b);
        return result;
    }

    private Skill requireSkill(Long skillId, Long tenantId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "技能不存在: " + skillId);
        }
        if (tenantId != null && !tenantId.equals(skill.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该技能");
        }
        return skill;
    }
}
