package com.aegis.core.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户上下文，承载当前请求所属租户的基础信息。
 *
 * <p>贯穿全链路的租户标识载体，从入口（HTTP Header / MQ 消息属性）解析后，
 * 通过 ThreadLocal（同步链路）与 Reactor Context（响应式链路）传递至数据访问层，
 * 配合 MyBatis-Plus 多租户插件实现 SQL 自动拼装 tenant_id 条件。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>租户上下文在请求入口初始化，请求结束清理，避免线程复用导致上下文泄漏</li>
 *   <li>跨链路传递时（HTTP → MQ → 异步任务）需将 tenantId 显式序列化到消息属性</li>
 *   <li>Reactor 链路通过 contextWrite/deferContextual 传递，不可使用 ThreadLocal</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantContext {

    /** 租户ID，全局唯一，数据隔离核心标识，贯穿数据访问层 SQL 拼装 */
    private Long tenantId;

    /** 租户名称，用于日志展示与运营界面，非数据隔离字段 */
    private String tenantName;

    /** 租户编码，业务可读标识，对应租户对外简称，用于URL与配置引用 */
    private String tenantCode;
}
