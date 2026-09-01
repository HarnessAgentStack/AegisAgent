package com.aegis.core.spi;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.aegis.core.domain.resource.KbDocument;

/**
 * 对象存储协议。
 *
 * <p>抽象平台文件存储的统一协议，屏蔽底层实现差异（MinIO / S3 / OSS / COS）。
 * 支持上传/下载/预签名 URL，按租户前缀隔离对象命名空间，承载知识库文档、
 * 智能体头像、会话附件等二进制资源。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>租户隔离：对象键以 tenant/{id}/ 为前缀，按租户划分存储命名空间</li>
 *   <li>预签名 URL：上传/下载生成临时签名 URL，客户端直传直下，减轻平台带宽压力</li>
 *   <li>大文件分片：实现可支持分片上传，协议层透明</li>
 *   <li>生命周期：可配置过期自动清理，配合逻辑删除兜底</li>
 * </ul>
 *
 * <p>本协议为同步契约，保持 aegis-core 不引入响应式框架。
 *
 * @author wang.zhen
 * @see com.aegis.core.domain.resource.KbDocument
 */
public interface IObjectStorage {

    /**
     * 上传对象。
     *
     * @param tenantId    租户ID
     * @param objectKey   对象键（不含租户前缀，由实现拼接）
     * @param inputStream 对象内容流
     * @param contentType MIME 类型
     * @return 对象完整键（含租户前缀）
     */
    String upload(@Valid @NotNull Long tenantId, @Valid @NotBlank String objectKey, @Valid InputStream inputStream, @Valid String contentType);

    /**
     * 下载对象为流。
     *
     * @param tenantId  租户ID
     * @param objectKey 对象键
     * @return 对象内容流，调用方负责关闭
     */
    InputStream download(@Valid @NotNull Long tenantId, @Valid @NotBlank String objectKey);

    /**
     * 生成预签名下载 URL。
     *
     * @param tenantId  租户ID
     * @param objectKey 对象键
     * @param expire    有效时长
     * @param unit      时长单位
     * @return 预签名 URL
     */
    String presignedDownloadUrl(@Valid @NotNull Long tenantId, @Valid @NotBlank String objectKey, @Valid long expire, @Valid TimeUnit unit);

    /**
     * 生成预签名上传 URL（客户端直传）。
     *
     * @param tenantId  租户ID
     * @param objectKey 对象键
     * @param contentType MIME 类型
     * @param expire    有效时长
     * @param unit      时长单位
     * @return 预签名 URL
     */
    String presignedUploadUrl(@Valid @NotNull Long tenantId, @Valid @NotBlank String objectKey, @Valid String contentType, @Valid long expire, @Valid TimeUnit unit);

    /**
     * 删除对象。
     *
     * @param tenantId  租户ID
     * @param objectKey 对象键
     * @return true 表示删除成功
     */
    boolean delete(@Valid @NotNull Long tenantId, @Valid @NotBlank String objectKey);
}
