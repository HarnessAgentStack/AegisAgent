package com.aegis.runtime;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aegis 运行平面服务启动类。
 *
 * <p>负责智能体执行、沙箱管理、会话管理、SSE 流式输出。
 *
 * @author wang.zhen
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableAsync
@EnableScheduling
@MapperScan({"com.aegis.dal.mapper", "com.aegis.dal.mapper..*"})
@ComponentScan(
    basePackages = "com.aegis",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.aegis\\.core\\.autoconfigure\\..*"
    )
)
public class AegisRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AegisRuntimeApplication.class, args);
    }
}
