package com.aegis.core.base;

import com.baomidou.mybatisplus.annotation.*;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 所有实体的抽象基类，提供主键、审计字段与逻辑删除标识。
 *
 * <p>平台领域实体的统一根基类，承载雪花ID主键、创建/更新审计字段以及逻辑删除标识。
 * 所有业务实体（租户隔离与非租户隔离）均继承自本类，确保统一的审计追踪与软删除语义。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>主键采用雪花算法（IdType.ASSIGN_ID）生成，全局唯一且趋势递增</li>
 *   <li>审计字段由 MyBatis-Plus 自动填充，业务代码无需手动赋值</li>
 *   <li>逻辑删除字段（deleted）查询时默认过滤，0-未删除 1-已删除</li>
 *   <li>所有 Long 类型 ID 字段通过 {@code @JsonSerialize(ToStringSerializer)} 序列化为字符串，
 *       防止前端 JavaScript Number 精度丢失（雪花ID超过 JS Number.MAX_SAFE_INTEGER）</li>
 * </ul>
 *
 * <h3>关联实体</h3>
 * <ul>
 *   <li>{@link TenantEntity} - 租户隔离实体基类，扩展 tenantId 字段</li>
 * </ul>
 *
 * @author wang.zhen
 * @see TenantEntity
 */
@Data
public abstract class BaseEntity {

    /** 主键ID，雪花算法生成，全局唯一，趋势递增。序列化为字符串防止前端精度丢失。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 创建人ID，关联用户主键，INSERT 时自动填充。序列化为字符串防止前端精度丢失。 */
    @TableField(fill = FieldFill.INSERT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createBy;

    /** 创建时间，INSERT 时自动填充，格式 yyyy-MM-dd HH:mm:ss */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新人ID，关联用户主键，INSERT/UPDATE 时自动填充。序列化为字符串防止前端精度丢失。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updateBy;

    /** 更新时间，INSERT/UPDATE 时自动填充，格式 yyyy-MM-dd HH:mm:ss */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标识：0-未删除（默认） 1-已删除，查询时自动过滤已删除记录 */
    @TableLogic
    @TableField(value = "deleted", select = false)
    private Integer deleted;
}
