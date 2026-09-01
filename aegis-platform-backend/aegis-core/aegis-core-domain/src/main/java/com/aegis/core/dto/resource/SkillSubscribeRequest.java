package com.aegis.core.dto.resource;

import com.aegis.core.enums.resource.SubscriberType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 技能订阅/取消订阅请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSubscribeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订阅者类型：USER / AGENT，默认 USER */
    private SubscriberType subscriberType;

    /** 订阅时锁定的版本号，NULL 表示跟随 active_version */
    private String subscribedVersion;
}