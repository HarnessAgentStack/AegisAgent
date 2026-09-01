package com.aegis.core.dto.observe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测模块配置属性。
 *
 * <p>通过 {@code aegis.observe.*} 前缀绑定配置，支持存储类型选择、
 * 批量写入参数与 ClickHouse 连接配置。</p>
 *
 *  @author wang.zhen
 */
@Data
@ConfigurationProperties(prefix = "aegis.observe")
public class ObserveProperties {

    /** 存储类型：mysql / clickhouse */
    private String store = "mysql";

    /** 批量写入配置 */
    private Batch batch = new Batch();

    /** 数据保留天数 */
    private int retentionDays = 30;

    /** ClickHouse 配置 */
    private Clickhouse clickhouse = new Clickhouse();

    /**
     * 批量写入配置。
     */
    @Data
    public static class Batch {

        /** 批量写入大小 */
        private int size = 500;

        /** 刷新间隔（秒） */
        private int flushSeconds = 5;
    }

    /**
     * ClickHouse 连接配置。
     */
    @Data
    public static class Clickhouse {

        /** JDBC URL */
        private String jdbcUrl = "jdbc:clickhouse://localhost:8123/aegis_observe";

        /** 用户名 */
        private String username = "default";

        /** 密码 */
        private String password = "";

        /** 批量写入大小 */
        private int batchSize = 5000;
    }
}