package com.aegis.admin.web.resource;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.resource.SkillManageService;
import com.aegis.admin.service.resource.SkillSubscriptionService;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.dto.resource.SkillVO;
import com.aegis.core.enums.resource.SubscriberType;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能用户侧 Controller。
 *
 * <p>面向终端用户的技能市场、订阅等接口，路径前缀
 * {@code /api/resource/skill}，与管理侧 {@code /api/admin/resource/skill} 对应。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/resource/skill")
@RequiredArgsConstructor
public class SkillUserController {

    private final SkillManageService skillManageService;
    private final SkillSubscriptionService subscriptionService;

    /**
     * 市场分页列表（已发布 + 租户隔离 + 可见性过滤）。
     */
    @GetMapping("/market")
    public Result<Page<SkillVO>> market(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        Page<SkillVO> result = skillManageService.page(tenantId, "market", keyword, type, page, size);
        return Result.success(result);
    }

    /**
     * 我的技能列表（仅展示自己创建的技能）。
     */
    @GetMapping("/mine")
    public Result<Page<SkillVO>> mine(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        Page<SkillVO> result = skillManageService.page(tenantId, "mine", userId, keyword, type, page, size);
        return Result.success(result);
    }

    /**
     * 订阅技能（USER 类型）。
     *
     * <p>userId 为空时拒绝请求（禁止匿名订阅）；
     * 发布状态/跨租户/自订阅校验由 {@link SkillSubscriptionService#subscribe} 统一执行。
     */
    @PostMapping("/{id}/subscribe")
    @Auditable(operation = "SUBSCRIBE_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Map<String, Object>> subscribe(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "订阅失败：缺少用户身份（X-User-Id）");
        }
        String skillCode = skillManageService.getSkillCodeById(id);
        subscriptionService.subscribe(tenantId, id, skillCode, SubscriberType.USER, userId, null);
        Map<String, Object> result = new HashMap<>();
        result.put("subscribed", true);
        return Result.success(result);
    }

    /**
     * 取消订阅（USER 类型）。
     *
     * <p>userId 为空时拒绝请求。
     */
    @PostMapping("/{id}/unsubscribe")
    @Auditable(operation = "UNSUBSCRIBE_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Map<String, Object>> unsubscribe(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "取消订阅失败：缺少用户身份（X-User-Id）");
        }
        subscriptionService.unsubscribe(tenantId, id, SubscriberType.USER, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("subscribed", false);
        return Result.success(result);
    }

    /**
     * 查询当前用户的订阅状态。
     */
    @GetMapping("/{id}/sub-status")
    public Result<Map<String, Object>> subStatus(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        boolean subscribed = subscriptionService.isSubscribed(tenantId, id, SubscriberType.USER, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("subscribed", subscribed);
        return Result.success(result);
    }

    /**
     * 批量查询订阅状态。
     *
     * <p>请求体为技能ID列表，返回 { skillId -> isSubscribed } 映射，
     * 单次 SQL 完成整页技能的订阅状态标记。
     */
    @PostMapping("/subscription/batch-status")
    public Result<Map<String, Boolean>> batchSubStatus(
            @RequestBody(required = false) List<Long> skillIds,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        if (skillIds == null || skillIds.isEmpty()) {
            return Result.success(new HashMap<>());
        }
        // 未登录：全部返回 false（未订阅）
        if (userId == null) {
            Map<String, Boolean> anonymous = new HashMap<>();
            skillIds.forEach(id -> anonymous.put(String.valueOf(id), false));
            return Result.success(anonymous);
        }
        Set<Long> subscribedIds = subscriptionService.batchQuerySubscribedSkillIds(
                tenantId, SubscriberType.USER, userId, skillIds);
        Map<String, Boolean> result = skillIds.stream()
                .collect(Collectors.toMap(
                        String::valueOf,
                        subscribedIds::contains,
                        (a, b) -> a));
        return Result.success(result);
    }

    /**
     * 我订阅的技能列表（基于真实订阅关系，USER 订阅的 PUBLISHED 技能）。
     */
    @GetMapping("/subscribed")
    public Result<Page<SkillVO>> subscribed(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        Page<SkillVO> result = skillManageService.page(tenantId, "subscribed", userId, keyword, null, page, size);
        return Result.success(result);
    }
}