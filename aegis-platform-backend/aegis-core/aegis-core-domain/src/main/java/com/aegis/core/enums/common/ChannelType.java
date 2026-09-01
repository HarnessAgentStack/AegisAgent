package com.aegis.core.enums.common;

import lombok.Getter;

/**
 * 渠道类型枚举。
 *
 * <p>智能体会话的接入渠道，不同渠道对应不同的消息协议与交互能力。
 * 同一智能体可多渠道接入，会话按渠道隔离。
 *
 * @author wang.zhen
 */
@Getter
public enum ChannelType {

    /** Web端：浏览器网页接入，支持富文本/流式输出/卡片交互 */
    WEB("Web端"),

    /** 飞书：飞书即时通讯接入，支持消息卡片与机器人交互 */
    FEISHU("飞书"),

    /** A2A协议：Agent-to-Agent协议接入，支持智能体间标准化通信 */
    A2A("A2A协议");

    /** 类型中文描述，用于日志输出 */
    private final String desc;

    ChannelType(String desc) { this.desc = desc; }
}