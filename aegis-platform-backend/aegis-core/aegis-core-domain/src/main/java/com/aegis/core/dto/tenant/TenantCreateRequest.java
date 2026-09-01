package com.aegis.core.dto.tenant;

import com.aegis.core.enums.tenant.TenantType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 租户创建请求。
 *
 * <p>由管理平面接收，平台管理员创建租户时提交。创建后初始化默认配额与预置平台角色。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 租户编码，全局唯一，创建后不可修改 */
    private String tenantCode;

    /** 租户名称 */
    private String tenantName;

    /** 租户类型：HQ（集团总部）/ SUBSIDIARY（子公司）/ DIVISION（事业部），决定默认配额档位 */
    private TenantType tenantType;

    /** 联系人姓名 */
    private String contactName;

    /** 联系人电话 */
    private String contactPhone;

    /** 有效期截止时间，null 表示长期有效 */
    private LocalDateTime expireTime;

    /** 备注 */
    private String remark;
}
