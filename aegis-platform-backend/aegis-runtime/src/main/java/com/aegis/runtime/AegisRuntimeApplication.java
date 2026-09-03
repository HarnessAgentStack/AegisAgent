package com.aegis.runtime;

import com.aegis.core.common.tenant.TenantContextPropagationInitializer;
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
 * <p>P1-A：在 SpringApplication.run 之前调用 {@link TenantContextPropagationInitializer#init()}
 * 启用 Reactor 跨线程租户上下文自动传播（与手工 bind 互为保险）。
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
        // P1-A：Reactor 跨线程租户上下文自动传播（必须在 run 之前）
        TenantContextPropagationInitializer.init();
        SpringApplication.run(AegisRuntimeApplication.class, args);
    }
}
