package com.aegis.runtime.web;

import com.aegis.core.common.tenant.TenantContextScope;
import com.aegis.core.common.web.Result;
import com.aegis.core.dto.chat.AvailableResourcesResponse;
import com.aegis.core.dto.chat.AvailableResourcesResponse.KbResourceItem;
import com.aegis.core.dto.chat.AvailableResourcesResponse.McpResourceItem;
import com.aegis.core.dto.chat.SessionResourcesRef;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.domain.resource.McpService;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.runtime.service.agent.ResourceQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 智能体资源控制器。
 * 提供会话级资源引用的查询和管理接口。
 *
 * <p>核心能力：
 * <ul>
 *   <li>查询指定智能体可用的知识库和 MCP 服务列表</li>
 *   <li>验证会话级资源引用的有效性</li>
 *   <li>支持智能体绑定资源 + 用户订阅资源的合并查询</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/agent/resource")
@RequiredArgsConstructor
public class AgentResourceController {

    private final ResourceQueryService resourceQueryService;

    /**
     * 标记知识库资源项的可选性。
     *
     * <p>原基于策略引擎的等级门控已随策略引擎下线，当前所有知识库默认可选，
     * 返回与输入等量的资源项列表（不修改 selectable 标记）。入参 agentId/tenantId
     * 保留以兼容调用方签名。
     *
     * @param kbItems  候选知识库资源项
     * @param agentId  智能体 ID（保留以兼容调用方签名）
     * @param tenantId 租户 ID（保留以兼容调用方签名）
     * @return 资源项列表（数量不变，全部可选）
     */
    private List<KbResourceItem> markKbItemsByResourceLevel(List<KbResourceItem> kbItems,
                                                            Long agentId, Long tenantId) {
        if (kbItems == null || kbItems.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(kbItems);
    }

    /**
     * 查询指定智能体可用的资源列表。
     *
     * <p>资源来源（按优先级）：
     * <ol>
     *   <li>智能体绑定的资源（agent_binding 表中 resourceType=KNOWLEDGE_BASE/MCP_SERVICE）</li>
     *   <li>用户订阅的知识库（res_kb_subscription 表）</li>
     *   <li>用户创建的知识库（res_knowledge_base.author_user_id，含 DRAFT/REVIEWING，仅本人可见）</li>
     *   <li>用户订阅的 MCP 服务（res_mcp_subscription 表）</li>
     * </ol>
     *
     * <p>过滤规则：
     * <ul>
     *   <li>知识库：PUBLISHED 全量可见；DRAFT/REVIEWING 仅创建者本人可见（带未发布标识）；ARCHIVED 不可见</li>
     *   <li>MCP服务：lifeStatus=PUBLISHED 且 status=ACTIVE 的已启用服务</li>
     * </ul>
     *
     * @param agentId  智能体ID（可选，为空时返回市场级推荐资源）
     * @param tenantId 租户ID（从Header获取）
     * @param userId   用户ID（从Header获取）
     * @return 可用资源响应
     */
    @GetMapping("/available")
    public Result<AvailableResourcesResponse> available(
            @RequestParam(required = false) Long agentId,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {

        // 边界式租户作用域（P1-1）：WebFlux 阻塞式 controller 运行在 boundedElastic 线程，
        // 网关过滤器的绑定不跨线程传递，需在执行线程上显式绑定；线程归池前必须清空。
        try (var ignore = TenantContextScope.bound(tenantId)) {
            return doAvailable(agentId, tenantId, userId);
        }
    }

    /**
     * {@link #available} 的实现体（调用方已绑定租户作用域）。
     */
    private Result<AvailableResourcesResponse> doAvailable(Long agentId, Long tenantId, Long userId) {
        log.info("查询可用资源: agentId={}, tenantId={}, userId={}", agentId, tenantId, userId);

        // 无 agentId 时，返回市场级推荐资源（已发布且启用的）
        if (agentId == null) {
            return buildMarketResources(tenantId);
        }

        // 1. 查询智能体定义，验证存在性
        AgentDef agentDef = resourceQueryService.findAgentDefById(agentId);
        if (agentDef == null) {
            log.warn("智能体不存在，返回市场级推荐: agentId={}", agentId);
            return buildMarketResources(tenantId);
        }

        try {
            // 2. 查询智能体绑定的知识库
            List<Long> boundKbIds = resourceQueryService.listBoundKbIds(agentId);

            // 3. 查询用户订阅的知识库
            List<Long> subscribedKbIds = (tenantId != null && userId != null)
                    ? resourceQueryService.listUserSubscribedKbIds(tenantId, userId)
                    : Collections.emptyList();

            // 3.1 查询用户创建的知识库（含草稿，作者对自建库天然可见）
            List<Long> ownedKbIds = (tenantId != null && userId != null)
                    ? resourceQueryService.listUserOwnedKbIds(tenantId, userId)
                    : Collections.emptyList();

            // 4. 查询智能体绑定的 MCP 服务
            List<Long> boundMcpIds = resourceQueryService.listBoundMcpIds(agentId);

            // 5. 查询用户订阅的 MCP 服务
            List<Long> subscribedMcpIds = (tenantId != null && userId != null)
                    ? resourceQueryService.listUserSubscribedMcpIds(tenantId, userId)
                    : Collections.emptyList();

            // 6. 合并知识库 ID（绑定的 + 订阅的 + 自建的，去重）
            Set<Long> allKbIds = Stream.of(boundKbIds, subscribedKbIds, ownedKbIds)
                    .flatMap(List::stream)
                    .collect(Collectors.toSet());

            // 7. 合并 MCP 服务 ID（绑定的 + 订阅的，去重）
            Set<Long> allMcpIds = Stream.concat(boundMcpIds.stream(), subscribedMcpIds.stream())
                    .collect(Collectors.toSet());

            // 如果智能体没有绑定且用户没有订阅也没有自建，则返回空列表（有智能体场景下，仅展示绑定/订阅/自建资源，不混入市场推荐）
            if (allKbIds.isEmpty() && allMcpIds.isEmpty()) {
                log.info("智能体无绑定且用户无订阅无自建，返回空资源列表: agentId={}", agentId);
                return Result.success(AvailableResourcesResponse.builder()
                        .kbs(Collections.emptyList())
                        .mcps(Collections.emptyList())
                        .build());
            }

            // 8. 批量查询知识库详情并过滤可引用的（已发布全量 + 自建草稿），再按资源安全等级标记可选性（L3/L4 不可选+原因）
            List<KbResourceItem> kbItems = markKbItemsByResourceLevel(
                    buildKbItems(allKbIds, subscribedKbIds, userId), agentId, tenantId);

            // 9. 批量查询 MCP 服务详情并过滤已发布且启用的
            List<McpResourceItem> mcpItems = buildMcpItems(allMcpIds, boundMcpIds);

            AvailableResourcesResponse response = AvailableResourcesResponse.builder()
                    .kbs(kbItems)
                    .mcps(mcpItems)
                    .build();

            log.info("查询可用资源完成: agentId={}, kbCount={}, mcpCount={}",
                    agentId, kbItems.size(), mcpItems.size());

            return Result.success(response);

        } catch (Exception e) {
            log.error("查询可用资源异常: agentId={}", agentId, e);
            // 异常降级：返回空列表，不阻塞对话
            return Result.success(AvailableResourcesResponse.builder()
                    .kbs(Collections.emptyList())
                    .mcps(Collections.emptyList())
                    .build());
        }
    }

    /**
     * 验证会话级资源引用的有效性。
     *
     * <p>验证规则：
     * <ul>
     *   <li>知识库：PUBLISHED 任何用户可引用；DRAFT/REVIEWING 仅创建者本人可引用；ARCHIVED 不可引用</li>
     *   <li>MCP服务：ID 必须存在且已发布且启用</li>
     *   <li>返回验证后的有效资源列表 + 无效资源 ID 列表（不静默丢弃）</li>
     * </ul>
     *
     * @param agentId   智能体ID（可选）
     * @param resources 资源引用（kbIds + mcpIds）
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @return 验证结果（仅包含有效的资源 + 无效 ID 列表）
     */
    @PostMapping("/validate")
    public Result<AvailableResourcesResponse> validate(
            @RequestParam(required = false) Long agentId,
            @RequestBody(required = false) SessionResourcesRef resources,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {

        // 边界式租户作用域（P1-1）：同 available，boundedElastic 线程归池前必须清空
        try (var ignore = TenantContextScope.bound(tenantId)) {
            return doValidate(agentId, resources, userId);
        }
    }

    /**
     * {@link #validate} 的实现体（调用方已绑定租户作用域）。
     */
    private Result<AvailableResourcesResponse> doValidate(
            Long agentId, SessionResourcesRef resources, Long userId) {
        log.info("验证资源引用: agentId={}, kbIds={}, mcpIds={}",
                agentId,
                resources != null ? resources.getKbIds() : null,
                resources != null ? resources.getMcpIds() : null);

        if (resources == null) {
            return Result.success(AvailableResourcesResponse.builder()
                    .kbs(Collections.emptyList())
                    .mcps(Collections.emptyList())
                    .build());
        }

        try {
            // 1. 验证知识库引用（已发布 + 用户自建草稿/审核中）
            List<Long> kbIds = resources.getKbIds();
            List<KbResourceItem> validKbItems = Collections.emptyList();
            List<Long> invalidKbIds = Collections.emptyList();
            if (kbIds != null && !kbIds.isEmpty()) {
                List<KnowledgeBase> kbs = resourceQueryService
                        .findReferenceableKnowledgeBasesByIds(Set.copyOf(kbIds), userId);
                validKbItems = kbs.stream()
                        .map(kb -> toKbResourceItem(kb, userId))
                        .collect(Collectors.toList());
                Set<Long> validIds = kbs.stream().map(KnowledgeBase::getId).collect(Collectors.toSet());
                invalidKbIds = kbIds.stream()
                        .filter(id -> id != null && !validIds.contains(id))
                        .collect(Collectors.toList());
            }

            // 2. 验证 MCP 服务引用
            List<Long> mcpIds = resources.getMcpIds();
            List<McpResourceItem> validMcpItems = Collections.emptyList();
            List<Long> invalidMcpIds = Collections.emptyList();
            if (mcpIds != null && !mcpIds.isEmpty()) {
                List<McpService> mcps = resourceQueryService.findActiveMcpServicesByIds(Set.copyOf(mcpIds));
                validMcpItems = mcps.stream()
                        .map(this::toMcpResourceItem)
                        .collect(Collectors.toList());
                Set<Long> validIds = mcps.stream().map(McpService::getId).collect(Collectors.toSet());
                invalidMcpIds = mcpIds.stream()
                        .filter(id -> id != null && !validIds.contains(id))
                        .collect(Collectors.toList());
            }

            if (!invalidKbIds.isEmpty() || !invalidMcpIds.isEmpty()) {
                log.warn("资源引用校验发现无效ID: invalidKbIds={}, invalidMcpIds={}, userId={}",
                        invalidKbIds, invalidMcpIds, userId);
            }

            AvailableResourcesResponse response = AvailableResourcesResponse.builder()
                    .kbs(validKbItems)
                    .mcps(validMcpItems)
                    .invalidKbIds(invalidKbIds)
                    .invalidMcpIds(invalidMcpIds)
                    .build();

            log.info("验证资源完成: agentId={}, validKbCount={}, validMcpCount={}, invalidKbCount={}, invalidMcpCount={}",
                    agentId, validKbItems.size(), validMcpItems.size(),
                    invalidKbIds.size(), invalidMcpIds.size());

            return Result.success(response);

        } catch (Exception e) {
            log.error("验证资源引用异常: agentId={}", agentId, e);
            return Result.success(AvailableResourcesResponse.builder()
                    .kbs(Collections.emptyList())
                    .mcps(Collections.emptyList())
                    .build());
        }
    }

    // ============ 私有辅助方法 ============

    /**
     * 构建市场级推荐资源（无 agentId 时使用）。
     */
    private Result<AvailableResourcesResponse> buildMarketResources(Long tenantId) {
        List<KbResourceItem> kbItems = Collections.emptyList();
        List<McpResourceItem> mcpItems = Collections.emptyList();

        try {
            // 推荐最多 20 个已发布的知识库
            List<KnowledgeBase> recommendedKbs = resourceQueryService.listPublishedKnowledgeBases(tenantId, 20);
            kbItems = recommendedKbs.stream()
                    .map(this::toKbResourceItem)
                    .collect(Collectors.toList());

            // 推荐最多 20 个已发布且启用的 MCP 服务
            List<McpService> recommendedMcps = resourceQueryService.listPublishedActiveMcpServices(20);
            mcpItems = recommendedMcps.stream()
                    .map(this::toMcpResourceItem)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("构建市场级推荐资源异常", e);
        }

        log.info("市场级推荐资源: kbCount={}, mcpCount={}", kbItems.size(), mcpItems.size());

        return Result.success(AvailableResourcesResponse.builder()
                .kbs(kbItems)
                .mcps(mcpItems)
                .totalKbCount(kbItems.size())
                .totalMcpCount(mcpItems.size())
                .build());
    }

    /**
     * 构建知识库资源项列表。
     *
     * <p>过滤规则：PUBLISHED 全量保留（绑定/订阅来源）；
     * DRAFT/REVIEWING 仅创建者本人保留（自建来源，带未发布标识）；
     * ARCHIVED 一律排除。
     */
    private List<KbResourceItem> buildKbItems(Set<Long> kbIds, List<Long> subscribedKbIds, Long userId) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBase> knowledgeBases = resourceQueryService.findKnowledgeBasesByIds(kbIds);
        return knowledgeBases.stream()
                .filter(kb -> isKbSelectableForUser(kb, userId))
                .map(kb -> {
                    boolean subscribed = subscribedKbIds.contains(kb.getId());
                    boolean owned = userId != null && userId.equals(kb.getAuthorUserId());
                    return KbResourceItem.builder()
                            .id(kb.getId())
                            .name(kb.getKbName())
                            .description(kb.getDescription())
                            .securityLevel(kb.getSecurityLevel() != null ? kb.getSecurityLevel().name() : null)
                            .documentCount(kb.getDocCount() != null ? kb.getDocCount() : 0)
                            .subscribed(subscribed)
                            .lifeStatus(kb.getLifeStatus() != null ? kb.getLifeStatus().name() : null)
                            .owned(owned)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 判断知识库是否可进入当前用户的资源面板。
     */
    private boolean isKbSelectableForUser(KnowledgeBase kb, Long userId) {
        if (kb.getLifeStatus() == AgentLifeStatus.PUBLISHED) {
            return true;
        }
        if (kb.getLifeStatus() == AgentLifeStatus.ARCHIVED) {
            return false;
        }
        return userId != null && userId.equals(kb.getAuthorUserId());
    }

    /**
     * 构建 MCP 服务资源项列表。
     */
    private List<McpResourceItem> buildMcpItems(Set<Long> mcpIds, List<Long> boundMcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpService> mcpServices = resourceQueryService.findMcpServicesByIds(mcpIds);
        return mcpServices.stream()
                .filter(mcp -> mcp.getLifeStatus() == AgentLifeStatus.PUBLISHED
                        && mcp.getStatus() == ProviderStatus.ACTIVE)
                .map(mcp -> {
                    boolean subscribed = boundMcpIds.contains(mcp.getId());
                    return McpResourceItem.builder()
                            .id(mcp.getId())
                            .name(mcp.getMcpName())
                            .description(mcp.getDescription())
                            .securityLevel(mcp.getSecurityLevel() != null ? mcp.getSecurityLevel().name() : null)
                            .toolCount(mcp.getToolCount() != null ? mcp.getToolCount() : 0)
                            .subscribed(subscribed)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * KnowledgeBase → KbResourceItem 转换（市场推荐路径，均为 PUBLISHED）。
     */
    private KbResourceItem toKbResourceItem(KnowledgeBase kb) {
        return toKbResourceItem(kb, null);
    }

    /**
     * KnowledgeBase → KbResourceItem 转换。
     *
     * @param kb     知识库实体
     * @param userId 当前用户ID（判定自建标识，null 时视为非创建者）
     */
    private KbResourceItem toKbResourceItem(KnowledgeBase kb, Long userId) {
        return KbResourceItem.builder()
                .id(kb.getId())
                .name(kb.getKbName())
                .description(kb.getDescription())
                .securityLevel(kb.getSecurityLevel() != null ? kb.getSecurityLevel().name() : null)
                .documentCount(kb.getDocCount() != null ? kb.getDocCount() : 0)
                .createTime(kb.getCreateTime())
                .subscribed(true)
                .lifeStatus(kb.getLifeStatus() != null ? kb.getLifeStatus().name() : null)
                .owned(userId != null && userId.equals(kb.getAuthorUserId()))
                .build();
    }

    /**
     * McpService → McpResourceItem 转换。
     */
    private McpResourceItem toMcpResourceItem(McpService mcp) {
        return McpResourceItem.builder()
                .id(mcp.getId())
                .name(mcp.getMcpName())
                .description(mcp.getDescription())
                .securityLevel(mcp.getSecurityLevel() != null ? mcp.getSecurityLevel().name() : null)
                .toolCount(mcp.getToolCount() != null ? mcp.getToolCount() : 0)
                .subsCount(mcp.getSubsCount() != null ? mcp.getSubsCount() : 0)
                .createTime(mcp.getCreateTime())
                .subscribed(true)
                .build();
    }
}
