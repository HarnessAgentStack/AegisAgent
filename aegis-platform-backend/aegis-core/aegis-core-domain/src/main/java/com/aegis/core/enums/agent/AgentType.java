package com.aegis.core.enums.agent;

import lombok.Getter;

/**
 * 智能体类型。
 *
 * @author wang.zhen
 */
@Getter
public enum AgentType {
    /** 通用智能体：平台唯一，默认所有用户可用，按用户动态加载资源 */
    UNIVERSAL("通用智能体"),
    /** 应用智能体：用户创建，固定绑定资源，不可发布 API */
    APPLICATION("应用智能体"),
    /** 系统智能体：面向业务系统发布，常驻 K8S POD，可发布 API、支持系统回调与指定输出格式 */
    SYSTEM("系统智能体");

    private final String desc;

    AgentType(String desc) {
        this.desc = desc;
    }
}
