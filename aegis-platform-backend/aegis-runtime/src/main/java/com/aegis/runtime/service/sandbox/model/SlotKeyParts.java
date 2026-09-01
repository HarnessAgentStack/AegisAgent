package com.aegis.runtime.service.sandbox.model;

import io.agentscope.harness.agent.IsolationScope;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * slotKey 解析结果。
 *
 * <p>由 {@link SlotKeyParser#parse(String, IsolationScope)} 解析 slotKey 得到，
 * 携带 tenantId / userId / agentId 等业务主键，供沙箱分配流程使用。
 *
 * @author wang.zhen
 */
@Data
@AllArgsConstructor
public class SlotKeyParts {

    /** 租户 ID（所有 scope 都有） */
    private final Long tenantId;

    /** 用户 ID（USER scope 有值，其他 scope 为 null） */
    private final Long userId;

    /** 智能体 ID（AGENT scope 有值，其他 scope 为 null） */
    private final Long agentId;

    /** 隔离作用域 */
    private final IsolationScope isolationScope;

    /** 原始 slotKey */
    private final String slotKey;
}