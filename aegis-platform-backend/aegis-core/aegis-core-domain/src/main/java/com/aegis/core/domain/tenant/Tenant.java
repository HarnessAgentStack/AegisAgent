package com.aegis.core.domain.tenant;

import com.aegis.core.base.BaseEntity;
import com.aegis.core.enums.tenant.TenantStatus;
import com.aegis.core.enums.tenant.TenantType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import com.aegis.core.base.TenantEntity;

/**
 * 租户实体，平台多租户隔离的根聚合。
 *
 * <p>对应平台多租户体系的根聚合根，承载租户的基础属性、联系人、有效期与状态。
 * 继承 BaseEntity（无 tenantId，因为 Tenant 本身即租户主体）。
 * 所有租户隔离实体通过 tenantId 关联本实体实现数据隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>租户编码（tenantCode）全局唯一，创建后不可修改</li>
 *   <li>租户状态决定其下所有资源可用性：0-停用 1-正常</li>
 *   <li>过期租户（expireTime 早于当前时间）自动停用，拒绝新建会话</li>
 * </ul>
 *
 * <h3>关联实体</h3>
 * <ul>
 *   <li>{@link TenantQuota} - 租户配额配置</li>
 *   <li>{@link TenantUsage} - 租户用量统计</li>
 *   <li>{@link com.aegis.core.base.TenantEntity} - 租户隔离实体基类</li>
 * </ul>
 *
 * @author wang.zhen
 * @see TenantQuota
 * @see TenantUsage
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ten_tenant")
public class Tenant extends BaseEntity {

    /** 租户编码，全局唯一，业务可读标识，用于URL与配置引用，创建后不可修改 */
    private String tenantCode;

    /** 租户名称，展示用，可重复，支持修改 */
    private String tenantName;

    /** 租户类型：{@link TenantType#HQ}（集团总部）、{@link TenantType#SUBSIDIARY}（子公司）、{@link TenantType#DIVISION}（事业部），决定默认配额档位 */
    private TenantType tenantType;

    /** 租户状态：{@link TenantStatus#NORMAL}（正常）、{@link TenantStatus#FROZEN}（冻结），冻结后拒绝新建会话与资源操作 */
    private TenantStatus status;

    /** 租户联系人姓名，用于运营对接 */
    private String contactName;

    /** 租户联系人电话，用于运营对接与紧急通知 */
    private String contactPhone;

    /** 租户有效期截止时间，过期自动停用，null 表示长期有效 */
    private LocalDateTime expireTime;

    /** 备注，运营自定义描述信息 */
    private String remark;
}
