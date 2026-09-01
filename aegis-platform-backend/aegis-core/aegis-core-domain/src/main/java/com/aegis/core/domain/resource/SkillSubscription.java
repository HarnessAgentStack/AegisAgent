package com.aegis.core.domain.resource;

import com.aegis.core.enums.resource.SubscriberType;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 技能订阅关系实体。
 *
 * <p>记录用户（USER）或智能体（AGENT）对技能的真实订阅关系，
 * 替代原有的假订阅实现，支持按订阅者类型与技能维度的双向查询。</p>
 *
 * <h3>业务约束</h3>
 * <ul>
 *   <li>同一租户内，同一订阅者对同一技能仅允许一条 ACTIVE 记录（UK 唯一键保障）</li>
 *   <li>订阅时可锁定版本（subscribed_version），NULL 表示跟随技能 active_version</li>
 *   <li>取消订阅采用软删除（deleted=1），保留历史审计</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("res_skill_subscription")
public class SkillSubscription {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("tenant_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @TableField("skill_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skillId;

    @TableField("skill_code")
    private String skillCode;

    @TableField("subscriber_type")
    private SubscriberType subscriberType;

    @TableField("subscriber_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long subscriberId;

    @TableField("subscribed_version")
    private String subscribedVersion;

    @TableField(fill = FieldFill.INSERT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    @TableField(value = "deleted", select = false)
    private Integer deleted;
}