package com.aegis.runtime.integration.config;

import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import redis.clients.jedis.UnifiedJedis;

import java.net.URI;

/**
 * AgentScope 2.0.2 集成统一 Redis 配置。
 *
 * <p>通过 AS 2.0.2 原生的 {@link RedisDistributedStore} 一站式配置分布式存储组件：
 * <ul>
 *   <li>{@link UnifiedJedis} — Redis 客户端（所有组件共享）</li>
 *   <li>{@link DistributedStore} — 统一分布式存储入口，自动装配：
 *     <ul>
 *       <li>{@code RedisAgentStateStore} — AgentState 持久化（keyPrefix = aegis:session:）</li>
 *       <li>{@code RedisStore} — Workspace 文件系统 KV（keyPrefix = aegis:store:）</li>
 *       <li>{@code RedisSnapshotSpec} — 沙箱快照存储（keyPrefix = aegis:snapshot:）</li>
 *       <li>{@code RedisSandboxExecutionGuard} — 沙箱分布式锁（keyPrefix = aegis:guard:）</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>Key 命名规范</h3>
 * <pre>
 *   aegis:session:{userId}/{sessionId}:agent_state        ← AgentState（单值）
 *   aegis:session:{userId}/{sessionId}:agent_state:list   ← AgentState（列表）
 *   aegis:session:{userId}/{sessionId}:_keys             ← 会话 key 索引
 *   aegis:store:item:{namespace}\0{key}                  ← Workspace 文件
 *   aegis:store:idx:{namespace}                          ← Workspace 索引
 *   aegis:snapshot:{slotKey}                             ← 沙箱快照
 *   aegis:guard:{slotKey}                                ← 沙箱锁
 * </pre>
 *
 * @author wang.zhen
 */
@Slf4j
@Configuration
@ConditionalOnClass(UnifiedJedis.class)
public class AegisRedisConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /** Redis key 统一前缀，所有 AS 组件共享 */
    @Value("${aegis.runtime.redis.key-prefix:aegis:}")
    private String keyPrefix;

    /** Redisson 分布式锁：数据库索引 */
    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    private UnifiedJedis unifiedJedis;
    private RedissonClient redissonClient;

    /**
     * 创建统一 Redis 客户端 Bean。
     *
     * <p>所有 AgentScope Redis 组件（RedisAgentStateStore / RedisStore / RedisSnapshotSpec /
     * RedisSandboxExecutionGuard）通过 DistributedStore 共享此实例。
     *
     * @return UnifiedJedis 实例
     */
    @Bean
    public UnifiedJedis unifiedJedis() {
        String redisUrl = (redisPassword != null && !redisPassword.isEmpty())
                ? "redis://:" + redisPassword + "@" + redisHost + ":" + redisPort
                : "redis://" + redisHost + ":" + redisPort;
        unifiedJedis = new UnifiedJedis(URI.create(redisUrl));
        log.info("AgentScope UnifiedJedis 已创建: {}:{}", redisHost, redisPort);
        return unifiedJedis;
    }

    /**
     * 创建 Redisson 客户端 Bean（供 core 的 IDistributedLock/RedisDistributedLock 使用）。
     */
    @Bean
    @ConditionalOnClass(RedissonClient.class)
    public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setDatabase(redisDatabase);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            serverConfig.setPassword(redisPassword);
        }
        redissonClient = Redisson.create(config);
        log.info("Redisson 客户端已创建: {}:{}", redisHost, redisPort);
        return redissonClient;
    }

    /**
     * 创建 DistributedStore Bean（AS 分布式存储配置）。
     *
     * <p>启用 {@code RedisSandboxExecutionGuard} 提供沙箱执行的分布式互斥，
     * 确保多节点环境下不会发生跨节点并发沙箱操作。
     *
     * <p>配置以下组件：
     * <ul>
     *   <li>{@code RedisAgentStateStore} — AgentState 持久化（keyPrefix + "session:"）</li>
     *   <li>{@code RedisStore} — Workspace 文件系统 KV（keyPrefix + "store:"）</li>
     *   <li>{@code RedisSnapshotSpec} — 沙箱快照存储（keyPrefix + "snapshot:"）</li>
     *   <li>{@code sandboxExecutionGuard} — Redis 沙箱分布式锁（keyPrefix + "guard:"）</li>
     * </ul>
     *
     * @param jedis Redis 客户端
     * @return DistributedStore 实例
     */
    @Bean
    public DistributedStore distributedStore(UnifiedJedis jedis) {
        RedisDistributedStore redisStore = RedisDistributedStore.fromJedis(jedis, keyPrefix);

        // 使用 RedisSandboxExecutionGuard 提供分布式沙箱锁
        DistributedStore store = DistributedStore.builder()
                .agentStateStore(redisStore.agentStateStore())
                .baseStore(redisStore.baseStore())
                .sandboxSnapshotSpec(redisStore.sandboxSnapshotSpec())
                .sandboxExecutionGuard(redisStore.sandboxExecutionGuard())
                .build();

        log.info("AgentScope DistributedStore 已创建（启用 RedisSandboxExecutionGuard）: keyPrefix={}", keyPrefix);
        return store;
    }

    /**
     * 暴露 BaseStore Bean（从 DistributedStore 获取）。
     *
     * <p>供 {@code WorkspaceMaterializer} 等需要直接操作文件系统 KV 的组件使用。
     * 实际类型为 {@code RedisStore}（由 {@link RedisDistributedStore#baseStore()} 创建）。
     *
     * @param distributedStore 分布式存储
     * @return BaseStore 实例
     */
    @Bean
    public BaseStore baseStore(DistributedStore distributedStore) {
        BaseStore store = distributedStore.baseStore();
        log.info("AgentScope BaseStore 已从 DistributedStore 获取: {}", store.getClass().getSimpleName());
        return store;
    }

    /**
     * 创建 Redis 消息监听器容器（用于策略缓存 pub/sub 失效通知）。
     *
     * <p>在响应式 WebFlux 环境下，使用 Spring Boot 自动注入的
     * {@link RedisConnectionFactory}（LettuceConnectionFactory）构建监听器容器。
     *
     * @param connectionFactory Redis 连接工厂
     * @return RedisMessageListenerContainer 实例
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        log.info("RedisMessageListenerContainer 已创建");
        return container;
    }

    @PreDestroy
    public void destroy() {
        if (unifiedJedis != null) {
            try {
                unifiedJedis.close();
                log.info("AgentScope UnifiedJedis 已关闭");
            } catch (Exception e) {
                log.warn("关闭 UnifiedJedis 失败", e);
            }
        }
        if (redissonClient != null) {
            try {
                redissonClient.shutdown();
                log.info("RedissonClient 已关闭");
            } catch (Exception e) {
                log.warn("关闭 RedissonClient 失败", e);
            }
        }
    }
}
