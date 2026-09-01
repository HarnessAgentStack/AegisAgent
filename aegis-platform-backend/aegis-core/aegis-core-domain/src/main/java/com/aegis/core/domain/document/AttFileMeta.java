package com.aegis.core.domain.document;

import com.aegis.core.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 附件文件元数据实体。
 *
 * <p>持久化 MinIO 存储的附件元信息，作为内存索引的替代方案，支持应用重启后恢复附件归属。
 * 文件字节存于 MinIO，本实体仅记录元数据与归属信息。
 *
 * <h3>存储模型</h3>
 * <ul>
 *   <li>主键 fileId：UUID（不含连字符），由 {@code FileStorageService.store} 生成</li>
 *   <li>storageKey：MinIO objectKey，格式 {@code attachments/{tenantId}/{userId}/{fileId}{ext}}</li>
 *   <li>归属：tenantId（TenantEntity 继承） + userId（本实体显式字段）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("att_file_meta")
public class AttFileMeta extends TenantEntity {

    /**
     * 遮蔽父类 BaseEntity.id：AttFileMeta 使用 String fileId 作为主键，
     * 父类 Long id 在 att_file_meta 表中不存在，故标记为不存在。
     */
    @TableField(exist = false)
    private Long id;

    /** 文件ID（UUID，不含连字符），主键 */
    @TableId(type = IdType.INPUT)
    private String fileId;

    /** 原始文件名 */
    private String filename;

    /** 文件扩展名（含点，如 .pdf） */
    private String ext;

    /** 文件大小（字节） */
    private Long sizeBytes;

    /** MIME 类型 */
    private String contentType;

    /** MinIO object key */
    private String storageKey;

    /** MIME 是否验证通过 1=是 0=否 */
    private Integer mimeVerified;

    /** 上传者用户ID（归属校验用，与 BaseEntity.createBy 冗余） */
    private Long userId;
}
