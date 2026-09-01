package com.aegis.core.domain.resource;

import com.aegis.core.enums.resource.SubscriberType;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 知识库订阅关系实体。
 *
 * <p>记录用户（USER）或智能体（AGENT）对知识库的真实订阅关系，
 * 支持按订阅者类型与知识库维度的双向查询。</p>
 *
 * <h3>业务约束</h3>
 * <ul>
 *   <li>同一租户内，同一订阅者对同一知识库仅允许一条 ACTIVE 记录</li>
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
@TableName("res_kb_subscription")
public class KbSubscription {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @TableField("tenant_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @TableField("kb_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long kbId;

    @TableField("kb_code")
    private String kbCode;

    @TableField("subscriber_type")
    private SubscriberType subscriberType;

    @TableField("subscriber_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long subscriberId;

    @TableField(fill = FieldFill.INSERT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    @TableField(value = "deleted", select = false)
    private Integer deleted;
}
