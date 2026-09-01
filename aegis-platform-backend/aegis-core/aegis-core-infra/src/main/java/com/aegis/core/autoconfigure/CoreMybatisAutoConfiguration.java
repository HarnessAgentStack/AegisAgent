package com.aegis.core.autoconfigure;

import com.aegis.core.tenant.CoreMetaObjectHandler;
import com.aegis.core.tenant.CoreTenantLineHandler;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Set;

/**
 * MyBatis-Plus 自动配置（仅当 classpath 存在 mybatis-plus 时生效）。
 *
 * <p>从 CoreAutoConfiguration 拆分出来，避免 gateway 等不依赖 mybatis 的服务在加载
 * 自动配置时因找不到 mybatis 类而失败。
 */
@AutoConfiguration(after = CoreAutoConfiguration.class)
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class CoreMybatisAutoConfiguration {

    private static final Set<String> TENANT_IGNORE_TABLES = Set.of(
            "ten_tenant",
            "model_provider",
            "model_def",
            "sbx_pool",
            "sbx_base_image",
            "res_tool",
            "res_mcp_service",
            "res_mcp_subscription",
            "res_review",
            "sbx_instance",
            "sess_message",
            "sess_session",
            "agent_def",
            "agent_api_key",
            "mon_span",
            "sbx_lease",
            "sec_sensitive_word",
            "sec_tool_policy",
            "sec_outbound_policy",
            "sec_mask_rule",
            // P0: 权限字典表为平台级共享数据（tenant_id=0 表示全租户可见，tenant_id>0 表示租户自定义），
            // 租户插件强制加 tenant_id=当前租户 会把 tenant_id=0 的共享权限过滤掉导致 PermissionController.tree() 返回空。
            // 改为加入忽略列表，由 Service 层自行处理"平台共享 + 租户自定义"的合并逻辑。
            "org_permission",
            "org_role_permission"
    );

    @Bean
    @ConditionalOnMissingBean
    public CoreTenantLineHandler tenantLineHandler() {
        return new CoreTenantLineHandler(TENANT_IGNORE_TABLES);
    }

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor(CoreTenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    @ConditionalOnMissingBean
    public CoreMetaObjectHandler coreMetaObjectHandler() {
        return new CoreMetaObjectHandler();
    }
}
