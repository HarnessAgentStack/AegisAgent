package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * 资源类型枚举。
 *
 * <p>平台可治理资源的统一分类，用于资源绑定、权限管控、审核流程与配额计量的类型路由。
 * 不同资源类型对应不同的发布审核策略与订阅审批规则。
 *
 * @author wang.zhen
 */
@Getter
public enum ResourceType {

    /** 智能体：平台核心资源，发布需审核，订阅可能需审批 */
    AGENT("智能体"),

    /** 技能：工具封装资源，用户可发布，发布需审核 */
    SKILL("技能"),

    /** 知识库：RAG检索资源，用户可发布，发布需审核 */
    KNOWLEDGE_BASE("知识库"),

    /** MCP服务：工具协议服务端，由管理员发布，全租户共享，需审核 */
    MCP_SERVICE("MCP服务"),

    /** 工具：原子能力单元，来源平台内置或MCP，工具定义只读 */
    TOOL("工具"),

    /** 数据集：结构化数据资源，用于数据分析与模型微调 */
    DATASET("数据集");

    /** 类型中文描述，用于日志输出 */
    private final String desc;

    ResourceType(String desc) { this.desc = desc; }
}