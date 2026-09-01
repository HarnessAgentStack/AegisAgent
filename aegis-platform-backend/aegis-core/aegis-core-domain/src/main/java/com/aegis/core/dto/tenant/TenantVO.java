package com.aegis.core.dto.tenant;

import com.aegis.core.enums.tenant.TenantStatus;
import com.aegis.core.enums.tenant.TenantType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 租户视图对象。
 *
 * <p>包含所有展示字段，不含敏感信息。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 租户ID */
    private Long id;

    /** 租户编码，全局唯一 */
    private String tenantCode;

    /** 租户名称 */
    private String tenantName;

    /** 租户类型：HQ / SUBSIDIARY / DIVISION */
    private TenantType tenantType;

    /** 租户状态：NORMAL / FROZEN */
    private TenantStatus status;

    /** 联系人姓名 */
    private String contactName;

    /** 联系人电话 */
    private String contactPhone;

    /** 有效期截止时间 */
    private LocalDateTime expireTime;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
