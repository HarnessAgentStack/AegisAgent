package com.aegis.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Aegis 网关服务启动类。
 *
 * <p>负责流量入口、租户标识解析、认证鉴权、限流降级、路由转发。
 *
 * @author wang.zhen
 */
@SpringBootApplication
@EnableDiscoveryClient
public class AegisGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(AegisGatewayApplication.class, args);
    }
}
