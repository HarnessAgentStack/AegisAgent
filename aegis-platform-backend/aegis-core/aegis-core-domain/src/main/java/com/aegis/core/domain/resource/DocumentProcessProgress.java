package com.aegis.core.domain.resource;

import com.aegis.core.base.TenantEntity;
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
 * 知识库文档处理进度实体。
 *
 * <p>记录文档处理流水线中每个步骤的执行状态，用于进度追踪。
 * 每次文档处理都会创建一系列进度记录，涵盖下载、扫描、切片、嵌入、向量入库等步骤。
 *
 * <h3>步骤流程</h3>
 * <ol>
 *   <li>DOWNLOADING - 从对象存储下载文档</li>
 *   <li>SCANNING - 安全扫描（文件类型白名单 + 敏感词检测）</li>
 *   <li>CHUNKING - 文本切片</li>
 *   <li>EMBEDDING - 文本嵌入为向量</li>
 *   <li>VECTORING - 向量入库</li>
 *   <li>COMPLETED - 全部完成</li>
 * </ol>
 *
 *  @author wang.zhen  
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("res_kb_process_progress")
public class DocumentProcessProgress extends TenantEntity {
    /** 所属知识库 ID */
    private Long kbId;
    /** 所属文档 ID，关联 res_kb_document.id */
    private Long docId;
    /** 处理步骤枚举 */
    private String step;
    /** 步骤顺序，从1开始 */
    private Integer stepOrder;
    /** 步骤状态：PENDING/RUNNING/COMPLETED/FAILED */
    private String status;
    /** 当前步骤进度百分比，0-100 */
    private Integer progressPercent;
    /** 步骤描述信息 */
    private String message;
    /** 步骤开始时间 */
    private LocalDateTime startedAt;
    /** 步骤完成时间 */
    private LocalDateTime completedAt;
    /** 错误详情，失败时记录 */
    private String errorDetail;
}
