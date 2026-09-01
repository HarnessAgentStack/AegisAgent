package com.aegis.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aegis 管理平面服务启动类。
 *
 * <p>负责智能体管理、资源管理、模型管理、安全管理、监管统计。
 *
 * @author wang.zhen
 */
@SpringBootApplication
@ComponentScan(
    basePackages = "com.aegis",
    excludeFilters = {
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.aegis\\.runtime\\..*"
        ),
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.aegis\\.core\\.autoconfigure\\..*"
        )
    }
)
@EnableDiscoveryClient
@EnableAsync
@EnableScheduling
@MapperScan({"com.aegis.dal.mapper", "com.aegis.dal.mapper..*"})
public class AegisAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(AegisAdminApplication.class, args);
    }
}
