package com.aegis.core.enums.api;

import lombok.Getter;
import com.aegis.core.domain.agent.AgentApi;

/**
 * API认证类型枚举。
 *
 * <p>智能体开放API（AgentApi）、MCP 服务/客户端的认证方式，决定请求鉴权流程。
 * 不同认证类型对应不同的密钥管理与安全等级。
 *
 * @author wang.zhen
 */
@Getter
public enum ApiAuthType {

    /** API Key：静态密钥认证，简单易用，适合内部系统集成 */
    API_KEY("API Key"),

    /** Bearer Token：JWT令牌认证，含过期时间与权限声明，适合中安全场景 */
    BEARER("Bearer Token"),

    /** OAuth2：授权码/客户端凭证模式，支持细粒度scope，适合高安全场景 */
    OAUTH2("OAuth2"),

    /** Basic Auth：用户名+密码基础认证，适合内部 MCP 服务与受控系统集成 */
    BASIC("Basic Auth"),

    /** 无认证：开放接口，仅适合公开数据查询，需配合限流策略 */
    NONE("无认证");

    /** 类型中文描述，用于日志输出 */
    private final String desc;

    ApiAuthType(String desc) { this.desc = desc; }
}