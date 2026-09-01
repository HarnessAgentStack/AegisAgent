package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * 文档处理步骤枚举。
 *
 * <p>定义知识库文档处理流水线中的所有步骤，
 * 用于进度追踪。
 *
 *  @author wang.zhen  
 */
@Getter
public enum ProcessStep {

    DOWNLOADING("DOWNLOADING", 1, "下载中", "从对象存储下载文档..."),
    SCANNING("SCANNING", 2, "扫描中", "安全扫描中..."),
    CHUNKING("CHUNKING", 3, "切片中", "文本切片处理中..."),
    EMBEDDING("EMBEDDING", 4, "嵌入中", "文本向量化中..."),
    VECTORING("VECTORING", 5, "向量入库", "向量写入向量库中..."),
    COMPLETED("COMPLETED", 6, "已完成", "处理完成");

    private final String code;
    private final int order;
    private final String displayName;
    private final String defaultMessage;

    ProcessStep(String code, int order, String displayName, String defaultMessage) {
        this.code = code;
        this.order = order;
        this.displayName = displayName;
        this.defaultMessage = defaultMessage;
    }

    public static ProcessStep fromCode(String code) {
        if (code == null) return null;
        for (ProcessStep step : values()) {
            if (step.code.equalsIgnoreCase(code)) {
                return step;
            }
        }
        return null;
    }
}
