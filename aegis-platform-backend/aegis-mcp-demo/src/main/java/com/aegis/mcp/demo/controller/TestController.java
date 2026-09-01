package com.aegis.mcp.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 简单测试控制器，验证 Spring MVC 路由是否正常工作。
 */
@RestController
@RequestMapping("/test")
public class TestController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from MCP Demo!");
    }
}
