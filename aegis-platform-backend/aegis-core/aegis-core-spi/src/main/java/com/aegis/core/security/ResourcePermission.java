package com.aegis.core.security;

import com.aegis.core.enums.resource.ResourceType;
import lombok.Getter;

/**
 * 资源权限枚举：定义资源操作的权限级别。
 *
 * <p>用于 @ResourceOwner 注解中指定需要的权限类型，
 * 与资源类型配合使用实现细粒度权限控制。
 *
 *  @author wang.zhen
 */
@Getter
public enum ResourcePermission {

    /** 查看权限 */
    VIEW("查看"),

    /** 创建权限 */
    CREATE("创建"),

    /** 编辑权限 */
    EDIT("编辑"),

    /** 删除权限 */
    DELETE("删除"),

    /** 发布权限 */
    PUBLISH("发布"),

    /** 管理权限（包含所有操作） */
    MANAGE("管理");

    private final String desc;

    ResourcePermission(String desc) {
        this.desc = desc;
    }

    /**
     * 判断当前权限是否包含指定权限。
     * MANAGE 包含所有其他权限。
     */
    public boolean includes(ResourcePermission other) {
        if (this == MANAGE) {
            return true;
        }
        return this == other;
    }

    /**
     * 根据资源类型获取默认需要的权限。
     */
    public static ResourcePermission defaultPermission(ResourceType resourceType) {
        return switch (resourceType) {
            case AGENT, AGENT_API, SKILL, KNOWLEDGE_BASE, MCP_SERVICE -> MANAGE;
            case TOOL, DATASET -> VIEW;
        };
    }
}
