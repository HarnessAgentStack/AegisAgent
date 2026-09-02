package com.aegis.admin.service.resource.handler;

import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.resource.ResourceType;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * 资源审核 SPI。每种资源类型各自实现一个 Handler，由 {@code ReviewProcessEngine}
 * 通过 {@code Map<ResourceType, ResourceReviewHandler>}（Spring 注入 List 后转 Map）
 * 按 {@link #supportedType()} 分发，消除 if/else-if 硬编码分支。
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>{@link #loadResourceInfo}：提交审核时建档（校验存在 + 状态允许提交 + 取名称/版本/安全级别/作者）</li>
 *   <li>{@link #updateLifeStatus}：通过/驳回/重交时更新资源 lifeStatus、版本、发布时间</li>
 *   <li>{@link #rejectStatus}：驳回时资源应回退到的生命周期状态</li>
 * </ul>
 *
 * @author wang.zhen
 * @see com.aegis.admin.service.resource.ReviewProcessEngine
 */
public interface ResourceReviewHandler {

    /**
     * 本实现支持的资源类型，作为 SPI 分发键。
     */
    ResourceType supportedType();

    /**
     * 加载资源审核建档信息：校验资源存在且状态允许提交，返回名称/版本/安全级别/作者。
     *
     * @param resourceId 资源ID
     * @return 资源审核建档信息
     * @throws com.aegis.core.common.error.BusinessException 资源不存在或当前状态不可提交审核
     */
    ResourceReviewInfo loadResourceInfo(Long resourceId);

    /**
     * 更新资源生命周期状态；发布时递增版本号并可选写入发布时间。
     *
     * @param resourceId    资源ID
     * @param lifeStatus    目标状态
     * @param newVersion    新版本号（null 时由实现按状态递增）
     * @param publishedTime 发布时间（null 时不更新）
     */
    void updateLifeStatus(Long resourceId, AgentLifeStatus lifeStatus,
                          String newVersion, LocalDateTime publishedTime);

    /**
     * 审核驳回时资源应回退到的生命周期状态。
     */
    AgentLifeStatus rejectStatus();

    /**
     * 版本号递增工具（各 Handler 复用同一份语义）：
     * <ul>
     *   <li>草稿版本 0.0.x → 首次发布 1.0.0</li>
     *   <li>已发布版本 x.y.z → 重新发布 x.(y+1).0</li>
     *   <li>非发布目标状态保持原版本</li>
     * </ul>
     */
    static String bumpVersion(String currentVersion, AgentLifeStatus targetStatus) {
        if (targetStatus != AgentLifeStatus.PUBLISHED) {
            return currentVersion;
        }
        if (currentVersion == null || currentVersion.isEmpty()) {
            return "1.0.0";
        }
        try {
            String[] parts = currentVersion.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major + "." + (minor + 1) + ".0";
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(ResourceReviewHandler.class)
                    .warn("版本号格式非法，回退原值: {}", currentVersion);
        }
        return currentVersion;
    }
}
