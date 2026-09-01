package com.aegis.core.domain.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话产物实体。
 *
 * <p>产物（Artifact）是会话中智能体生成或处理的文件类资源，包括文档、图片、代码、
 * 链接等。产物由工具调用产生，存储在 MinIO 对象存储中，通过 storageRef 关联。</p>
 *
 * <h3>核心场景</h3>
 * <ul>
 *   <li>文档生成：智能体生成 docx/pdf/excel/ppt 等文档，作为会话产物供用户下载</li>
 *   <li>代码产物：代码执行结果（.java/.py/.sql 等）持久化为产物</li>
 *   <li>图片产物：图片生成工具的输出</li>
 *   <li>链接产物：外部资源的 URL 引用</li>
 * </ul>
 *
 * <h3>版本管理</h3>
 * <p>产物支持版本迭代，version 字段标记版本号，parentArtifactId 指向父版本，
 * 形成版本链，支持产物的修改历史追溯。</p>
 *
 * @author wang.zhen
 * @see Session
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sess_artifact")
public class AegisArtifact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String artifactId;

    private String sessionId;

    private Long agentId;

    private int msgSeq;

    private Long tenantId;

    private Long userId;

    private String name;

    private String type;

    private String storageRef;

    private String previewMeta;

    private Long size;

    private Integer version;

    private String parentArtifactId;

    private Boolean archived;

    private LocalDateTime expireAt;

    private LocalDateTime createdAt;
}