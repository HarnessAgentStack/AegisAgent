package com.aegis.core.domain.resource;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 技能打包产物元数据实体。
 *
 * <p>每次对技能执行 PACKAGE 或 SUBMIT 时（后者自动打包），
 * 压缩包上传 MinIO 后在此留下元数据，便于审计、下载、清理。</p>
 *
 * @author aegis
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("res_skill_package")
public class SkillPackage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户ID */
    private Long tenantId;

    /** 关联 res_skill.id */
    private Long skillId;

    /** 技能编码（冗余） */
    private String skillCode;

    /** 打包对应的技能版本 */
    private String skillVersion;

    /** 文件名，如 skill_skill_1788513417113_v0.0.1.zip */
    private String packageName;

    /** 对象存储完整键 */
    private String storedKey;

    /** 压缩包大小（字节） */
    private Integer storageSize;

    /** SHA-256，下载时校验 */
    private String contentHash;

    /** 触发源：USER_ACTION / AUTO_SUBMIT / AUTO_PUBLISH */
    private String triggerSource;

    /** 打包时的 debug_report + security_report 快照 */
    private String buildReport;

    /** 创建人ID */
    private Long createBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 逻辑删除：0-正常，1-已删除 */
    @TableLogic
    private Integer deleted;
}
