package com.aegis.core.dto.model;

import com.aegis.core.enums.model.RateLimitAction;
import com.aegis.core.enums.model.RateLimitScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 模型限流策略保存请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRateLimitSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 限流策略ID（存在时更新，不存在时新增） */
    private Long id;

    /** 限流作用域：PLATFORM / DEPT / USER */
    private RateLimitScope scope;

    /** 限流对象ID */
    private Long scopeTargetId;

    /** 轻量模型QPS限制 */
    private Integer lightQps;

    /** 标准模型QPS限制 */
    private Integer standardQps;

    /** 强力模型QPS限制 */
    private Integer strongQps;

    /** 总QPS限制 */
    private Integer totalQps;

    /** 已用QPS */
    private Integer usedQps;

    /** 超限动作：ALERT / LIMIT / PASS */
    private RateLimitAction action;
}
