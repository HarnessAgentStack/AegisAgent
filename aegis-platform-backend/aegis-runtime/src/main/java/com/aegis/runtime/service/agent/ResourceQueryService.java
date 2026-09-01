package com.aegis.runtime.service.agent;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.resource.KbDocument;
import com.aegis.core.domain.resource.KbSubscription;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.domain.resource.McpService;
import com.aegis.core.domain.resource.McpSubscription;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.enums.resource.SubscriberType;
import com.aegis.dal.mapper.agent.AgentBindingMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.aegis.dal.mapper.resource.RuntimeKbDocumentMapper;
import com.aegis.dal.mapper.resource.KbSubscriptionMapper;
import com.aegis.dal.mapper.resource.KnowledgeBaseMapper;
import com.aegis.dal.mapper.resource.McpServiceMapper;
import com.aegis.dal.mapper.resource.McpSubscriptionMapper;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.ToolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资源查询领域服务。
 *
 * <p>收口智能体相关资源的数据访问，聚合 {@link ToolMapper}、{@link AgentBindingMapper}、
 * {@link McpServiceMapper}、{@link SkillMapper}、{@link AgentDefMapper} 五个 Mapper，
 * 供 {@code AegisToolBridge}、{@code WorkspaceMaterializer}、{@code BindingSyncMiddleware}、
 * {@code McpInvoker}、{@code SkillExecutor} 等集成层组件调用，避免 integration 层直接持有 DAL Mapper。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>查询智能体绑定的工具列表（含 TOOL 类型绑定过滤）</li>
 *   <li>按 ID 查询 Tool / Skill / McpService / AgentDef</li>
 *   <li>查询智能体全部启用的绑定（不限类型）</li>
 * </ul>
 *
 * <p>v2.0：已移除 McpClientMapper，简化为直接查询 McpService。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceQueryService {

    private final ToolMapper toolMapper;
    private final AgentBindingMapper agentBindingMapper;
    private final McpServiceMapper mcpServiceMapper;
    private final McpSubscriptionMapper mcpSubscriptionMapper;
    private final SkillMapper skillMapper;
    private final AgentDefMapper agentDefMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbSubscriptionMapper kbSubscriptionMapper;
    private final RuntimeKbDocumentMapper kbDocumentMapper;

    /**
     * 按 ID 查询工具定义。
     *
     * @param toolId 工具ID
     * @return 工具定义，不存在时返回 null
     */
    public Tool findToolById(Long toolId) {
        return toolMapper.selectById(toolId);
    }

    /**
     * 按 ID 查询技能定义。
     *
     * @param skillId 技能ID
     * @return 技能定义，不存在时返回 null
     */
    public Skill findSkillById(Long skillId) {
        return skillMapper.selectById(skillId);
    }

    /**
     * 按ID批量查询技能定义（装配期一次 selectBatchIds，供 ToolBridge 与 SkillRepository 共享，T4）。
     *
     * @param skillIds 技能ID集合（null/空返回空列表）
     * @return 技能定义列表（不存在的 ID 自然排除，不区分 PUBLISHED 状态）
     */
    public List<Skill> findSkillsByIds(java.util.Collection<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        return skillMapper.selectBatchIds(skillIds);
    }

    /**
     * 按 ID 查询 MCP 服务定义。
     *
     * @param mcpServiceId MCP 服务ID
     * @return MCP 服务定义，不存在时返回 null
     */
    public McpService findMcpServiceById(Long mcpServiceId) {
        return mcpServiceMapper.selectById(mcpServiceId);
    }

    /**
     * 按 ID 查询智能体定义。
     *
     * @param agentId 智能体ID
     * @return 智能体定义，不存在时返回 null
     */
    public AgentDef findAgentDefById(Long agentId) {
        return agentDefMapper.selectById(agentId);
    }

    /**
     * 查询智能体启用的全部绑定（不限资源类型）。
     *
     * @param agentId 智能体ID
     * @return 启用的绑定列表，无数据时返回空列表
     */
    public List<AgentBinding> listEnabledBindings(long agentId) {
        return agentBindingMapper.selectList(
                new LambdaQueryWrapper<AgentBinding>()
                        .eq(AgentBinding::getAgentId, agentId)
                        .eq(AgentBinding::getEnabled, true));
    }

    /**
     * 查询智能体启用的 TOOL 类型绑定。
     *
     * @param agentId 智能体ID
     * @return 启用的 TOOL 类型绑定列表，无数据时返回空列表
     */
    public List<AgentBinding> listEnabledToolBindings(long agentId) {
        return agentBindingMapper.selectList(
                new LambdaQueryWrapper<AgentBinding>()
                        .eq(AgentBinding::getAgentId, agentId)
                        .eq(AgentBinding::getEnabled, true)
                        .eq(AgentBinding::getResourceType, ResourceType.TOOL));
    }

    /**
     * 按 ID 查询知识库。
     *
     * <p>v4.1 新增：供 RAG 中间件查询知识库安全等级。
     *
     * @param kbId 知识库ID
     * @return 知识库实体，不存在时返回 null
     */
    public KnowledgeBase getKnowledgeBase(Long kbId) {
        return knowledgeBaseMapper.selectById(kbId);
    }

    /**
     * 按 ID 查询知识库文档。
     *
     * <p>v4.4 新增：供 RAG 中间件在构建 kb.reference 事件时补充文档名。
     *
     * @param docId 文档ID
     * @return 文档实体，不存在时返回 null
     */
    public KbDocument getKbDocument(Long docId) {
        if (docId == null) {
            return null;
        }
        return kbDocumentMapper.selectById(docId);
    }

    /**
     * 查询智能体绑定的知识库 ID 列表。
     *
     * @param agentId 智能体ID
     * @return 知识库 ID 列表
     */
    public List<Long> listBoundKbIds(Long agentId) {
        List<AgentBinding> bindings = agentBindingMapper.selectList(
                new LambdaQueryWrapper<AgentBinding>()
                        .eq(AgentBinding::getAgentId, agentId)
                        .eq(AgentBinding::getResourceType, ResourceType.KNOWLEDGE_BASE)
                        .eq(AgentBinding::getEnabled, true));
        return bindings.stream()
                .map(AgentBinding::getResourceId)
                .collect(Collectors.toList());
    }

    /**
     * 查询智能体绑定的 MCP 服务 ID 列表。
     *
     * @param agentId 智能体ID
     * @return MCP 服务 ID 列表
     */
    public List<Long> listBoundMcpIds(Long agentId) {
        List<AgentBinding> bindings = agentBindingMapper.selectList(
                new LambdaQueryWrapper<AgentBinding>()
                        .eq(AgentBinding::getAgentId, agentId)
                        .eq(AgentBinding::getResourceType, ResourceType.MCP_SERVICE)
                        .eq(AgentBinding::getEnabled, true));
        return bindings.stream()
                .map(AgentBinding::getResourceId)
                .collect(Collectors.toList());
    }

    /**
     * 查询用户订阅的知识库 ID 列表。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @return 订阅的知识库 ID 列表
     */
    public List<Long> listUserSubscribedKbIds(Long tenantId, Long userId) {
        List<KbSubscription> subscriptions = kbSubscriptionMapper.selectList(
                new LambdaQueryWrapper<KbSubscription>()
                        .eq(KbSubscription::getTenantId, tenantId)
                        .eq(KbSubscription::getSubscriberType, SubscriberType.USER)
                        .eq(KbSubscription::getSubscriberId, userId));
        return subscriptions.stream()
                .map(KbSubscription::getKbId)
                .collect(Collectors.toList());
    }

    /**
     * 批量查询知识库详情。
     *
     * @param kbIds 知识库 ID 列表
     * @return 知识库实体列表
     */
    public List<KnowledgeBase> batchGetKnowledgeBases(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyList();
        }
        return knowledgeBaseMapper.selectBatchIds(kbIds);
    }

    /**
     * 批量查询 MCP 服务详情。
     *
     * @param mcpIds MCP 服务 ID 列表
     * @return MCP 服务实体列表
     */
    public List<McpService> batchGetMcpServices(List<Long> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }
        return mcpServiceMapper.selectBatchIds(mcpIds);
    }

    /**
     * 根据 ID 集合查询知识库。
     *
     * @param kbIds 知识库 ID 集合
     * @return 知识库实体列表
     */
    public List<KnowledgeBase> findKnowledgeBasesByIds(Set<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyList();
        }
        return knowledgeBaseMapper.selectBatchIds(kbIds);
    }

    /**
     * 根据 ID 集合查询 MCP 服务。
     *
     * @param mcpIds MCP 服务 ID 集合
     * @return MCP 服务实体列表
     */
    public List<McpService> findMcpServicesByIds(Set<Long> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }
        return mcpServiceMapper.selectBatchIds(mcpIds);
    }

    /**
     * 根据 ID 集合查询 MCP 服务（仅返回已发布且启用的）。
     *
     * @param mcpIds MCP 服务 ID 集合
     * @return 已发布且启用的 MCP 服务实体列表
     */
    public List<McpService> findActiveMcpServicesByIds(Set<Long> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpService> all = mcpServiceMapper.selectBatchIds(mcpIds);
        return all.stream()
                .filter(mcp -> mcp.getLifeStatus() == AgentLifeStatus.PUBLISHED
                        && mcp.getStatus() == ProviderStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    /**
     * 根据 ID 集合查询知识库（仅返回已发布的）。
     *
     * @param kbIds 知识库 ID 集合
     * @return 已发布的知识库实体列表
     */
    public List<KnowledgeBase> findPublishedKnowledgeBasesByIds(Set<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBase> all = knowledgeBaseMapper.selectBatchIds(kbIds);
        return all.stream()
                .filter(kb -> kb.getLifeStatus() == AgentLifeStatus.PUBLISHED)
                .collect(Collectors.toList());
    }

    /**
     * 查询用户在当前租户创建的知识库 ID 列表（全生命周期状态，含 DRAFT/REVIEWING）。
     *
     * <p>工作台资源面板候选来源之一：作者对自建知识库天然可见，
     * 与 {@link #queryUserSkills} 的"自建含草稿"语义对齐。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @return 用户创建的知识库 ID 列表
     */
    public List<Long> listUserOwnedKbIds(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return Collections.emptyList();
        }
        List<KnowledgeBase> kbs = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getTenantId, tenantId)
                        .eq(KnowledgeBase::getAuthorUserId, userId)
                        .ne(KnowledgeBase::getLifeStatus, AgentLifeStatus.ARCHIVED));
        return kbs.stream()
                .map(KnowledgeBase::getId)
                .collect(Collectors.toList());
    }

    /**
     * 根据 ID 集合查询可引用的知识库（已发布的 + 指定用户自建的草稿/审核中）。
     *
     * <p>引用规则：PUBLISHED 任何有权限用户可引用；
     * DRAFT/REVIEWING 仅创建者本人可引用（用于自建库的自测）；
     * ARCHIVED 任何人都不可引用。
     *
     * @param kbIds  知识库 ID 集合
     * @param userId 当前用户ID（用于判定创建者身份）
     * @return 可引用的知识库实体列表
     */
    public List<KnowledgeBase> findReferenceableKnowledgeBasesByIds(Set<Long> kbIds, Long userId) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBase> all = knowledgeBaseMapper.selectBatchIds(kbIds);
        return all.stream()
                .filter(kb -> isReferenceable(kb, userId))
                .collect(Collectors.toList());
    }

    /**
     * 判断知识库对指定用户是否可引用。
     */
    private boolean isReferenceable(KnowledgeBase kb, Long userId) {
        if (kb.getLifeStatus() == AgentLifeStatus.PUBLISHED) {
            return true;
        }
        if (kb.getLifeStatus() == AgentLifeStatus.ARCHIVED) {
            return false;
        }
        return userId != null && userId.equals(kb.getAuthorUserId());
    }

    /**
     * 查询已发布且启用的 MCP 服务列表（市场展示用）。
     *
     * @param limit 限制数量（0 表示不限制）
     * @return MCP 服务列表
     */
    public List<McpService> listPublishedActiveMcpServices(int limit) {
        LambdaQueryWrapper<McpService> wrapper = new LambdaQueryWrapper<McpService>()
                .eq(McpService::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .eq(McpService::getStatus, ProviderStatus.ACTIVE)
                .orderByDesc(McpService::getSubsCount);
        if (limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return mcpServiceMapper.selectList(wrapper);
    }

    /**
     * 查询已发布的知识库列表（市场展示用）。
     *
     * @param tenantId 租户ID（用于过滤本租户的知识库）
     * @param limit    限制数量（0 表示不限制）
     * @return 知识库列表
     */
    public List<KnowledgeBase> listPublishedKnowledgeBases(Long tenantId, int limit) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .orderByDesc(KnowledgeBase::getCreateTime);
        if (tenantId != null) {
            wrapper.eq(KnowledgeBase::getTenantId, tenantId);
        }
        if (limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return knowledgeBaseMapper.selectList(wrapper);
    }

    /**
     * 查询用户订阅的 MCP 服务 ID 列表。
     *
     * <p>从 res_mcp_subscription 表查询当前用户在当前租户的订阅记录。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @return 订阅的 MCP 服务 ID 列表
     */
    public List<Long> listUserSubscribedMcpIds(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return Collections.emptyList();
        }
        List<McpSubscription> subscriptions = mcpSubscriptionMapper.selectList(
                new LambdaQueryWrapper<McpSubscription>()
                        .eq(McpSubscription::getTenantId, tenantId)
                        .eq(McpSubscription::getSubscriberType, SubscriberType.USER)
                        .eq(McpSubscription::getSubscriberId, userId));
        return subscriptions.stream()
                .map(McpSubscription::getMcpServiceId)
                .collect(Collectors.toList());
    }

    /**
     * 查询可注册为 Tool 的 GLOBAL 系统技能（如 skill_creator）。
     *
     * <p>GLOBAL 系统技能的 tenant_id=0（启动时由 SkillCreatorInitializer 以平台租户
     * 身份插入）。res_skill 不在租户忽略表中，走 {@link SkillMapper#selectGlobalSkillsForTenant}
     * （{@code @InterceptorIgnore} 显式跳过租户插件）跨租户查询，
     * 无需清空/恢复租户上下文——旧的 clear() 模式在 fail-closed 租户插件下会抛
     * "租户上下文缺失"异常，导致智能体装配失败。
     *
     * <p>仅返回 isSystem=true 且 PUBLISHED 的技能，普通租户技能不会进入该集合。
     *
     * @return GLOBAL 系统技能列表（无数据时返回空列表）
     */
    public List<Skill> listGlobalToolSkills() {
        return skillMapper.selectGlobalSkillsForTenant(
                        SkillScope.GLOBAL.name(), AgentLifeStatus.PUBLISHED.name(), null).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsSystem()))
                .collect(Collectors.toList());
    }

    /**
     * 合并两个 ID 列表并去重。
     *
     * @param list1 第一个 ID 列表
     * @param list2 第二个 ID 列表
     * @return 合并去重后的 ID 列表
     */
    public List<Long> mergeAndDeduplicate(List<Long> list1, List<Long> list2) {
        if (list1 == null || list1.isEmpty()) {
            return list2 != null ? new ArrayList<>(list2) : Collections.emptyList();
        }
        if (list2 == null || list2.isEmpty()) {
            return new ArrayList<>(list1);
        }
        Set<Long> set = new java.util.LinkedHashSet<>(list1);
        set.addAll(list2);
        return new ArrayList<>(set);
    }
}
