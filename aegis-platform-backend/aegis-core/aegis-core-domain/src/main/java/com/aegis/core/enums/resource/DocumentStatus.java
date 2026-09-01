package com.aegis.core.enums.resource;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 知识库文档处理状态。
 *
 * @author wang.zhen
 */
@Getter
public enum DocumentStatus {
    PENDING("待扫描"),
    SCANNING("扫描中"),
    CHUNKED("已切片"),
    CHUNKING("切片中"),
    FAILED("失败");

    private final String desc;

    DocumentStatus(String desc) {
        this.desc = desc;
    }

    @JsonValue
    public String toValue() {
        return name();
    }
}
