package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 脱敏数据类型。
 *
 * @author wang.zhen
 */
@Getter
public enum MaskDataType {
    PHONE("手机号"),
    ID_CARD("身份证"),
    BANK_CARD("银行卡"),
    EMAIL("邮箱"),
    IP("IP地址"),
    PASSPORT("护照号"),
    LICENSE("车牌号"),
    COMPANY_ID("统一社会信用代码"),
    CUSTOM("自定义");

    private final String desc;

    MaskDataType(String desc) {
        this.desc = desc;
    }
}
