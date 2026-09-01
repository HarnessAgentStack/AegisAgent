package com.aegis.runtime.integration.agent;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.McpService;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.SkillSubscription;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.enums.resource.SubscriberType;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.dal.mapper.resource.McpSubscriptionMapper;
import com.aegis.dal.mapper.resource.SkillSubscriptionMapper;
import com.aegis.runtime.service.agent.AssemblyResourceContext;
import com.aegis.runtime.service.agent.ResourceQueryService;
import com.aegis.runtime.integration.mcp.McpInvoker;
import com.aegis.runtime.integration.skill.SkillCreatorOrchestrator;
import com.aegis.runtime.integration.tool.AegisBuiltinTools;
import com.aegis.runtime.integration.tool.AegisExecuteTool;
import com.aegis.runtime.integration.tool.AegisGenerateFileTool;
import com.aegis.runtime.integration.tool.AegisHttpTool;
import com.aegis.runtime.integration.tool.AegisMcpTool;
import com.aegis.runtime.integration.tool.SkillAsToolAdapter;
import com.aegis.runtime.integration.tool.SkillExecutor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aegis 工具桥接器：将 Aegis 的 res_tool 表中的工具注册到 AgentScope {@link Toolkit}。
 *
 * <p>核心职责：
 * <ul>
 *   <li>从 agent_binding 表查询 TOOL 类型绑定，关联 res_tool 表加载工具定义</li>
 *   <li>将绑定的工具按 toolCode 分派到对应的 AS 工具实现：
 *     <ul>
 *       <li>{@code web_search} → {@link AegisBuiltinTools}（@Tool 注解模式，
 *           通过 {@code toolkit.registerTool()} 注册，AS 自动扫描 @Tool 方法）</li>
 *       <li>{@code generate_file} → {@link AegisGenerateFileTool}（ToolBase 子类模式，
 *           通过 {@code toolkit.registerAgentTool()} 注册，可访问 RuntimeContext 解决租户上下文丢失）</li>
 *       <li>{@code http_request} → {@link AegisHttpTool}（ToolBase 子类模式，
 *           通过 {@code toolkit.registerAgentTool()} 注册）</li>
 *     </ul>
 *   </li>
 *   <li>MCP 来源工具通过 {@link McpInvoker} 查询 MCP 服务暴露的工具列表，
 *       每个 MCP 工具包装为 {@link AegisMcpTool} 注册</li>
 *   <li>CODE_EXEC 等其他工具类型跳过 AS 注册，由其他集成路径处理</li>
 * </ul>
 *
 * @author wang.zhen
 * @see AegisBuiltinTools
 * @see AegisGenerateFileTool
 * @see AegisHttpTool
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisToolBridge {

    private final ResourceQueryService resourceQueryService;
    private final AegisBuiltinTools aegisBuiltinTools;
    private final AegisGenerateFileTool aegisGenerateFileTool;
    private final AegisHttpTool aegisHttpTool;
    private final AegisExecuteTool aegisExecuteTool;
    private final McpInvoker mcpInvoker;
    private final ToolResultCache toolResultCache;
    private final SkillExecutor skillExecutor;
    private final SkillCreatorOrchestrator skillCreatorOrchestrator;
    /** v4.1: 统一安全策略引擎（MCP 工具出站联动） */
    private final com.aegis.runtime.service.policy.AegisSecurityPolicyEngine securityPolicyEngine;
    /** MCP 订阅 Mapper，用于查询用户订阅的 MCP 服务 */
    private final McpSubscriptionMapper mcpSubscriptionMapper;
    /** 技能订阅 Mapper，用于查询用户订阅的技能 */
    private final SkillSubscriptionMapper skillSubscriptionMapper;

    /** AegisBuiltinTools 提供的工具编码集合（@Tool 注解方法） */
    private static final Set<String> BUILTIN_ANNOTATED_TOOLS = Set.of(
            "web_search",
            "image_search",
            "memory_search",
            "session_search",
            "search_history",
            "image_generation"
    );

    /**
     * 可注册为 Tool 的 GLOBAL 系统技能白名单（元技能，有专门编排器承接 tool_call）。
     *
     * <p>skill_creator 由 {@link SkillCreatorOrchestrator} 承接，
     * 调用链见 {@code SkillAsToolAdapter#handleSkillCreatorCall}。
     */
    private static final Set<String> TOOL_SKILL_CODES = Set.of("skill_creator");

    /**
     * 基于装配期资源上下文解析绑定的工具/技能列表并注册到 Toolkit（T3/T4 收敛入口）。
     *
     * <p>装配期由 {@code AgentAssemblyService#buildResourceContext} 一次全量查询
     * enabled 绑定与绑定技能实体（{@code selectBatchIds}），本方法与
     * {@code AegisSkillRepository}、{@code AegisRagMiddleware} 共享同一份数据，
     * 消除 agent_binding 的重复 SELECT 与绑定 skill 的双重加载。
     *
     * <p>TOOL 类型绑定按 toolCode 分派注册；SKILL 类型绑定复用预载实体，
     * 缺失时回退单条查询（上下文构建失败的降级路径）。
     *
     * @param toolkit  目标 Toolkit 实例
     * @param resources 装配期资源上下文（null 或空绑定时直接返回）
     */
    public void resolveTools(Toolkit toolkit, AssemblyResourceContext resources) {
        if (resources == null) {
            return;
        }
        List<AgentBinding> allBindings = resources.enabledBindings();
        if (allBindings == null || allBindings.isEmpty()) {
            return;
        }


        // WebFlux boundedElastic 线程 ThreadLocal 可能丢失，
        // 从 AssemblyResourceContext 的 binding 取 tenantId 手动 bind，
        // 确保后续 findToolById/findSkillById 带正确租户过滤
        Long ctxTenant = allBindings.get(0).getTenantId();
        if (ctxTenant != null) {
            TenantContextHolder.bind(ctxTenant);
        }

        // 处理 TOOL 类型绑定
        List<Tool> tools = new ArrayList<>();
        for (AgentBinding binding : allBindings) {
            if (binding.getResourceType() == ResourceType.TOOL) {
                Tool tool = resourceQueryService.findToolById(binding.getResourceId());
                if (tool == null) {
                    log.warn("工具不存在，跳过: resourceId={}", binding.getResourceId());
                    continue;
                }
                tools.add(tool);
            }
        }
        resolveTools(toolkit, tools);

        // 处理 SKILL 类型绑定：优先复用装配期批量预载实体（T4），缺失时降级单查
        List<Skill> boundSkills = resources.boundSkills();
        Map<Long, Skill> skillById = new HashMap<>();
        if (boundSkills != null) {
            for (Skill s : boundSkills) {
                if (s != null && s.getId() != null) {
                    skillById.put(s.getId(), s);
                }
            }
        }
        int skillCount = 0;
        for (AgentBinding binding : allBindings) {
            if (binding.getResourceType() != ResourceType.SKILL) {
                continue;
            }
            Skill skill = skillById.get(binding.getResourceId());
            if (skill == null) {
                skill = resourceQueryService.findSkillById(binding.getResourceId());
            }
            if (skill == null) {
                log.warn("技能不存在，跳过: resourceId={}", binding.getResourceId());
                continue;
            }
            registerSkillAsTool(toolkit, skill);
            skillCount++;
        }

        log.debug("resolveTools(AssemblyResourceContext): bindingCount={}, toolCount={}, skillCount={}",
                allBindings.size(), tools.size(), skillCount);
    }

    /**
     * 将 Aegis {@link Tool} 列表对应的工具实现注册到 {@link Toolkit}。
     *
     * <p>分派逻辑：
     * <ul>
     *   <li>{@code web_search} → 注册 {@link AegisBuiltinTools}（@Tool 注解模式，仅一次）</li>
     *   <li>{@code generate_file} → 注册 {@link AegisGenerateFileTool}（ToolBase 子类模式，仅一次）</li>
     *   <li>{@code http_request} → 注册 {@link AegisHttpTool}（ToolBase 子类模式，仅一次）</li>
     *   <li>MCP 来源工具 → 通过 {@link McpInvoker} 查询 MCP 工具列表，逐个包装为 {@link AegisMcpTool} 注册</li>
     *   <li>其他工具（CODE_EXEC 等）→ 跳过并记录 debug 日志</li>
     * </ul>
     *
     * @param toolkit 目标 Toolkit 实例
     * @param tools   Aegis 工具实体列表
     */
    public void resolveTools(Toolkit toolkit, List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        boolean builtinRegistered = false;
        boolean generateFileRegistered = false;
        boolean httpRegistered = false;
        boolean networkRegistered = false;
        boolean executeRegistered = false;

        for (Tool tool : tools) {
            String toolCode = tool.getToolCode();
            if (toolCode == null) {
                continue;
            }

            // 注册 @Tool 注解工具（web_search, image_search, memory_search 等）
            if (BUILTIN_ANNOTATED_TOOLS.contains(toolCode)) {
                if (!builtinRegistered) {
                    toolkit.registerTool(aegisBuiltinTools);
                    builtinRegistered = true;
                    log.debug("注册 AegisBuiltinTools（@Tool 注解模式）: trigger={}", toolCode);
                }
                continue;
            }

            // 注册 ToolBase 子类工具（generate_file）
            if ("generate_file".equals(toolCode)) {
                if (!generateFileRegistered) {
                    toolkit.registerAgentTool(aegisGenerateFileTool);
                    generateFileRegistered = true;
                    log.debug("注册 AegisGenerateFileTool（ToolBase 子类模式）: toolCode={}", toolCode);
                }
                continue;
            }

            // 注册 ToolBase 子类工具（http_request）
            if ("http_request".equals(toolCode)) {
                if (!httpRegistered) {
                    toolkit.registerAgentTool(aegisHttpTool);
                    httpRegistered = true;
                    log.debug("注册 AegisHttpTool（ToolBase 子类模式）: toolCode={}", toolCode);
                }
                continue;
            }

            // 注册 network_request 作为 http_request 的别名
            if ("network_request".equals(toolCode)) {
                if (!networkRegistered) {
                    AegisHttpTool networkRequestTool = new AegisHttpTool("network_request");
                    // 手动注入 securityPolicyEngine（因为不是 Spring 管理的 Bean）
                    try {
                        var field = AegisHttpTool.class.getDeclaredField("securityPolicyEngine");
                        field.setAccessible(true);
                        field.set(networkRequestTool, securityPolicyEngine);
                    } catch (Exception e) {
                        log.warn("设置 network_request securityPolicyEngine 失败: {}", e.getMessage());
                    }
                    toolkit.registerAgentTool(networkRequestTool);
                    networkRegistered = true;
                    log.debug("注册 AegisHttpTool（network_request 别名）: toolCode={}", toolCode);
                }
                continue;
            }

            // 注册 aegis_execute 工具（AegisExecuteTool，支持 code + language 参数，兼容 command 模式）
            if ("aegis_execute".equals(toolCode)) {
                if (!executeRegistered) {
                    toolkit.registerAgentTool(aegisExecuteTool);
                    executeRegistered = true;
                    log.debug("注册 AegisExecuteTool（ToolBase 子类模式）: toolCode={}", toolCode);
                }
                continue;
            }

            // 注册 MCP 工具：通过 McpInvoker.listTools 获取 MCP 服务暴露的工具列表
            if (ToolSourceType.MCP.equals(tool.getSourceType()) && tool.getMcpServiceId() != null) {
                registerMcpTools(toolkit, tool);
                continue;
            }

            // 其他未识别工具（CODE_EXEC 等）由其他集成路径处理，此处跳过
            log.debug("跳过非MCP/CODE_EXEC工具注册: toolCode={}, sourceType={}",
                    toolCode, tool.getSourceType());
        }
    }

    /**
     * 注册 MCP 工具到 AgentScope Toolkit。
     *
     * <p>通过 {@link McpInvoker#listTools(String)} 获取 MCP 服务实际暴露的工具列表，
     * 每个工具包装为 {@link AegisMcpTool} 并注册到 Toolkit，使 LLM 能识别并调用。
     *
     * @param toolkit 目标 Toolkit 实例
     * @param tool    MCP 来源的工具定义（res_tool 表记录）
     */
    private void registerMcpTools(Toolkit toolkit, Tool tool) {
        String mcpServiceId = tool.getMcpServiceId().toString();
        try {
            McpService mcpService = resourceQueryService.findMcpServiceById(tool.getMcpServiceId());
            String endpoint = mcpService != null ? mcpService.getEndpoint() : null;

            List<McpSchema.Tool> mcpTools = mcpInvoker.listTools(mcpServiceId);
            if (mcpTools == null || mcpTools.isEmpty()) {
                log.warn("MCP 服务未暴露任何工具，跳过注册: mcpServiceId={}", mcpServiceId);
                return;
            }
            int registered = 0;
            for (McpSchema.Tool mcpTool : mcpTools) {
                AegisMcpTool asTool = AegisMcpTool.of(mcpInvoker, toolResultCache, mcpServiceId, mcpTool, endpoint, securityPolicyEngine);
                toolkit.registerAgentTool(asTool);
                registered++;
                log.debug("注册 MCP 工具: serviceId={}, toolName={}, endpoint={}", mcpServiceId, mcpTool.name(), endpoint);
            }
            log.info("MCP 工具注册完成: serviceId={}, count={}, endpoint={}", mcpServiceId, registered, endpoint);
        } catch (Exception e) {
            log.error("MCP 工具注册失败: mcpServiceId={}, error={}", mcpServiceId, e.getMessage(), e);
        }
    }

    /**
     * 注册 Skill 为 AgentScope Tool。
     *
     * <p>将 {@link Skill} 包装为 {@link SkillAsToolAdapter} 并注册到 Toolkit，
     * 使 LLM 能通过 tool_call 机制调用技能。
     * skill_creator 技能会自动注入 {@link SkillCreatorOrchestrator} 用于编排处理。
     *
     * @param toolkit 目标 Toolkit 实例
     * @param skill   技能定义
     */
    private void registerSkillAsTool(Toolkit toolkit, Skill skill) {
        try {
            SkillAsToolAdapter adapter = SkillAsToolAdapter.of(skill, skillExecutor, toolResultCache, skillCreatorOrchestrator);
            toolkit.registerAgentTool(adapter);
            log.info("Skill 注册为 Tool: skillCode={}, skillType={}, isCreator={}",
                    skill.getSkillCode(), skill.getSkillType(),
                    "skill_creator".equals(skill.getSkillCode()));
        } catch (Exception e) {
            log.error("Skill 注册失败: skillCode={}, error={}", skill.getSkillCode(), e.getMessage(), e);
        }
    }

    /**
     * 将 GLOBAL 系统技能（如 skill_creator）注册为 AgentScope Tool。
     *
     * <p>P0 修复：skill_creator 是 scope=GLOBAL 的系统技能，不在任何 agent_binding 中，
     * {@link #resolveTools(Toolkit, AssemblyResourceContext)} 只处理 agent_binding 绑定，
     * 导致该技能从未被注册到
     * Toolkit —— LLM 能在系统提示词中"看到"它，却无法通过 tool_call"调用"它，
     * 工作台技能创建面板的调试/保存按钮因此始终灰色。
     *
     * <p>白名单控制：仅注册 {@link #TOOL_SKILL_CODES} 中声明的元技能
     * （有专门编排器承接 tool_call 的技能），避免所有 GLOBAL 技能都被强行工具化。
     *
     * @param toolkit 目标 Toolkit 实例
     * @return 本次注册的技能工具数量
     */
    public int resolveGlobalSkillAsTools(Toolkit toolkit) {
        List<Skill> globalSkills = resourceQueryService.listGlobalToolSkills();
        int registered = 0;
        for (Skill skill : globalSkills) {
            if (skill.getSkillCode() == null || !TOOL_SKILL_CODES.contains(skill.getSkillCode())) {
                continue;
            }
            registerSkillAsTool(toolkit, skill);
            registered++;
        }
        log.info("resolveGlobalSkillAsTools: globalSystemSkills={}, registered={}",
                globalSkills.size(), registered);
        return registered;
    }

    /**
     * 将用户订阅的技能注册为 AgentScope Tool。
     *
     * <p>U2 修复：技能订阅链路中，用户订阅（res_skill_subscription）的技能
     * 此前从未注册到 Toolkit —— 订阅只写订阅表，装配时只查 agent_binding 和
     * GLOBAL 系统技能，导致"订阅后在通用智能体中使用"这条核心链路断裂：
     * LLM 看不到已订阅技能的 Tool 定义，无法通过 tool_call 调用。
     *
     * <p>仅注册 PUBLISHED 状态的技能（订阅时技能必须已发布，但作者后续可能
     * 重新编辑回 DRAFT，运行时需再次过滤）。与绑定技能重名时按 Map 覆盖语义
     * 安全去重（{@code ToolRegistry.registerTool} 为 put 语义）。
     *
     * <p>与 {@link #resolveMcpToolsForSubscriptions} 相同，仅对 UNIVERSAL
     * （用户工作台）智能体开放；APPLICATION/SYSTEM 智能体技能仅来自
     * agent_binding 审核通过项，加载用户订阅技能会造成越权。
     *
     * @param toolkit  目标 Toolkit 实例
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @return 本次注册的技能工具数量
     */
    public int resolveSubscribedSkillAsTools(Toolkit toolkit, Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            log.warn("resolveSubscribedSkillAsTools: tenantId 或 userId 为 null，跳过");
            return 0;
        }

        // 1. 查询用户订阅的技能ID列表（res_skill_subscription 不在租户忽略表，插件自动追加租户过滤）
        List<Long> subscribedSkillIds = skillSubscriptionMapper.selectList(
                new LambdaQueryWrapper<SkillSubscription>()
                        .eq(SkillSubscription::getTenantId, tenantId)
                        .eq(SkillSubscription::getSubscriberType, SubscriberType.USER)
                        .eq(SkillSubscription::getSubscriberId, userId))
                .stream()
                .map(SkillSubscription::getSkillId)
                .distinct()
                .collect(Collectors.toList());

        if (subscribedSkillIds.isEmpty()) {
            log.info("resolveSubscribedSkillAsTools: 用户无订阅技能, tenantId={}, userId={}",
                    tenantId, userId);
            return 0;
        }

        // 2. 过滤 PUBLISHED 状态的技能并注册
        int registered = 0;
        for (Long skillId : subscribedSkillIds) {
            Skill skill = resourceQueryService.findSkillById(skillId);
            if (skill == null) {
                log.warn("resolveSubscribedSkillAsTools: 订阅技能不存在，跳过: skillId={}", skillId);
                continue;
            }
            if (skill.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
                log.info("resolveSubscribedSkillAsTools: 订阅技能非 PUBLISHED 状态，跳过: skillId={}, status={}",
                        skillId, skill.getLifeStatus());
                continue;
            }
            registerSkillAsTool(toolkit, skill);
            registered++;
        }
        log.info("resolveSubscribedSkillAsTools: subscribedSkills={}, registered={}, tenantId={}, userId={}",
                subscribedSkillIds.size(), registered, tenantId, userId);
        return registered;
    }

    /**
     * 从用户订阅的 MCP 服务动态加载工具到 Toolkit。
     *
     * <p>查询当前用户在指定租户下订阅的所有 MCP 服务，
     * 过滤出已发布且激活的服务，然后通过 SSE/HTTP 动态获取工具列表并注册。
     * 此方法不依赖 res_tool 表，完全基于 MCP 协议动态发现。
     *
     * <p>调用时机：在智能体装配阶段，与 agent_binding 绑定的工具一起加载。
     *
     * @param toolkit  目标 Toolkit 实例
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @return 本次动态加载的工具数量
     */
    public int resolveMcpToolsForSubscriptions(Toolkit toolkit, Long tenantId, Long userId) {
        log.info("resolveMcpToolsForSubscriptions: 开始加载, tenantId={}, userId={}", tenantId, userId);

        if (tenantId == null || userId == null) {
            log.warn("resolveMcpToolsForSubscriptions: tenantId 或 userId 为 null，跳过");
            return 0;
        }

        // 1. 查询用户订阅的 MCP 服务ID列表
        List<Long> subscribedServiceIds = mcpSubscriptionMapper.selectList(
                new LambdaQueryWrapper<com.aegis.core.domain.resource.McpSubscription>()
                        .eq(com.aegis.core.domain.resource.McpSubscription::getTenantId, tenantId)
                        .eq(com.aegis.core.domain.resource.McpSubscription::getSubscriberType, SubscriberType.USER)
                        .eq(com.aegis.core.domain.resource.McpSubscription::getSubscriberId, userId))
                .stream()
                .map(com.aegis.core.domain.resource.McpSubscription::getMcpServiceId)
                .collect(Collectors.toList());

        log.info("resolveMcpToolsForSubscriptions: 查询到{}个订阅MCP服务, tenantId={}, userId={}",
                subscribedServiceIds.size(), tenantId, userId);

        if (subscribedServiceIds.isEmpty()) {
            log.info("resolveMcpToolsForSubscriptions: 用户无订阅的 MCP 服务, tenantId={}, userId={}",
                    tenantId, userId);
            return 0;
        }

        // 2. 过滤已发布且激活的服务
        List<McpService> activeServices = new ArrayList<>();
        for (Long serviceId : subscribedServiceIds) {
            McpService service = resourceQueryService.findMcpServiceById(serviceId);
            if (service != null
                    && service.getLifeStatus() == AgentLifeStatus.PUBLISHED
                    && service.getStatus() == ProviderStatus.ACTIVE) {
                activeServices.add(service);
            } else {
                log.info("resolveMcpToolsForSubscriptions: 服务不符合条件, serviceId={}, lifeStatus={}, status={}",
                        serviceId,
                        service != null ? service.getLifeStatus() : "null",
                        service != null ? service.getStatus() : "null");
            }
        }

        log.info("resolveMcpToolsForSubscriptions: 过滤后有效服务数={}, 订阅数={}", activeServices.size(), subscribedServiceIds.size());

        if (activeServices.isEmpty()) {
            log.info("resolveMcpToolsForSubscriptions: 无已发布激活的 MCP 服务, tenantId={}, userId={}",
                    tenantId, userId);
            return 0;
        }

        // 3. 动态加载每个服务的工具
        int totalRegistered = 0;
        for (McpService service : activeServices) {
            log.info("resolveMcpToolsForSubscriptions: 加载服务工具, serviceId={}, mcpCode={}, endpoint={}",
                    service.getId(), service.getMcpCode(), service.getEndpoint());
            int registered = registerToolsFromMcpService(toolkit, service.getId(), service.getEndpoint());
            totalRegistered += registered;
        }

        log.info("resolveMcpToolsForSubscriptions: 完成动态 MCP 工具加载, tenantId={}, userId={}, " +
                "serviceCount={}, toolCount={}", tenantId, userId, activeServices.size(), totalRegistered);
        return totalRegistered;
    }

    /**
     * 直接加载指定 MCP 服务ID列表的工具（会话级临时引用）。
     *
     * <p>与 {@link #resolveMcpToolsForSubscriptions} 不同，此方法不查询订阅关系，
     * 直接根据传入的服务ID列表加载工具。用于用户在对话中临时选择 MCP 服务的场景。
     *
     * @param toolkit         目标 Toolkit 实例
     * @param mcpServiceIds   需要加载工具的 MCP 服务ID列表
     * @param tenantId        租户ID
     * @return 加载的工具总数
     */
    public int resolveMcpToolsForServiceIds(Toolkit toolkit, List<Long> mcpServiceIds, Long tenantId) {
        log.info("resolveMcpToolsForServiceIds: 开始加载, serviceIds={}, tenantId={}", mcpServiceIds, tenantId);

        if (mcpServiceIds == null || mcpServiceIds.isEmpty()) {
            return 0;
        }

        int totalRegistered = 0;
        for (Long serviceId : mcpServiceIds) {
            if (serviceId == null) {
                continue;
            }
            McpService service = resourceQueryService.findMcpServiceById(serviceId);
            if (service != null
                    && service.getLifeStatus() == AgentLifeStatus.PUBLISHED
                    && service.getStatus() == ProviderStatus.ACTIVE) {
                int registered = registerToolsFromMcpService(toolkit, serviceId, service.getEndpoint());
                totalRegistered += registered;
            } else {
                log.info("resolveMcpToolsForServiceIds: 服务不符合条件, serviceId={}, lifeStatus={}, status={}",
                        serviceId,
                        service != null ? service.getLifeStatus() : "null",
                        service != null ? service.getStatus() : "null");
            }
        }

        log.info("resolveMcpToolsForServiceIds: 完成加载, serviceCount={}, toolCount={}",
                mcpServiceIds.size(), totalRegistered);
        return totalRegistered;
    }

    /**
     * 从指定 MCP 服务动态加载工具。
     *
     * <p>通过 McpInvoker.listTools() 调用 MCP 服务的 tools/list 端点，
     * 将返回的每个工具包装为 AegisMcpTool 注册到 Toolkit。
     *
     * @param toolkit     目标 Toolkit 实例
     * @param mcpServiceId MCP 服务ID
     * @param endpoint    MCP 服务接入端点URL
     * @return 注册的工具数量
     */
    private int registerToolsFromMcpService(Toolkit toolkit, Long mcpServiceId, String endpoint) {
        if (mcpServiceId == null) {
            return 0;
        }

        try {
            log.info("registerToolsFromMcpService: 开始加载, serviceId={}, endpoint={}", mcpServiceId, endpoint);
            List<McpSchema.Tool> mcpTools = mcpInvoker.listTools(mcpServiceId.toString());
            if (mcpTools == null || mcpTools.isEmpty()) {
                log.warn("registerToolsFromMcpService: MCP服务无可用工具, serviceId={}", mcpServiceId);
                return 0;
            }

            int registered = 0;
            for (McpSchema.Tool mcpTool : mcpTools) {
                AegisMcpTool asTool = AegisMcpTool.of(
                        mcpInvoker, toolResultCache, mcpServiceId.toString(), mcpTool, endpoint, securityPolicyEngine);
                toolkit.registerAgentTool(asTool);
                registered++;
                log.info("registerToolsFromMcpService: 注册工具, serviceId={}, toolName={}, endpoint={}, description={}",
                        mcpServiceId, mcpTool.name(), endpoint,
                        mcpTool.description() != null && mcpTool.description().length() > 100
                                ? mcpTool.description().substring(0, 100) + "..."
                                : mcpTool.description());
            }

            log.info("registerToolsFromMcpService: MCP工具加载完成, serviceId={}, count={}, endpoint={}", mcpServiceId, registered, endpoint);
            return registered;
        } catch (Exception e) {
            log.error("registerToolsFromMcpService: MCP工具加载失败, serviceId={}, error={}", mcpServiceId, e.getMessage(), e);
            return 0;
        }
    }
}
