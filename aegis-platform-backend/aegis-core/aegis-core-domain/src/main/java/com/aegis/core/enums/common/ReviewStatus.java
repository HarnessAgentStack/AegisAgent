package com.aegis.core.enums.common;

import lombok.Getter;

/**
 * 审核状态枚举。
 *
 * <p>资源发布审核工单的通用状态。
 * 审核流转由审核员驱动，PENDING为初始态，APPROVED/REJECTED为终态。
 *
 * @author wang.zhen
 */
@Getter
public enum ReviewStatus {

    /** 待审核：工单已提交，等待审核人处理 */
    PENDING("待审核"),

    /** 已通过：审核人批准，资源进入发布态或订阅生效 */
    APPROVED("已通过"),

    /** 已拒绝：审核人驳回，附驳回理由，发起人可修改后重新提交 */
    REJECTED("已拒绝");

    /** 状态中文描述，用于日志输出 */
    private final String desc;

    ReviewStatus(String desc) { this.desc = desc; }
}