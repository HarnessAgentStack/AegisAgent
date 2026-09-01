package com.aegis.core.enums.sandbox;

import lombok.Getter;
import com.aegis.core.enums.common.SecurityLevel;

/**
 * 沙箱池类型枚举。
 *
 * <p>按资源规格与隔离等级分桶，不同类型对应不同的CPU/内存配额与网络策略。
 * 沙箱池类型由智能体安全级别（SecurityLevel）驱动分配。
 *
 * @author wang.zhen
 */
@Getter
public enum SandboxPoolType {

    /** 通用：默认池类型，中等规格，适合大多数场景 */
    GENERAL("通用"),

    /** 通用轻量：低规格CPU/内存，适合只读查询与轻量计算，L1-L2场景 */
    LIGHT("轻量"),

    /** 标准执行：中规格CPU/内存，适合常规代码执行与工具调用，L2场景 */
    STANDARD("标准"),

    /** 重型计算：高规格CPU/内存/GPU，适合模型推理与大规模数据处理 */
    HEAVY("重量"),

    /** 高安全隔离：隔离网络环境，无外网访问，适合L3-L4涉密场景 */
    ISOLATED("隔离"),

    /** 临时调试：短生命周期实例，用于开发调试与问题排查，自动回收 */
    DEBUG("调试");

    /** 类型中文描述，用于日志输出 */
    private final String desc;

    SandboxPoolType(String desc) { this.desc = desc; }
}