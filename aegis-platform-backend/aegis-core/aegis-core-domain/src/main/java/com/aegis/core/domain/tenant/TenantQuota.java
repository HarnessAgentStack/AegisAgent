package com.aegis.core.domain.tenant;

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
 * 租户配额配置实体。
 *
 * <p>定义租户在六大维度（智能体/资源/会话/Token/沙箱/存储）的配额上限，
 * 是配额校验的基准。继承 BaseEntity（平台级配置，非租户隔离）。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>每个租户一条配额记录，1:1关联Tenant</li>
 *   <li>配额值为硬上限，实时用量（TenantUsage）超限即熔断</li>
 *   <li>Token配额区分日/月双周期，取严约束</li>
 * </ul>
 *
 * @author wang.zhen
 * @see Tenant
 * @see TenantUsage
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ten_quota")
public class TenantQuota extends BaseEntity {

    /** 租户ID，关联Tenant主键，1:1关系 */
    private Long tenantId;

    /** 智能体数量上限，含通用与应用智能体 */
    private Integer maxAgents;

    /** 资源数量上限，含技能/知识库/MCP客户端等 */
    private Integer maxResources;

    /** 最大并发会话数，超限新会话排队或拒绝 */
    private Integer maxConcurrentSessions;

    /** 每日Token上限，自然日0点重置，超限熔断 */
    private Long maxTokenPerDay;

    /** 每月Token上限，自然月1号重置，与日配额取严约束 */
    private Long maxTokenPerMonth;

    /** 沙箱实例数上限，含占用与空闲实例 */
    private Integer maxSandboxes;

    /** 存储容量上限（GB），含知识库文档/会话历史/文件附件 */
    private Integer maxStorageGb;
}