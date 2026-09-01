package com.aegis.core.dto.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 技能版本发布请求。
 *
 * <p>用于指针式发布：直接将当前版本标记为最新版本，无需创建新版本。
 * 适用于灰度发布转正、紧急修复等场景。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVersionPublishRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 技能ID */
    private Long skillId;

    /** 目标版本号（语义化版本，如 1.0.0），为空则使用当前 latestVersion */
    private String targetVersion;

    /** 是否为灰度发布 */
    private Boolean grayRelease;

    /** 灰度百分比（0-100），仅当 grayRelease=true 时有效 */
    private Integer grayPercent;

    /** 发布说明 / 变更日志 */
    private String releaseNotes;
}