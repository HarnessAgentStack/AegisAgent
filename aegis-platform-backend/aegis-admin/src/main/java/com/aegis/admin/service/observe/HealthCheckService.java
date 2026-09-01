package com.aegis.admin.service.observe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查服务。
 *
 * <p>聚合平台各组件的健康状态，包括运行平面、管理平面、网关、MySQL、Redis、Nacos，
 * 返回统一格式的健康报告。</p>
 *
 * <p>探活地址通过 {@code aegis.ha.health.*} 配置项注入：</p>
 * <ul>
 *     <li>{@code aegis.ha.health.runtime-url}：运行平面 Actuator 健康端点</li>
 *     <li>{@code aegis.ha.health.admin-url}：管理平面 Actuator 健康端点</li>
 *     <li>{@code aegis.ha.health.gateway-url}：网关 Actuator 健康端点</li>
 *     <li>{@code aegis.ha.health.nacos-url}：Nacos 服务指标端点</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Service
@Slf4j
public class HealthCheckService {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    @Value("${aegis.ha.health.runtime-url}")
    private String runtimeHealthUrl;

    @Value("${aegis.ha.health.admin-url}")
    private String adminHealthUrl;

    @Value("${aegis.ha.health.gateway-url}")
    private String gatewayHealthUrl;

    @Value("${aegis.ha.health.nacos-url}")
    private String nacosMetricsUrl;

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 5000;

    public HealthCheckService(DataSource dataSource, RedisConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    /**
     * 检查所有组件健康状态。
     *
     * @return 组件健康状态 Map，key 为组件名称，value 为健康详情
     */
    public Map<String, Object> checkAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runtime", checkHttp("runtime", runtimeHealthUrl));
        result.put("admin", checkHttp("admin", adminHealthUrl));
        result.put("gateway", checkHttp("gateway", gatewayHealthUrl));
        result.put("mysql", checkMysql());
        result.put("redis", checkRedis());
        result.put("nacos", checkHttp("nacos", nacosMetricsUrl));
        return result;
    }

    /**
     * 通过 HTTP 检查组件健康状态。
     */
    private Map<String, Object> checkHttp(String name, String urlStr) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            int code = conn.getResponseCode();
            String status = (code >= 200 && code < 300) ? "UP" : "DOWN";
            info.put("status", status);
            info.put("detail", "HTTP " + code);
        } catch (Exception e) {
            log.warn("健康检查失败: {} - {}", name, e.getMessage());
            info.put("status", "DOWN");
            info.put("detail", e.getMessage());
        }
        info.put("lastCheckTime", LocalDateTime.now().toString());
        return info;
    }

    /**
     * 检查 MySQL 连接。
     */
    private Map<String, Object> checkMysql() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "mysql");
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(CONNECT_TIMEOUT / 1000);
            info.put("status", valid ? "UP" : "DOWN");
            info.put("detail", valid ? "Connection valid" : "Connection invalid");
        } catch (Exception e) {
            log.warn("MySQL 健康检查失败: {}", e.getMessage());
            info.put("status", "DOWN");
            info.put("detail", e.getMessage());
        }
        info.put("lastCheckTime", LocalDateTime.now().toString());
        return info;
    }

    /**
     * 检查 Redis 连接。
     */
    private Map<String, Object> checkRedis() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "redis");
        try {
            String pong = redisConnectionFactory.getConnection().ping();
            info.put("status", "pong".equalsIgnoreCase(pong) ? "UP" : "DOWN");
            info.put("detail", pong);
        } catch (Exception e) {
            log.warn("Redis 健康检查失败: {}", e.getMessage());
            info.put("status", "DOWN");
            info.put("detail", e.getMessage());
        }
        info.put("lastCheckTime", LocalDateTime.now().toString());
        return info;
    }
}
