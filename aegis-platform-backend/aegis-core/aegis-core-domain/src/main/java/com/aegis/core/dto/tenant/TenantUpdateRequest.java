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
 * 租户更新请求。
 *
 * <p>排除 id 与 createTime，所有字段可选用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 租户编码，全局唯一，创建后不可修改 */
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

    /** 有效期截止时间，null 表示长期有效 */
    private LocalDateTime expireTime;

    /** 备注 */
    private String remark;
}
