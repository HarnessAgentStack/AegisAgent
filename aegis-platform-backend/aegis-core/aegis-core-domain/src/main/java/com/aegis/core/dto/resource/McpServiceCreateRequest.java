package com.aegis.core.dto.resource;

import com.aegis.core.enums.api.ApiAuthType;
import com.aegis.core.enums.resource.McpProtocol;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.enums.common.SecurityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * MCP 服务创建请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServiceCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** MCP 服务唯一编码，全局唯一 */
    private String mcpCode;

    /** MCP 服务展示名称 */
    private String mcpName;

    /** 服务图标 URL */
    private String icon;

    /** 服务提供方 */
    private String provider;

    /** 服务描述 */
    private String description;

    /** 版本号 */
    private String version;

    /** 服务接入端点 */
    private String endpoint;

    /** 传输协议：MCP_1_0 / HTTP_REST / GRPC */
    private McpProtocol protocol;

    /** 鉴权类型：API_KEY / BEARER / OAUTH2 / NONE */
    private ApiAuthType authType;

    /** 鉴权配置，JSON 字符串 */
    private String authConfig;

    /** 安全等级：L1~L4 */
    private SecurityLevel securityLevel;

    /** 状态：ACTIVE / PENDING */
    private ProviderStatus status;
}
