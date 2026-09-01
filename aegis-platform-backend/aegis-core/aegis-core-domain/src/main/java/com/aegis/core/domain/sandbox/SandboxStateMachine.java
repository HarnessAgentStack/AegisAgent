package com.aegis.core.domain.sandbox;

import com.aegis.core.common.error.sandbox.SandboxException.SandboxStateException;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 沙箱实例状态机。
 *
 * <p>定义 {@link SandboxInstanceStatus} 之间的合法转换规则，确保所有状态变更操作
 * （如分配、释放、回收、销毁）遵循严格的业务约束。
 *
 * <h3>状态转换规则表</h3>
 * <pre>
 * FROM          → TO              | 触发场景                  | 操作
 * ─────────────────────────────────────────────────────────────────────
 * IDLE          → OCCUPIED        | runtime 分配干净 IDLE     | allocateSlot
 * OCCUPIED      → IDLE            | runtime 释放（脏 IDLE）   | releaseSlot
 * IDLE          → ABNORMAL        | admin 检测异常            | markAbnormal
 * OCCUPIED      → ABNORMAL        | 跨租户复用拦截/探活失败   | markAbnormal
 * ABNORMAL      → IDLE            | admin 修复（重建 Pod）    | repairAbnormal
 * ABNORMAL      → DESTROYED       | admin 销毁异常实例        | destroyInstance
 * IDLE          → DESTROYED       | admin 缩容销毁            | destroyInstance
 * OCCUPIED      → DESTROYED       | 强制回收（紧急销毁）      | forceDestroy
 * IDLE          → RESIDENT        | A3: 系统智能体常驻绑定    | allocateSlot/预绑定
 * RESIDENT      → ABNORMAL        | A3: 常驻实例探活失败      | healthCheck
 * RESIDENT      → DESTROYED       | A3: 智能体停用解除绑定    | unbindResident
 * ABNORMAL      → RESIDENT        | A3: 常驻实例修复恢复绑定  | repairResident
 * ─────────────────────────────────────────────────────────────────────
 * </pre>
 *
 * <h3>关键约束</h3>
 * <ul>
 *   <li>{@code DESTROYED} 为终态，不可再转换到任何状态</li>
 *   <li>{@code OCCUPIED → IDLE} 仅允许 runtime 释放触发，标记脏 IDLE（initialized=0）</li>
 *   <li>{@code ABNORMAL → IDLE} 仅允许 admin 修复触发，重建 Pod 后标记干净 IDLE</li>
 *   <li>{@code IDLE} 内部的 initialized 字段不影响状态机转换（属于子状态）</li>
 *   <li>{@code RESIDENT}（A3）不参与动态分配与回收：无 RESIDENT→IDLE 转换，
 *       释放被 releaseSlot 拦截；仅探活失败转 ABNORMAL 后由 admin 修复恢复绑定</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 检查转换是否合法
 * SandboxStateMachine.assertCanTransit(SandboxInstanceStatus.IDLE, SandboxInstanceStatus.OCCUPIED);
 *
 * // 在 Service 中更新状态前校验
 * SandboxStateMachine.assertCanTransit(instance.getStatus(), targetStatus);
 * sandboxInstanceService.updateStatus(instanceId, targetStatus);
 * }</pre>
 *
 * @author wang.zhen
 * @see SandboxInstanceStatus
 * @see SandboxInstance
 */
public final class SandboxStateMachine {

    /**
     * 状态转换规则表：FROM → 允许的 TO 集合。
     */
    private static final Map<SandboxInstanceStatus, Set<SandboxInstanceStatus>> TRANSITIONS;

