package com.aegis.core.domain.resource;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.resource.DocumentStatus;
import com.aegis.core.enums.security.PermissionLevel;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 知识库文档实体
 *
 * <p>知识库文档（KbDocument）记录知识库中每个原始文件的元信息与处理状态，
 * 是文档上传、切片、向量化、安全扫描与权限管控的最小管理单元。</p>
 *
 * <h3>处理流程</h3>
 * <ul>
 *     <li>上传：文件落 OSS，记录 fileName / fileType / fileSize / ossKey</li>
 *     <li>切片：根据知识库 chunkStrategy 切分，结果写入 chunkCount</li>
 *     <li>向量化：切片向量写入向量库，status 标记处理状态</li>
 *     <li>安全扫描：扫描敏感内容，结果记录于 scanResult</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，文档随知识库一起隔离；权限级别 permissionLevel
 * 控制文档在知识库内的可见性。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see KnowledgeBase
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("res_kb_document")
public class KbDocument extends TenantEntity {
    /** 所属知识库 ID，关联 knowledge_base.id */
    private Long kbId;
    /** 文件名，包含扩展名，长度不超过 255 */
    private String fileName;
    /** 文件类型，取值：PDF / DOCX / XLSX / PPTX / TXT / MD / HTML 等 */
    private String fileType;
    /** 文件大小，单位字节 */
    private Long fileSize;
    /** OSS 存储键，文件在对象存储中的唯一路径 */
    private String ossKey;
    /** 处理状态：{@link DocumentStatus#PENDING}（待扫描）、{@link DocumentStatus#SCANNING}（扫描中）、{@link DocumentStatus#CHUNKING}（切片中）、{@link DocumentStatus#CHUNKED}（已切片）、{@link DocumentStatus#FAILED}（失败） */
    private DocumentStatus status;
    /** 切片数量，文档切分后的总片段数 */
    private Integer chunkCount;
    /** 权限级别：{@link PermissionLevel#CREATOR}（仅创建者）、{@link PermissionLevel#DEPT}（同部门）、{@link PermissionLevel#ALL}（全员可查看），控制文档检索可见性 */
    private PermissionLevel permissionLevel;
    /** 安全扫描结果，JSON 字符串，记录敏感词、合规性等扫描结论 */
    private String scanResult;
    /** 上传时间，文档首次上传完成时写入 */
    private LocalDateTime uploadedTime;
}