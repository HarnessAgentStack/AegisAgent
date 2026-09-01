package com.aegis.core.enums.resource;

/**
 * 资源（Skill / KnowledgeBase 等）列表查询范围枚举。
 *
 * <p>统一替代散落的字符串 "market" / "mine" / "subscribed"，
 * 避免魔法字符串比较（如 {@code "market".equalsIgnoreCase(scope)}）。
 *
 * <ul>
 *     <li>{@link #MARKET} — 市场视图，租户隔离，仅返回本租户已发布资源</li>
 *     <li>{@link #MINE} — 个人视图，仅返回当前用户创建的资源</li>
 *     <li>{@link #SUBSCRIBED} — 已订阅视图，返回当前用户订阅/收藏的资源</li>
 * </ul>
 *
 * @author aegis-platform-backend
 */
public enum ResourceScope {

    /** 市场视图（已发布 + 租户隔离）。 */
    MARKET("market"),

    /** 个人视图（仅当前用户创建）。 */
    MINE("mine"),

    /** 已订阅视图。 */
    SUBSCRIBED("subscribed");

    private final String code;

    ResourceScope(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 从字符串 code 解析枚举；解析失败返回 null。
     *
     * @param code scope 字符串（如 "market" / "mine"）
     * @return 枚举值或 null
     */
    public static ResourceScope fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ResourceScope e : values()) {
            if (e.code.equalsIgnoreCase(code)) {
                return e;
            }
        }
        return null;
    }
}
