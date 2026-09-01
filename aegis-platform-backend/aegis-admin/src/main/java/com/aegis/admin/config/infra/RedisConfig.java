package com.aegis.admin.config.infra;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 配置。
 *
 * <p>管理平面 Redis 配置，为缓存和分布式锁提供存储后端。
 * 复用 Spring Boot 自动装配的 Lettuce 连接池，本类负责：
 * <ul>
 *   <li>Redisson 客户端 Bean（供 {@link com.aegis.core.spi.IDistributedLock} 实现）</li>
 * </ul></p>
 *
 * <h3>用途分区</h3>
 * <ul>
 *   <li>缓存：资源元数据（智能体/技能/知识库/MCP/工具）、模型路由、租户配置等热点数据</li>
 *   <li>分布式锁：审核流程并发控制、配置发布互斥、定时对账防重入</li>
 *   <li>限流：管理操作频率限制（防误操作/恶意批量）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    /**
     * 创建 Redisson 客户端 Bean。
     *
     * <p>通过 {@link ConditionalOnClass} 条件装配，仅当 classpath 中存在 Redisson 时才创建。
     * 读取 {@code spring.data.redis} 配置构建 {@link Redisson} 实例，
     * 与 Lettuce 共享同一 Redis 连接。</p>
     *
     * @return RedissonClient 实例
     */
    @Bean
    @ConditionalOnClass(RedissonClient.class)
    public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        if (password != null && !password.isEmpty()) {
            serverConfig.setPassword(password);
        }
        log.info("Redisson 客户端已创建: {}:{}", host, port);
        return Redisson.create(config);
    }
}