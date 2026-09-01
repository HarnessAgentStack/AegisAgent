package com.aegis.core.tenant;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 多租户行级过滤处理器。
 *
 * <p>对需要忽略租户隔离的表（系统共享表）放行，其余表自动追加 tenant_id 过滤条件。
 */
public class CoreTenantLineHandler implements TenantLineHandler {

    private static final Logger log = LoggerFactory.getLogger(CoreTenantLineHandler.class);

    private final Set<String> ignoreTables;

    public CoreTenantLineHandler(Set<String> ignoreTables) {
        this.ignoreTables = ignoreTables;
    }

    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            // fail-closed：租户上下文缺失直接抛异常，防止静默回退 tenant_id=0 造成跨租户数据越权
            // 常见缺失场景：@Async 线程未传递租户上下文、定时任务未显式设置 TenantContext、
            // 绕过 Controller/AOP 直接调用 Mapper
            log.error("租户上下文缺失！这通常是代码 bug：@Async 线程未传递上下文 / 定时任务未显式绑定 / 绕过 Controller 直调 Mapper");
            throw new IllegalStateException(
                    "租户上下文缺失，拒绝执行数据库操作（fail-closed）。" +
                    "请确保调用链已通过 JwtAuthenticationToken 或 TenantContextHolder.bind(tenantId) 绑定租户ID。"
            );
        }
        return new LongValue(tenantId);
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return ignoreTables.contains(tableName);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }
}
