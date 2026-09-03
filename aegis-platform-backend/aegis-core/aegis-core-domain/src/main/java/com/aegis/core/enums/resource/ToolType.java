package com.aegis.core.enums.resource;

import lombok.Getter;
import com.aegis.core.domain.security.ToolPolicy;
import com.aegis.core.enums.common.SecurityLevel;

/**
 * 工具类型枚举。
 *
 * <p>按工具的副作用与访问边界分类，是工具管控策略（ToolPolicy）决策矩阵的核心维度之一。
 * 与安全级别（SecurityLevel）正交，决定工具在不同安全级别下的处置策略。
 *
 * @author wang.zhen
 */
@Getter
public enum ToolType {

    /** 只读查询：无副作用查询，如读取配置、查询数据，低风险 */
    READONLY("只读"),

    /** 内部接口：调用企业内部系统API，需身份鉴权，中风险 */
    INTERNAL_API("内部接口"),

    /** 写入：产生数据变更，如创建订单、更新记录，中高风险 */
    WRITE("写入"),

    /** 外网访问：访问外部网络资源，存在数据出境风险，高风险 */
    EXTERNAL_NETWORK("外网访问"),

    /** 代码执行：在沙箱内执行代码，存在资源耗尽与逃逸风险，高风险 */
    CODE_EXEC("代码执行"),

    /** 高风险：删除、支付、外发等不可逆操作，需强制HITL审批 */
    /** 文件操作集：框架 FilesystemTool，读写/列出/搜索工作区文件 */
    FILE_OPS("文件操作"),

    /** 智能体调度：框架内部的子智能体生成/智能体生成工具 */
    AGENT("智能体调度"),

    /** 异步任务：框架后台任务创建/异步结果等待 */
    ASYNC("异步任务"),

    HIGH_RISK("高风险");

    /** 类型中文描述，用于日志输出 */
    private final String desc;

    ToolType(String desc) { this.desc = desc; }
}
