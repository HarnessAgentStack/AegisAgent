package com.aegis.mcp.demo.controller;

import com.aegis.mcp.demo.config.AegisMcpDemoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Demo 信息查询接口，方便快速验证当前 Demo 配置与状态。
 */
@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
public class DemoInfoController {

    private final AegisMcpDemoProperties properties;

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("mcpCode", properties.getMcpCode());
        info.put("mcpName", properties.getMcpName());
        info.put("version", properties.getVersion());
        info.put("endpoint", properties.getEndpoint());
        info.put("protocol", properties.getProtocol());
        info.put("authType", properties.getAuthType());
        info.put("securityLevel", properties.getSecurityLevel());
        info.put("adminBaseUrl", properties.getAdminBaseUrl());
        info.put("autoRegister", properties.isAutoRegister());
        return info;
    }
}
