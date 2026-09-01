package com.aegis.admin.web.resource;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.resource.KbSubscriptionService;
import com.aegis.admin.service.resource.KnowledgeBaseService;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.dto.resource.KnowledgeBaseVO;
import com.aegis.core.enums.resource.SubscriberType;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库用户侧 Controller。
 *
 * <p>面向终端用户的知识库市场查询、我的知识库、发布/提审等接口，
 * 路径前缀 {@code /api/resource/kb}，与管理侧 {@code /api/admin/resource/kb} 对应。
 *
 * @author wang.zhen
 * @see KnowledgeBaseService
 */
@Slf4j
@RestController
@RequestMapping("/api/resource/kb")
@RequiredArgsConstructor
public class KbUserController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KbSubscriptionService kbSubscriptionService;

    // ============ 知识库市场 ============

    /**
     * 知识库市场（仅展示已发布 PUBLISHED 的知识库，本租户内）。
     *
     * @param tenantId 租户ID
     * @param keyword  关键字搜索（可选）
     * @param page     页码
     * @param size     每页条数
     * @return 知识库分页列表
     */
    @GetMapping("/market")
    public Result<Page<KnowledgeBaseVO>> market(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        Page<KnowledgeBaseVO> result = knowledgeBaseService.page(tenantId, null, "market", keyword, page, size);
        return Result.success(result);
    }

    // ============ 我的知识库 ============

    /**
     * 我的知识库列表（仅返回当前用户创建的知识库）。
     *
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     * @param keyword  关键字搜索（可选）
     * @param page     页码
     * @param size     每页条数
     * @return 知识库分页列表
     */
    @GetMapping("/mine")
    public Result<Page<KnowledgeBaseVO>> mine(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        Page<KnowledgeBaseVO> result = knowledgeBaseService.page(tenantId, userId, "mine", keyword, page, size);
        return Result.success(result);
    }

    // ============ 知识库详情 ============

    /**
     * 知识库详情。
     *
     * @param id       知识库ID
     * @param tenantId 租户ID
     * @return 知识库详情
     */
    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> detail(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        KnowledgeBaseVO vo = knowledgeBaseService.getDetail(tenantId, id);
        return Result.success(vo);
    }

    // ============ 发布与提审 ============

    /**
     * 发布知识库（仅作者，DRAFT/REVIEWING → PUBLISHED）。
     *
     * @param id       知识库ID
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     */
    @PostMapping("/{id}/publish")
    @Auditable(operation = "PUBLISH_KB", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Map<String, Object>> publish(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);
        knowledgeBaseService.publish(tenantId, id, userId);
        Map<String, Object> result = new HashMap<>(2);
        result.put("published", true);
        return Result.success(result);
    }

    /**
     * 提交知识库审核（仅作者）。
     *
     * @param id       知识库ID
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     */
    @PostMapping("/{id}/submit-review")
    @Auditable(operation = "SUBMIT_KB_REVIEW", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Map<String, Object>> submitForReview(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);
        knowledgeBaseService.submitForReview(tenantId, id, userId);
        Map<String, Object> result = new HashMap<>(2);
        result.put("submitted", true);
        return Result.success(result);
    }

    // ============ 知识库订阅 ============

    /**
     * 订阅知识库（USER 类型）。
     *
     * <p>若当前用户是知识库的创建者，则直接返回订阅成功，无需创建订阅记录。</p>
     */
    @PostMapping("/{id}/subscribe")
    @Auditable(operation = "SUBSCRIBE_KB", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Map<String, Object>> subscribe(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);

        // 作者自动视为已订阅，直接返回成功
        KnowledgeBase kb = knowledgeBaseService.getKnowledgeBase(id);
        if (kb != null && kb.getAuthorUserId() != null && kb.getAuthorUserId().equals(userId)) {
            Map<String, Object> result = new HashMap<>(2);
            result.put("subscribed", true);
            result.put("isAuthor", true);
            return Result.success(result);
        }

        String kbCode = kb != null ? kb.getKbCode() : null;
        kbSubscriptionService.subscribe(tenantId, id, kbCode, SubscriberType.USER, userId);
        Map<String, Object> result = new HashMap<>(2);
        result.put("subscribed", true);
        return Result.success(result);
    }

    /**
     * 取消订阅知识库（USER 类型）。
     *
     * <p>若当前用户是知识库的创建者，则不允许取消订阅。</p>
     */
    @PostMapping("/{id}/unsubscribe")
    @Auditable(operation = "UNSUBSCRIBE_KB", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Map<String, Object>> unsubscribe(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);

        // 作者不允许取消订阅自己创建的知识库
        KnowledgeBase kb = knowledgeBaseService.getKnowledgeBase(id);
        if (kb != null && kb.getAuthorUserId() != null && kb.getAuthorUserId().equals(userId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "您是该知识库的创建者，无法取消订阅");
        }

        kbSubscriptionService.unsubscribe(tenantId, id, SubscriberType.USER, userId);
        Map<String, Object> result = new HashMap<>(2);
        result.put("subscribed", false);
        return Result.success(result);
    }

    /**
     * 查询当前用户的知识库订阅状态。
     */
    @GetMapping("/{id}/sub-status")
    public Result<Map<String, Object>> subStatus(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        boolean subscribed = kbSubscriptionService.isSubscribed(tenantId, id, SubscriberType.USER, userId);
        Map<String, Object> result = new HashMap<>(2);
        result.put("subscribed", subscribed);
        return Result.success(result);
    }

    /**
     * 批量查询当前用户对多个知识库的订阅状态。
     *
     * @param ids      知识库ID列表（请求体）
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     * @return 订阅状态映射 {kbId: subscribed}
     */
    @PostMapping("/batch-sub-status")
    public Result<Map<String, Object>> batchSubStatus(
            @RequestBody List<Long> ids,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);

        Set<Long> subscribedKbIds = kbSubscriptionService.batchQuerySubscribedKbIds(
                tenantId, SubscriberType.USER, userId, ids);

        Map<String, Object> result = new HashMap<>(2);
        // 返回 Map<kbIdStr, subscribed>
        Map<String, Boolean> statusMap = new HashMap<>();
        if (ids != null) {
            for (Long id : ids) {
                statusMap.put(String.valueOf(id), subscribedKbIds.contains(id));
            }
        }
        result.put("subscribedMap", statusMap);
        result.put("subscribedCount", subscribedKbIds.size());
        return Result.success(result);
    }

    /**
     * 查询当前用户已订阅的全部知识库ID集合。
     *
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     * @return 已订阅知识库ID列表
     */
    @GetMapping("/my-subscriptions")
    public Result<Map<String, Object>> mySubscriptions(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);

        Set<Long> subscribedIds = kbSubscriptionService.listAllSubscribedKbIds(
                tenantId, SubscriberType.USER, userId);

        Map<String, Object> result = new HashMap<>(2);
        result.put("subscribedIds", List.copyOf(subscribedIds));
        result.put("total", subscribedIds.size());
        return Result.success(result);
    }
}
