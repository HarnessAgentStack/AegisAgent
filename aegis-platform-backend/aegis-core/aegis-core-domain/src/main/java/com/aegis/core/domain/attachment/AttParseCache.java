package com.aegis.core.domain.attachment;

import com.aegis.core.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 附件解析缓存实体
 *
 * <p>缓存文件解析结果，避免重复解析相同文件。
 * 缓存键：fileId + parseVersion + contentHash（文件内容哈希，内容变更时自动失效）。</p>
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
@TableName("att_parse_cache")
public class AttParseCache extends BaseEntity {
    /** 文件 ID（关联 AttachmentRef.fileId） */
    private String fileId;
    /** 解析引擎版本（解析逻辑变更时递增） */
    private String parseVersion;
    /** 文件内容 SHA-256 哈希（文件内容变更时自动失效缓存） */
    private String contentHash;
    /** 文件 MIME 类型 */
    private String contentType;
    /** 解析后的文本内容 */
    private String parsedText;
    /** 解析元数据（JSON 格式：页数/sheet数/图片数等） */
    private String parsedMetadata;
    /** 解析文本字符数 */
    private Integer charCount;
    /** 估算 token 数（字符数/4） */
    private Integer tokenEstimate;
}
