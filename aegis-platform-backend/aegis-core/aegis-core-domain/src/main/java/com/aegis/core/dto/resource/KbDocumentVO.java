package com.aegis.core.dto.resource;

import com.aegis.core.enums.resource.DocumentStatus;
import com.aegis.core.enums.security.PermissionLevel;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档视图对象。
 *
 * <p>所有 Long 类型 ID 字段通过 {@code @JsonSerialize(ToStringSerializer)} 序列化为字符串，
 * 防止前端 JavaScript Number 精度丢失。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbDocumentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档ID（雪花ID，序列化为字符串防止JS精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 租户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 所属知识库 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long kbId;

    /** 文件名 */
    private String fileName;

    /** 文件类型：PDF / DOCX / XLSX / TXT / MD 等 */
    private String fileType;

    /** 文件大小，单位字节 */
    private Long fileSize;

    /** OSS 存储键 */
    private String ossKey;

    /** 处理状态：PENDING / SCANNING / CHUNKING / CHUNKED / FAILED */
    private DocumentStatus status;

    /** 切片数量 */
    private Integer chunkCount;

    /** 权限级别：CREATOR / DEPT / ALL */
    private PermissionLevel permissionLevel;

    /** 安全扫描结果，JSON 字符串 */
    private String scanResult;

    /** 上传时间 */
    private LocalDateTime uploadedTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
