package com.aegis.core.enums.common;

import lombok.Getter;

/**
 * 安全级别枚举。
 *
 * <p>平台数据与操作安全分级标准，贯穿沙箱分配、工具管控、数据脱敏、出站策略全链路。
 * 级别由低到高递增，高级别自动继承低级别所有限制。
 *
 * @author wang.zhen
 */
@Getter
public enum SecurityLevel {

    /** L1 公开级：通用问答场景，通用沙箱池，白名单MCP出站，无敏感数据 */
    L1(1, "公开级"),

    /** L2 内部级：内部文档场景，标准沙箱池，白名单+工具出站，内部可读 */
    L2(2, "内部级"),

    /** L3 机密级：涉密业务场景，隔离沙箱池，严格出站+审计，脱敏处理 */
    L3(3, "机密级"),

    /** L4 绝密级：核心涉密场景，高安全无外网沙箱，禁止出站，全程加密 */
    L4(4, "绝密级");

    /** 级别数字（1-4），用于 DB 存储与排序 */
    private final int level;

    /** 级别中文描述，用于日志输出 */
    private final String desc;

    SecurityLevel(int level, String desc) {
        this.level = level;
        this.desc = desc;
    }

    /**
     * 从整数级别转换为枚举。
     *
     * @param level 整数级别（1-4）
     * @return 对应的 SecurityLevel，非法值默认返回 L1
     */
    public static SecurityLevel fromLevel(Integer level) {
        if (level == null) {
            return L1;
        }
        for (SecurityLevel sl : values()) {
            if (sl.level == level) {
                return sl;
            }
        }
        return L1;
    }
}