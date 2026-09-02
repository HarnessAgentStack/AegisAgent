package com.aegis.admin.service.resource.handler;

/**
 * 资源审核建档信息载体。由各 {@link ResourceReviewHandler} 在 {@code loadResourceInfo} 时产出，
 * 供 {@code ReviewProcessEngine} 写入审核单 {@code res_review} 记录。
 *
 * <p>{@code securityLevel} 使用 {@code Integer}（可为 null）以保留原 Engine 下传语义——
 * 资源未配置安全级别时不下发级别值。
 *
 * @author wang.zhen
 */
public record ResourceReviewInfo(String resourceName,
                                 String resourceVersion,
                                 Integer securityLevel,
                                 Long authorUserId,
                                 Long authorDeptId) {
}
