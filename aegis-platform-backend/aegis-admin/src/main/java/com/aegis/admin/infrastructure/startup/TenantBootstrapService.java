package com.aegis.admin.infrastructure.startup;

import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.agent.AgentType;
import com.aegis.core.enums.agent.BindingType;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.domain.tenant.Tenant;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.agent.MemoryStrategy;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.dal.mapper.tenant.TenantMapper;
import com.aegis.dal.mapper.agent.AgentBindingMapper;
import com.aegis.dal.mapper.agent.AgentConfigMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.ToolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 租户级智能体引导服务。
 *
 * <p>平台启动时，为每个租户幂等补齐「通用智能体」单例（UNIVERSAL）：
 * <ul>
 *   <li>每租户恰好一个，由平台预置与维护默认配置，用户不可创建 / 不可编辑 / 不可删除；</li>
 *   <li>初始即 PUBLISHED，全员可见、立即可用，无需走审核；</li>
 *   <li>自身零业务资源绑定，每个用户调用时由运行时按用户隔离域注入各自资源。</li>
 * </ul>
 *
 * <p>幂等：以「租户内 agentType=UNIVERSAL 唯一」判重，已存在则跳过。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantBootstrapService implements ApplicationRunner {

    private static final String UNIVERSAL_CODE = "universal";
    private static final String UNIVERSAL_NAME = "通用智能体";
    private static final String UNIVERSAL_PROMPT =
            "你是一个企业通用智能助手，基于用户自身绑定到本智能体的技能、知识与工具回答问题；"
                    + "如用户未绑定任何资源，则基于通用知识作答，并提示用户可在个人设置中绑定自己的资源。";

    private final TenantMapper tenantMapper;
    private final AgentDefMapper agentDefMapper;
    private final AgentConfigMapper agentConfigMapper;
    private final ToolMapper toolMapper;
    private final SkillMapper skillMapper;
    private final AgentBindingMapper agentBindingMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Tenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<Tenant>());
            if (tenants == null || tenants.isEmpty()) {
                log.info("TenantBootstrap: no tenants found, skip universal agent seeding");
                return;
            }
            for (Tenant tenant : tenants) {
                ensureUniversalAgent(tenant.getId());
            }
            log.info("TenantBootstrap: universal agent seeding completed for {} tenant(s)", tenants.size());
        } catch (Exception e) {
            // 引导失败不应阻断应用启动
            log.error("TenantBootstrap: failed to seed universal agents, error={}", e.getMessage(), e);
        }
    }

    /**
     * 为指定租户幂等创建通用智能体单例。
     *
     * <p>幂等判别以「租户内 agentCode=UNIVERSAL_CODE("universal") 唯一」为准：
     * 通用智能体由本服务独占创建与维护（01_schema.sql 已不再预置），
     * 唯一索引 uk_agent_def_code(tenant_id, agent_code) 保证单例，避免重复创建。
     */
    public void ensureUniversalAgent(Long tenantId) {
        // 多租户拦截器会按当前线程租户上下文追加 tenant_id 过滤条件；bootstrap 在启动期执行时
        // 默认上下文为空，拦截器会补全 tenant_id=0，导致判重失效、重复插入。此处显式绑定租户上下文，
        // 使拦截器补全的 tenant_id 与待查/待插数据一致，保证幂等单例。
        TenantContextHolder.bind(tenantId);
        try {
            AgentDef def = agentDefMapper.selectOne(new LambdaQueryWrapper<AgentDef>()
                    .eq(AgentDef::getTenantId, tenantId)
                    .eq(AgentDef::getAgentCode, UNIVERSAL_CODE));
            if (def == null) {
                def = AgentDef.builder()
                    .agentCode(UNIVERSAL_CODE)
                    .agentName(UNIVERSAL_NAME)
                    .agentType(AgentType.UNIVERSAL)
                    .description("平台预置的通用智能助手，绑定你自己的资源后即可获得个性化能力。")
                    .category("通用")
                    .governanceTier(GovernanceTier.STANDARD)
                    .lifeStatus(AgentLifeStatus.PUBLISHED)
                    .version("1.0.0")
                    .authorUserId(0L)
                    .subsCount(0)
                    .visibility(Visibility.TENANT)
                    .lockVersion(0)
                    .build();
                def.setTenantId(tenantId);
                agentDefMapper.insert(def);

                AgentConfig config = AgentConfig.builder()
                        .agentId(def.getId())
                        .version(def.getVersion())
                        .systemPrompt(UNIVERSAL_PROMPT)
                        .modelTier(ModelTier.STANDARD)
                        .temperature(BigDecimal.valueOf(0.7))
                        .memoryStrategy(MemoryStrategy.SESSION_LEVEL)
                        .maxTurns(20)
                        .enabledTools("[]")
                        .build();
                config.setTenantId(tenantId);
                agentConfigMapper.insert(config);
                log.info("TenantBootstrap: universal agent created for tenantId={}, agentId={}", tenantId, def.getId());
            }

            // 无论智能体是新创建还是已存在，每次启动都重新绑定平台内建 TOOL + SKILL（幂等）。
            // 这样当平台新增内建资源后重启服务，通用智能体会自动获得新绑定。
            seedUniversalBuiltinToolBindings(tenantId, def.getId());
            seedUniversalBuiltinSkillBindings(tenantId, def.getId());
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * 为通用智能体幂等绑定平台内置 TOOL（基线能力）。
     *
     * <p><b>设计约束（产品确认）</b>：通用智能体仅静态绑定「平台级内置工具」
     * （{@code res_tool.source_type=BUILTIN} 且 {@code status=NORMAL}）作为所有用户的统一基线；
     * MCP 与知识库不在此处静态绑定，由运行时按用户订阅 / 授权资源动态加载
     * （见 {@code AgentPoolManager.mergeDynamicBindings}）。
     *
     * <p><b>幂等</b>：按 (agent_id, resource_type, resource_id) 判重，已绑定则跳过。
     * {@code res_tool} 在租户拦截器忽略表中，跨租户共享，查询不受 tenant 过滤影响。
     *
     * @param tenantId 租户ID（绑定的 agent_binding 归属该租户）
     * @param agentId  通用智能体ID
     */
    private void seedUniversalBuiltinToolBindings(Long tenantId, Long agentId) {
        List<Tool> builtinTools = toolMapper.selectList(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getSourceType, ToolSourceType.BUILTIN)
                        .eq(Tool::getStatus, CommonStatus.NORMAL));
        if (builtinTools == null || builtinTools.isEmpty()) {
            log.warn("TenantBootstrap: no BUILTIN/NORMAL tools found, skip tool binding: agentId={}", agentId);
            return;
        }
        int newlySeeded = 0;
        for (Tool tool : builtinTools) {
            Long existing = agentBindingMapper.selectCount(
                    new LambdaQueryWrapper<AgentBinding>()
                            .eq(AgentBinding::getAgentId, agentId)
                            .eq(AgentBinding::getResourceType, ResourceType.TOOL)
                            .eq(AgentBinding::getResourceId, tool.getId()));
            if (existing != null && existing > 0) {
                continue;
            }
            AgentBinding binding = AgentBinding.builder()
                    .agentId(agentId)
                    .agentVersion("1.0.0")
                    .resourceType(ResourceType.TOOL)
                    .resourceId(tool.getId())
                    .resourceVersion("latest")
                    .bindingType(BindingType.FIXED)
                    .enabled(true)
                    .build();
            binding.setTenantId(tenantId);
            agentBindingMapper.insert(binding);
            newlySeeded++;
        }
        log.info("TenantBootstrap: universal builtin tool bindings ensured: agentId={}, total={}, newlySeeded={}",
                agentId, builtinTools.size(), newlySeeded);
    }

    /**
     * 为通用智能体幂等绑定平台级内建 SKILL（基线能力，如 skill_creator）。
     *
     * <p>查询条件：{@code res_skill.is_system=1}（系统内建）且 {@code life_status=PUBLISHED}。
     * 通用智能体零业务资源（MCP/KB），仅绑定系统 TOOL + 系统 SKILL 作为基线。
     *
     * <p><b>幂等</b>：按 (agent_id, resource_type=SKILL, resource_id) 判重。
     */
    private void seedUniversalBuiltinSkillBindings(Long tenantId, Long agentId) {
        List<Skill> systemSkills = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getIsSystem, true)
                        .eq(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED));
        if (systemSkills == null || systemSkills.isEmpty()) {
            log.warn("TenantBootstrap: no system/PUBLISHED skills found, skip skill binding: agentId={}", agentId);
            return;
        }
        int newlySeeded = 0;
        for (Skill skill : systemSkills) {
            Long existing = agentBindingMapper.selectCount(
                    new LambdaQueryWrapper<AgentBinding>()
                            .eq(AgentBinding::getAgentId, agentId)
                            .eq(AgentBinding::getResourceType, ResourceType.SKILL)
                            .eq(AgentBinding::getResourceId, skill.getId()));
            if (existing != null && existing > 0) {
                continue;
            }
            AgentBinding binding = AgentBinding.builder()
                    .agentId(agentId)
                    .agentVersion("1.0.0")
                    .resourceType(ResourceType.SKILL)
                    .resourceId(skill.getId())
                    .resourceVersion("latest")
                    .bindingType(BindingType.FIXED)
                    .enabled(true)
                    .build();
            binding.setTenantId(tenantId);
            agentBindingMapper.insert(binding);
            newlySeeded++;
        }
        log.info("TenantBootstrap: universal builtin skill bindings ensured: agentId={}, total={}, newlySeeded={}",
                agentId, systemSkills.size(), newlySeeded);
    }
}
