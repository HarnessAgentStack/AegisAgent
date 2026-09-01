package com.aegis.core.dto.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 技能版本回滚请求。
 *
 * <p>将技能的当前生效版本指针回滚到指定历史版本。
 * 回滚操作不创建新版本，仅修改 activeVersion 指针。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillRollbackRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 技能ID */
    private Long skillId;

    /** 回滚目标版本号（必须为历史存在的版本） */
    private String targetVersion;

    /** 回滚原因，用于审计追溯 */
    private String reason;
}