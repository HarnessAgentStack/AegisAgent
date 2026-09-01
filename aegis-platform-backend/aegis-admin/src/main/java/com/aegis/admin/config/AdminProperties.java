package com.aegis.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 管理平面配置属性。
 *
 * <p>承载 aegis-admin 运行期的可调参数：审核流程配置、计量计费规则、缓存策略等。
 * 通过 {@code @ConfigurationProperties} 绑定 Nacos 配置中心，支持运行期热更新。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code review}：审核流程配置（超时/默认审批人/自动通过阈值）</li>
 *   <li>{@code billing}：计量计费规则（计费周期/单价表/对账周期）</li>
 *   <li>{@code cache}：管理平面缓存策略（配置/资源元数据 TTL）</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>审核流程与计费规则变更热生效，无需重启</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aegis.admin")
public class AdminProperties {

    /** 审核流程配置 */
    private Review review = new Review();

    /** 计量计费配置 */
    private Billing billing = new Billing();

    /** 审核流程配置。 */
    @Data
    public static class Review {
        /** 审批超时（小时），超时按策略处理 */
        private int timeoutHours = 72;
        /** 默认审批人角色 */
        private String defaultApproverRole = "TENANT_ADMIN";
        /** 自动通过阈值（低风险变更自动通过，如版本号递增的技能更新） */
        private int autoApproveRiskThreshold = 10;
    }

    /** 计量计费配置。 */
    @Data
    public static class Billing {
        /** 计费周期（天） */
        private int billingCycleDays = 30;
        /** 对账周期（小时） */
        private int reconcileCycleHours = 24;
        /** 单价表 dataId（Nacos） */
        private String pricingDataId = "model-pricing.yaml";
    }
}
