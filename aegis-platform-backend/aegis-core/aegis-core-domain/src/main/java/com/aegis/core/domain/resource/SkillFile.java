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
 * 技能文件持久化实体。
 *
 * <p>每个技能在特定版本下由 3~5 个虚拟文件组成（SKILL.md / skill.json / README.md）。
 * 本实体将这些文件落库，支持：页面刷新不丢、版本对比、后续对接 MinIO 下载。</p>
 *
 * @author aegis
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("res_skill_file")
public class SkillFile implements Serializable {

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

    /** 快照所属版本，创建时为 0.0.1 */
    private String version;

    /** 相对路径，如 SKILL.md / skill.json */
    private String filePath;

    /** 文件名 */
    private String fileName;

    /** 文件类型：MARKDOWN / JSON / PYTHON / OTHER */
    private String fileType;

    /** 文件内容（UTF-8，mediumtext 可容纳数十 KB 的 SKILL.md） */
    private String content;

    /** SHA-256，用于变更检测和下载校验 */
    private String contentHash;

    /** 字节数 */
    private Integer size;

    /** 是否为入口文件（SKILL.md=1） */
    private Integer isEntry;

    /** 创建人ID */
    private Long createBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0-正常，1-已删除 */
    @TableLogic
    private Integer deleted;
}
