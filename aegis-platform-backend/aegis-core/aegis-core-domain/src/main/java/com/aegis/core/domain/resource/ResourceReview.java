package com.aegis.core.domain.resource;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.common.ReviewStatus;
import com.baomidou.mybatisplus.annotation.TableField;
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
 * 资源审核实体
 *
 * <p>资源审核（ResourceReview）记录技能、知识库等资源发布到资源中心前的安全审核流程，
 * 包括安全扫描、依赖检查与人工复核，确保发布资源符合平台安全规范。</p>
 *
 * <h3>审核流程</h3>
 * <ul>
 *     <li>提交：申请人提交资源，记录 applicantUserId 与 submitTime</li>
 *     <li>扫描：系统自动执行安全扫描与依赖检查，结果写入 scanResult 与 depCheckResult</li>
 *     <li>复核：审核员人工复核，记录 reviewerUserId、reviewTime 与 rejectReason</li>
 *     <li>结论：reviewStatus 标记最终结论（通过/驳回/待审）</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，审核记录带 tenantId 隔离；
 * 审核员通常为租户管理员或安全员，仅可见本租户审核单。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("res_review")
public class ResourceReview extends TenantEntity {
    /** 资源类型：{@link ResourceType}，标识被审核资源种类 */
    private ResourceType resourceType;
    /** 资源 ID，关联对应资源表的主键 */
    private Long resourceId;
    /** 资源名称，冗余存储便于审核列表展示 */
    private String resourceName;
    /** 资源版本号，标识被审核的版本 */
    private String resourceVersion;
    /** 申请人用户 ID，关联 user.id */
    private Long applicantUserId;
    /** 申请人部门 ID，关联 department.id */
    private Long applicantDeptId;
    /** 安全等级，1-4 对应 L1-L4，资源声明的安全等级，影响审核严格度 */
    private Integer securityLevel;
    /** 安全扫描结果，JSON 字符串，记录敏感词、漏洞、合规性等自动扫描结论 */
    private String scanResult;
    /** 依赖检查结果，JSON 字符串，记录工具依赖、数据依赖等检查结论 */
    private String depCheckResult;
    /** 审核状态：{@link ReviewStatus#PENDING}（待审核）、{@link ReviewStatus#APPROVED}（已通过）、{@link ReviewStatus#REJECTED}（已拒绝） */
    private ReviewStatus reviewStatus;
    /** 审核员用户 ID，关联 user.id，执行人工复核的审核员 */
    private Long reviewerUserId;
    /** 审核时间，审核员作出结论的时间 */
    private LocalDateTime reviewTime;
    /** 驳回原因，当 reviewStatus 为 REJECTED 时填写，长度不超过 512 */
    private String rejectReason;
    /** 提交时间，申请人提交审核的时间 */
    private LocalDateTime submitTime;

    /** 申请人展示名（realName 优先，回退 username），查询时批量填充，非库字段 */
    @TableField(exist = false)
    private String applicantName;
}