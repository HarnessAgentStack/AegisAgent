package com.aegis.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 管理后台配置类。
 *
 * <p>启用异步支持（@Async），供知识库文档异步处理流水线等场景使用。
 * <p>启用定时任务（@Scheduled），供沙箱空闲回收调度器使用。
 *
 * @author wang.zhen
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AdminConfig {
}
