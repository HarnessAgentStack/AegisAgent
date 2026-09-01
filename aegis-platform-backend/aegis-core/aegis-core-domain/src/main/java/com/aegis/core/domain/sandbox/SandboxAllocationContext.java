package com.aegis.core.domain.sandbox;

import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 沙箱分配结果上下文。
 *
 * <p>封装沙箱分配操作的返回结果，包括分配的实例信息、状态和错误信息。
 * 由 Coordinator 返回给 Runtime 调用方。</p>
 *
 * <h3>分配结果类型</h3>
 * <ul>
 *   <li>SUCCESS — 成功分配已有 IDLE 实例</li>
 *   <li>CREATED — 紧急扩容创建新实例（IDLE 池为空且未达最大实例数）</li>
 *   <li>FAILED — 分配失败（配额不足、状态机冲突等）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxAllocationContext {

    /** 分配是否成功 */
    private boolean success;

    /** 分配的实例 ID */
    private String instanceId;

    /** 实例状态 */
    private SandboxInstanceStatus status;

    /** 是否为新创建的实例（紧急扩容） */
    private boolean newlyCreated;

    /** Pod 名称 */
    private String podName;

    /** 命名空间 */
    private String namespace;

    /** 槽位键 */
    private String slotKey;

    /** 错误码（失败时填写） */
    private String errorCode;

    /** 错误消息（失败时填写） */
    private String errorMessage;

    /**
     * 创建成功分配结果（复用已有实例）。
     *
     * @param instanceId 实例 ID
     * @param podName    Pod 名称
     * @param namespace  命名空间
     * @param slotKey    槽位键
     * @return 成功的分配上下文
     */
    public static SandboxAllocationContext success(String instanceId, String podName,
                                                    String namespace, String slotKey) {
        return SandboxAllocationContext.builder()
                .success(true)
                .instanceId(instanceId)
                .status(SandboxInstanceStatus.OCCUPIED)
                .newlyCreated(false)
                .podName(podName)
                .namespace(namespace)
                .slotKey(slotKey)
                .build();
    }

    /**
     * 创建紧急扩容结果（新创建实例）。
     *
     * @param instanceId 实例 ID
     * @param podName    Pod 名称
     * @param namespace  命名空间
     * @param slotKey    槽位键
     * @return 成功的分配上下文
     */
    public static SandboxAllocationContext created(String instanceId, String podName,
                                                    String namespace, String slotKey) {
        return SandboxAllocationContext.builder()
                .success(true)
                .instanceId(instanceId)
                .status(SandboxInstanceStatus.OCCUPIED)
                .newlyCreated(true)
                .podName(podName)
                .namespace(namespace)
                .slotKey(slotKey)
                .build();
    }

    /**
     * 创建失败结果。
     *
     * @param errorCode    错误码
     * @param errorMessage 错误消息
     * @return 失败的分配上下文
     */
    public static SandboxAllocationContext failure(String errorCode, String errorMessage) {
        return SandboxAllocationContext.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
