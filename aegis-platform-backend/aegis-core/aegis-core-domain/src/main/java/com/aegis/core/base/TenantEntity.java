package com.aegis.core.base;

import com.baomidou.mybatisplus.annotation.TableField;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.aegis.core.context.TenantContext;

/**
 * 租户隔离实体基类，在 BaseEntity 基础上增加 tenantId 字段。
 *
 * <p>平台多租户隔离的核心载体，所有需要按租户隔离数据的业务实体均应继承本类。
 * tenantId 字段贯穿数据访问层，配合 MyBatis-Plus 多租户插件实现 SQL 自动拼装租户条件。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>tenantId 在数据插入时由租户上下文（TenantContext）自动注入</li>
 *   <li>查询时自动附加 tenant_id 条件，防止跨租户数据越权访问</li>
 *   <li>跨租户共享数据（如平台级模型供应商）应直接继承 BaseEntity 而非本类</li>
 * </ul>
 *
 * <h3>关联实体</h3>
 * <ul>
 *   <li>{@link BaseEntity} - 抽象基类，提供主键与审计字段</li>
 *   <li>{@link com.aegis.core.context.TenantContext} - 租户上下文，提供 tenantId 来源</li>
 * </ul>
 *
 * @author wang.zhen
 * @see BaseEntity
 * @see com.aegis.core.context.TenantContext
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class TenantEntity extends BaseEntity {

    /** 租户ID，关联租户主键，数据隔离核心字段，由租户上下文自动注入。序列化为字符串防止前端精度丢失。 */
    @TableField("tenant_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;
}
