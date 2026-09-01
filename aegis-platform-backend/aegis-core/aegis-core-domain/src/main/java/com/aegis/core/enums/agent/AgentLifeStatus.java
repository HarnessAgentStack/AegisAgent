package com.aegis.core.enums.agent;

import lombok.Getter;

/**
 * 智能体生命周期状态枚举。
 *
 * <p>智能体从创建到下架的完整生命周期状态机，状态流转受审核流程与使用场景驱动，
 * 不可跳变（除驳回外），确保发布内容经过审核。
 *
 * <h3>状态流转</h3>
 * <ul>
 *   <li>ACTIVE -> REVIEWING：个人可用智能体申请共享发布，提交审核</li>
 *   <li>DRAFT -> REVIEWING：共享发布智能体提交审核</li>
 *   <li>REVIEWING -> PUBLISHED：审核通过</li>
 *   <li>REVIEWING -> REJECTED：审核驳回（可修改后重新提交）</li>
 *   <li>PUBLISHED -> ARCHIVED：主动下架</li>
 * </ul>
 *
 * <h3>初始状态</h3>
 * <ul>
 *   <li>个人使用（PERSONAL）创建后直接进入 ACTIVE 状态</li>
 *   <li>共享发布（SHARED）创建后进入 DRAFT 状态</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Getter
public enum AgentLifeStatus {

    /** 草稿：创建初始态，可编辑，仅创建者可见可对话自用 */
    DRAFT("草稿"),

    /** 审核中：已提交审核，主体字段冻结，等待审核结论 */
    REVIEWING("审核中"),

    /** 已发布：审核通过，进入智能体市场，可被订阅使用 */
    PUBLISHED("已发布"),

    /** 已归档：主动下架，历史会话只读，不可新建会话 */
    ARCHIVED("已归档"),

    /** 已拒绝：审核驳回，可修改后重新提交审核 */
    REJECTED("已拒绝");

    /** 状态中文描述，用于日志输出 */
    private final String desc;

    AgentLifeStatus(String desc) { this.desc = desc; }
}