package com.aegis.core.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import com.aegis.core.dto.chat.ChatRequest;

/**
 * 附件引用。
 *
 * <p>文件先通过 {@code POST /api/runtime/task/upload} 上传至服务端，
 * 获得 fileId 后以本结构作为 {@link ChatRequest#attachments} 的元素传入对话请求。
 *
 * <p>新增 {@code tenantId} / {@code userId} 字段记录归属，
 * 供 {@code download} 接口校验 IDOR（不安全直接对象引用），
 * 字段为 {@code transient}，仅服务端内部使用。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentRef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 服务端文件ID（上传后返回） */
    private String fileId;

    /** 原始文件名 */
    private String name;

    /** 文件大小（KB） */
    private Integer sizeKB;

    /** MIME 类型（如 application/pdf） */
    private String contentType;

    /** 临时存储路径（仅服务端内部使用） */
    private transient String storagePath;

    /** 上传者租户ID（归属校验，仅服务端内部使用） */
    private transient Long tenantId;

    /** 上传者用户ID（归属校验，仅服务端内部使用） */
    private transient Long userId;
}
