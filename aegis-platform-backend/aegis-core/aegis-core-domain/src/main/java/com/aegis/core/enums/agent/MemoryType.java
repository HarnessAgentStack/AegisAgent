package com.aegis.core.enums.agent;

import lombok.Getter;
import com.aegis.core.domain.agent.AgentMemory;

/**
 * 记忆类型枚举。
 *
 * <p>智能体记忆（AgentMemory）的三分类，不同类型有不同的写入时机与检索策略。
 * 记忆在会话中自动提取并持久化，跨会话复用以提升交互个性化。
 *
 * @author wang.zhen
 */
@Getter
public enum MemoryType {

    /** 用户画像：用户偏好/角色/习惯，长期记忆，跨会话累积更新 */
    USER_PROFILE("用户画像"),

    /** 任务摘要：会话任务的结构化摘要，中期记忆，用于上下文压缩 */
    TASK_SUMMARY("任务摘要"),

    /** 关键事实：会话中提取的实体/数值/结论，短期记忆，用于精准引用 */
    KEY_FACT("关键事实");

    /** 类型中文描述，用于日志输出 */
    private final String desc;

    MemoryType(String desc) { this.desc = desc; }
}