    static {
        Map<SandboxInstanceStatus, Set<SandboxInstanceStatus>> map =
                new EnumMap<>(SandboxInstanceStatus.class);

        // IDLE → OCCUPIED（runtime 分配）
        // IDLE → ABNORMAL（admin 检测异常）
        // IDLE → DESTROYED（admin 缩容销毁）
        // IDLE → RESIDENT（A3: 系统智能体常驻绑定）
        map.put(SandboxInstanceStatus.IDLE, EnumSet.of(
                SandboxInstanceStatus.OCCUPIED,
                SandboxInstanceStatus.ABNORMAL,
                SandboxInstanceStatus.DESTROYED,
                SandboxInstanceStatus.RESIDENT
        ));

        // OCCUPIED → IDLE（runtime 释放，标记脏 IDLE）
        // OCCUPIED → ABNORMAL（跨租户拦截/探活失败）
        // OCCUPIED → DESTROYED（强制回收）
        map.put(SandboxInstanceStatus.OCCUPIED, EnumSet.of(
                SandboxInstanceStatus.IDLE,
                SandboxInstanceStatus.ABNORMAL,
                SandboxInstanceStatus.DESTROYED
        ));

        // A3 RESIDENT 常驻绑定：
        // RESIDENT → ABNORMAL（探活失败）
        // RESIDENT → DESTROYED（智能体停用解除绑定）
        // 无 RESIDENT → IDLE：常驻实例不回收、不参与动态分配
        map.put(SandboxInstanceStatus.RESIDENT, EnumSet.of(
                SandboxInstanceStatus.ABNORMAL,
                SandboxInstanceStatus.DESTROYED
        ));

        // ABNORMAL → IDLE（admin 修复，重建 Pod）
        // ABNORMAL → DESTROYED（admin 销毁）
        // ABNORMAL → RESIDENT（A3: 常驻实例修复后恢复绑定）
        map.put(SandboxInstanceStatus.ABNORMAL, EnumSet.of(
                SandboxInstanceStatus.IDLE,
                SandboxInstanceStatus.DESTROYED,
                SandboxInstanceStatus.RESIDENT
        ));

        // DESTROYED：终态，不可转换
        map.put(SandboxInstanceStatus.DESTROYED, EnumSet.noneOf(SandboxInstanceStatus.class));

        TRANSITIONS = Collections.unmodifiableMap(map);
    }

    private SandboxStateMachine() {
        // 工具类禁止实例化
    }

    /**
     * 检查从当前状态到目标状态的转换是否合法。
     *
     * @param current 当前状态
     * @param target  目标状态
     * @return {@code true} 表示合法转换
     */
    public static boolean canTransit(SandboxInstanceStatus current,
                                     SandboxInstanceStatus target) {
        if (current == null || target == null) {
            return false;
        }
        Set<SandboxInstanceStatus> allowed = TRANSITIONS.get(current);
        return allowed != null && allowed.contains(target);
    }

    /**
     * 断言状态转换合法，不合法则抛出 {@link SandboxStateException}。
     *
     * <p>所有沙箱实例状态变更操作（如 updateStatus、markOccupied、markIdleDirty）
     * 必须在调用数据库更新前执行此校验。</p>
     *
     * @param current 当前状态
     * @param target  目标状态
     * @throws SandboxStateException 当转换非法时抛出
     */
    public static void assertCanTransit(SandboxInstanceStatus current,
                                        SandboxInstanceStatus target) {
        if (current == null) {
            throw new SandboxStateException("null", target.name());
        }
        if (target == null) {
            throw new SandboxStateException(current.name(), "null");
        }
        if (!canTransit(current, target)) {
            // 终态特殊处理
            if (current == SandboxInstanceStatus.DESTROYED) {
                throw new SandboxStateException(current.name(), true);
            }
            throw new SandboxStateException(current.name(), target.name());
        }
    }

    /**
     * 获取指定状态允许的所有目标状态集合。
     *
     * @param current 当前状态
     * @return 允许的目标状态集合（不可变），null 状态返回空集合
     */
    public static Set<SandboxInstanceStatus> getAllowedTransitions(
            SandboxInstanceStatus current) {
        if (current == null) {
            return Collections.emptySet();
        }
        Set<SandboxInstanceStatus> allowed = TRANSITIONS.get(current);
        return allowed != null ? Collections.unmodifiableSet(allowed) : Collections.emptySet();
    }

    /**
     * 判断指定状态是否为终态（DESTROYED）。
     *
     * @param status 状态
     * @return {@code true} 表示终态
     */
    public static boolean isTerminal(SandboxInstanceStatus status) {
        return status == SandboxInstanceStatus.DESTROYED;
    }
}
