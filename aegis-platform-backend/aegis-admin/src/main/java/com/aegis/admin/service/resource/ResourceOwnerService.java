package com.aegis.admin.service.resource;

import com.aegis.core.domain.agent.AgentApi;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.agent.AgentSubscription;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.domain.resource.KbSubscription;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.SkillSubscription;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.security.ResourcePermission;
import com.aegis.core.security.UserContext;
import com.aegis.core.context.UserContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 资源归属校验服务。
 *
 * <p>校验当前用户对指定资源是否拥有访问权限，支持以下校验方式：
 * <ul>
 *   <li>创建者校验：用户是资源的作者</li>
 *   <li>订阅者校验：用户已订阅该资源</li>
 *   <li>管理员校验：用户是租户管理员或平台管理员</li>
 * </ul>
 *
 * <p>可供 SpEL 表达式调用，示例：
 * <pre>
 * @PreAuthorize("@resourceOwnerService.hasAgentAccess(#agentId, 'VIEW')")
 * </pre>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceOwnerService {

    // Mapper 引用
    private final com.aegis.dal.mapper.agent.AgentDefMapper agentDefMapper;
    private final com.aegis.dal.mapper.agent.AgentApiMapper agentApiMapper;
    private final com.aegis.dal.mapper.agent.AgentSubscriptionMapper agentSubscriptionMapper;
    private final com.aegis.dal.mapper.resource.SkillMapper skillMapper;
    private final com.aegis.dal.mapper.resource.SkillSubscriptionMapper skillSubscriptionMapper;
    private final com.aegis.dal.mapper.resource.KnowledgeBaseMapper knowledgeBaseMapper;
    private final com.aegis.dal.mapper.resource.KbSubscriptionMapper kbSubscriptionMapper;

    /**
     * 校验当前用户是否对指定智能体有访问权限。
     * 供 SpEL 表达式调用。
     */
    public boolean hasAgentAccess(Long agentId, String permission) {
        return checkResourceAccess(agentId, ResourceType.AGENT, ResourcePermission.valueOf(permission));
    }

    /**
     * 校验当前用户是否对指定技能有访问权限。
     * 供 SpEL 表达式调用。
     */
    public boolean hasSkillAccess(Long skillId, String permission) {
        return checkResourceAccess(skillId, ResourceType.SKILL, ResourcePermission.valueOf(permission));
    }

    /**
     * 校验当前用户是否对指定知识库有访问权限。
     * 供 SpEL 表达式调用。
     */
    public boolean hasKbAccess(Long kbId, String permission) {
        return checkResourceAccess(kbId, ResourceType.KNOWLEDGE_BASE, ResourcePermission.valueOf(permission));
    }

    /**
     * 统一资源访问权限校验方法（兜底入口：从 UserContextHolder 获取用户）。
     *
     * @param resourceId   资源ID
     * @param resourceType 资源类型
     * @param permission   所需权限
     * @return 是否有权访问
     */
    public boolean checkResourceAccess(Long resourceId, ResourceType resourceType, ResourcePermission permission) {
        return checkResourceAccess(resourceId, resourceType, permission, UserContextHolder.currentUser());
    }

    /**
     * 统一资源访问权限校验方法（显式传入 UserContext）。
     *
     * <p>WebFlux 环境下 JwtAuthFilter 只注入 HTTP Header 不写 SecurityContext，
     * 必须由上层（如 ResourceOwnerAspect）通过反射 @RequestHeader 构造 UserContext 后透传进来，
     * 避免 {@link UserContextHolder} 返回 null 导致权限校验失败。</p>
     *
     * @param resourceId   资源ID
     * @param resourceType 资源类型
     * @param permission   所需权限
     * @param currentUser  当前用户上下文（来自 Aspect 反射或 SecurityContext）
     * @return 是否有权访问
     */
    @Transactional(readOnly = true)
    public boolean checkResourceAccess(Long resourceId, ResourceType resourceType, ResourcePermission permission,
                                       UserContext currentUser) {
        if (currentUser == null || resourceId == null) {
            log.warn("资源访问校验失败：用户未登录或资源ID为空");
            return false;
        }

        Long userId = currentUser.getUserId();
        Long tenantId = currentUser.getTenantId();

        // 1. 平台管理员直接放行
        if (currentUser.isPlatformAdmin()) {
            log.debug("资源访问校验通过：用户[{}]是平台管理员", userId);
            return true;
        }

        // 2. 租户管理员可以管理本租户的所有资源
        if (currentUser.isTenantAdmin()) {
            boolean inSameTenant = isResourceInTenant(resourceId, resourceType, tenantId);
            if (inSameTenant) {
                log.debug("资源访问校验通过：用户[{}]是租户管理员，资源在租户[{}]内", userId, tenantId);
                return true;
            }
        }

        // 3. 根据资源类型校验创建者和订阅者权限
        return switch (resourceType) {
            case AGENT -> checkAgentAccess(resourceId, userId, permission);
            // AGENT_API 资源无独立 owner：先解析 agent_api → agentId，再按所属智能体校验
            // （修复：此前 AgentApiController 的 /{id}/** 端点误用 AGENT 类型直查 agent_def，
            //   将 agent_api 主键当 agentId 查询必然"智能体不存在"→ 一律 403）
            case AGENT_API -> checkAgentApiAccess(resourceId, userId, permission);
            case SKILL -> checkSkillAccess(resourceId, userId, permission);
            case KNOWLEDGE_BASE -> checkKbAccess(resourceId, userId, permission);
            case MCP_SERVICE, TOOL, DATASET -> checkMcporToolAccess(resourceId, userId, resourceType);
        };
    }

    /**
     * 校验智能体开放 API 配置的访问权限。
     *
     * <p>agent_api 无独立作者字段，归属随所属智能体：
     * 以 apiId 解析 {@code agent_api.agentId} 后复用 {@link #checkAgentAccess}，
     * 智能体创建者（及 VIEW 权限下的订阅者）即拥有其 API 配置的管理权。
     */
    private boolean checkAgentApiAccess(Long apiId, Long userId, ResourcePermission permission) {
        AgentApi api = agentApiMapper.selectById(apiId);
        if (api == null) {
            log.warn("API配置不存在: apiId={}", apiId);
            return false;
        }
        return checkAgentAccess(api.getAgentId(), userId, permission);
    }

    /**
     * 校验智能体访问权限。
     */
    private boolean checkAgentAccess(Long agentId, Long userId, ResourcePermission permission) {
        AgentDef agent = agentDefMapper.selectById(agentId);
        if (agent == null) {
            log.warn("智能体不存在: agentId={}", agentId);
            return false;
        }

        // 创建者校验
        if (agent.getAuthorUserId() != null && agent.getAuthorUserId().equals(userId)) {
            log.debug("用户[{}]是智能体[{}]的创建者", userId, agentId);
            return true;
        }

        // 对于 VIEW 权限，订阅者也可以访问
        if (permission.includes(ResourcePermission.VIEW)) {
            Long count = agentSubscriptionMapper.selectCount(
                    new LambdaQueryWrapper<AgentSubscription>()
                            .eq(AgentSubscription::getAgentId, agentId)
                            .eq(AgentSubscription::getUserId, userId)
            );
            if (count != null && count > 0) {
                log.debug("用户[{}]已订阅智能体[{}]", userId, agentId);
                return true;
            }
        }

        return false;
    }

    /**
     * 校验技能访问权限。
     */
    private boolean checkSkillAccess(Long skillId, Long userId, ResourcePermission permission) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            log.warn("技能不存在: skillId={}", skillId);
            return false;
        }

        // 创建者校验
        if (skill.getAuthorUserId() != null && skill.getAuthorUserId().equals(userId)) {
            log.debug("用户[{}]是技能[{}]的创建者", userId, skillId);
            return true;
        }

        // 对于 VIEW 权限，订阅者也可以访问
        if (permission.includes(ResourcePermission.VIEW)) {
            Long count = skillSubscriptionMapper.selectCount(
                    new LambdaQueryWrapper<SkillSubscription>()
                            .eq(SkillSubscription::getSkillId, skillId)
                            .eq(SkillSubscription::getSubscriberId, userId)
            );
            if (count != null && count > 0) {
                log.debug("用户[{}]已订阅技能[{}]", userId, skillId);
                return true;
            }
        }

        return false;
    }

    /**
     * 校验知识库访问权限。
     */
    private boolean checkKbAccess(Long kbId, Long userId, ResourcePermission permission) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            log.warn("知识库不存在: kbId={}", kbId);
            return false;
        }

        // 创建者校验
        if (kb.getAuthorUserId() != null && kb.getAuthorUserId().equals(userId)) {
            log.debug("用户[{}]是知识库[{}]的创建者", userId, kbId);
            return true;
        }

        // 对于 VIEW 权限，订阅者也可以访问
        if (permission.includes(ResourcePermission.VIEW)) {
            Long count = kbSubscriptionMapper.selectCount(
                    new LambdaQueryWrapper<KbSubscription>()
                            .eq(KbSubscription::getKbId, kbId)
                            .eq(KbSubscription::getSubscriberId, userId)
            );
            if (count != null && count > 0) {
                log.debug("用户[{}]已订阅知识库[{}]", userId, kbId);
                return true;
            }
        }

        return false;
    }

    /**
     * 校验 MCP/Tool/Dataset 访问权限。
     *
     * <p>这些资源为租户级共享资源，无独立 authorUserId 字段。
     * 管理操作（CREATE/EDIT/DELETE/PUBLISH/MANAGE）仅平台/租户管理员可执行（已在上方分支放行）；
     * 普通用户对此类资源的管理请求一律拒绝（fail-closed），仅可经订阅/绑定使用。
     */
    private boolean checkMcporToolAccess(Long resourceId, Long userId, ResourceType resourceType) {
        log.warn("MCP/Tool/Dataset 管理操作拒绝（非管理员）: userId={}, resourceType={}, resourceId={}",
                userId, resourceType, resourceId);
        return false;
    }

    /**
     * 检查资源是否属于指定租户。
     */
    private boolean isResourceInTenant(Long resourceId, ResourceType resourceType, Long tenantId) {
        try {
            Long resourceTenantId = switch (resourceType) {
                case AGENT -> {
                    AgentDef agent = agentDefMapper.selectById(resourceId);
                    yield agent != null ? agent.getTenantId() : null;
                }
                case AGENT_API -> {
                    AgentApi api = agentApiMapper.selectById(resourceId);
                    yield api != null ? api.getTenantId() : null;
                }
                case SKILL -> {
                    Skill skill = skillMapper.selectById(resourceId);
                    yield skill != null ? skill.getTenantId() : null;
                }
                case KNOWLEDGE_BASE -> {
                    KnowledgeBase kb = knowledgeBaseMapper.selectById(resourceId);
                    yield kb != null ? kb.getTenantId() : null;
                }
                default -> null;
            };

            return resourceTenantId != null && resourceTenantId.equals(tenantId);
        } catch (Exception e) {
            log.error("检查资源租户归属失败", e);
            return false;
        }
    }
}
