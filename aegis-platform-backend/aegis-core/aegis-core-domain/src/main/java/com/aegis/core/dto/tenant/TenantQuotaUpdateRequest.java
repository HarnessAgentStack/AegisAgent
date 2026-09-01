package com.aegis.core.dto.tenant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import com.aegis.core.domain.tenant.Tenant;

/**
 * 租户配额更新请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantQuotaUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 租户ID，关联 Tenant 主键 */
    private Long tenantId;

    /** 智能体数量上限 */
    private Integer maxAgents;

    /** 资源数量上限 */
    private Integer maxResources;

    /** 最大并发会话数 */
    private Integer maxConcurrentSessions;

    /** 每日 Token 上限 */
    private Long maxTokenPerDay;

    /** 每月 Token 上限 */
    private Long maxTokenPerMonth;

    /** 沙箱实例数上限 */
    private Integer maxSandboxes;

    /** 存储容量上限（GB） */
    private Integer maxStorageGb;
}
