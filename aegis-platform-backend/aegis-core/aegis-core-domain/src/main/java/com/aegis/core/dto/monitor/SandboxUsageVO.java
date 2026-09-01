package com.aegis.core.dto.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 沙箱使用情况视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxUsageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 租户ID */
    private Long tenantId;

    /** 沙箱配额上限 */
    private Integer maxSandboxes;

    /** 当前占用数 */
    private Integer occupied;

    /** 空闲数 */
    private Integer idle;

    /** 异常数 */
    private Integer abnormal;

    /** 总数 */
    private Integer total;
}
