package com.aegis.runtime.integration.skill;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCreatorInitializer implements CommandLineRunner {

    private static final String SKILL_CODE = "skill_creator";
    private static final String SKILL_NAME = "技能创造者";
    private static final String SKILL_DESC = "Aegis 平台元技能，通过自然语言对话创建、调试和交付技能";
    private static final String SKILL_VERSION = "1.0.0";

    private final SkillMapper skillMapper;

    @Override
    public void run(String... args) {
        if (skillMapper == null) {
            log.warn("SkillMapper 未就绪，跳过 skill_creator 初始化");
            return;
        }

        // skill_creator 为平台级系统技能（tenant_id=0）：以平台租户身份执行。
        // fail-closed 租户插件下，启动期租户上下文为空会导致 selectOne/insert
        // 抛"租户上下文缺失"异常，skill_creator 永远无法种子/自愈
        TenantContextHolder.bind(0L);
        try {
            Skill existing = skillMapper.selectOne(
                new QueryWrapper<Skill>()
                    .eq("skill_code", SKILL_CODE)
                    .last("LIMIT 1"));

            if (existing != null) {
                boolean needUpdate = false;
                // 已存在但缺少 inputs schema（旧版本初始化的数据）：
                // 补充 tool_call 参数定义，否则 LLM 看到的工具 schema 为空 object，无法正确传参
                if (existing.getInputs() == null || existing.getInputs().isBlank()) {
                    existing.setInputs(buildDefaultInputsSchema());
                    needUpdate = true;
                }
                // instructions 版本升级：旧 instructions 未包含"禁止调用 generate_file"约束，
                // 导致 LLM 在 skill_creator 流程中误调 generate_file 生成文件。检测到旧版本时强制刷新。
                String instr = existing.getInstructions();
                if (instr == null || !instr.contains("禁止调用 generate_file")) {
                    existing.setInstructions(buildDefaultInstructions());
                    needUpdate = true;
                    log.info("skill_creator 检测到旧版 instructions，升级为含 generate_file 约束版本");
                }
                if (needUpdate) {
                    skillMapper.updateById(existing);
                    log.info("skill_creator 已存在（id={}），已更新字段", existing.getId());
                } else {
                    log.info("skill_creator 已存在（id={}），跳过初始化", existing.getId());
                }
                return;
            }

            Skill skill = Skill.builder()
                .skillCode(SKILL_CODE)
                .skillName(SKILL_NAME)
                .description(SKILL_DESC)
                .skillType(SkillType.COMPOSITE)
                .category(SkillCategory.INTEGRATION)
                .visibility(Visibility.PUBLIC)
                .scope(SkillScope.GLOBAL)
                .securityLevel(SecurityLevel.L2)
                .lifeStatus(AgentLifeStatus.PUBLISHED)
                .version(SKILL_VERSION)
                .activeVersion(SKILL_VERSION)
                .latestVersion(SKILL_VERSION)
                .isSystem(true)
                .certified(true)
                .instructions(buildDefaultInstructions())
                .triggerExamples(buildDefaultTriggerExamples())
                .inputs(buildDefaultInputsSchema())
                .build();

            skillMapper.insert(skill);
            log.info("skill_creator 初始化完成：id={}, scope=GLOBAL, isSystem=true", skill.getId());
            log.info("skill_creator 特性：所有用户自动加载，无需订阅，仅技术人员可修改 scope");
        } catch (Exception e) {
            log.error("skill_creator 初始化失败", e);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * skill_creator 的 tool_call 参数 schema（对齐 SkillCreatorOrchestrator.handleSkillCreator 的取参逻辑）。
     *
     * <p>skill_creator 注册为 AgentScope Tool 后，LLM 依据此 schema 生成 tool_call 参数；
     * 缺失时 parseInputSchema 回退为空 object，LLM 无法得知应传 action/skillName 等参数。
     */
    private String buildDefaultInputsSchema() {
        return """
            {
              "type": "object",
              "description": "技能创建/修改/调试/打包/提交审核的编排参数",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["CREATE", "MODIFY", "DEBUG", "PACKAGE", "SUBMIT", "QUERY"],
                  "description": "要执行的动作：创建技能(CREATE)、修改技能(MODIFY)、调试技能(DEBUG)、打包交付(PACKAGE)、提交审核(SUBMIT)、查询元数据(QUERY)，默认 CREATE"
                },
                "skillName": {
                  "type": "string",
                  "description": "技能名称，CREATE 时必填，如：SQL生成技能"
                },
                "description": {
                  "type": "string",
                  "description": "技能描述：核心目标和使用场景"
                },
                "instructions": {
                  "type": "string",
                  "description": "方法论正文（SKILL.md body），包含技能的执行步骤、方法论"
                },
                "inputs": {
                  "type": "string",
                  "description": "技能输入参数定义，JSON Schema 格式字符串"
                },
                "outputs": {
                  "type": "string",
                  "description": "技能输出参数定义，JSON Schema 格式字符串"
                },
                "bindingTools": {
                  "type": "string",
                  "description": "技能绑定的工具清单，JSON 格式字符串"
                },
                "category": {
                  "type": "string",
                  "description": "技能分类：CONTENT/INTEGRATION/ANALYSIS/OTHER"
                },
                "securityLevel": {
                  "type": "string",
                  "description": "安全等级：L1/L2/L3"
                },
                "skillId": {
                  "type": "number",
                  "description": "技能ID，MODIFY/DEBUG/PACKAGE/SUBMIT 时用于定位目标技能"
                }
              }
            }
            """;
    }

    private String buildDefaultInstructions() {
        return """
            # 技能创造者 (skill_creator)

            你是 Aegis 平台的元技能，负责通过自然语言对话创建、调试和交付技能。

            ## 核心能力
            1. **技能创建**：与用户对话，识别意图，萃取方法论，结构化为技能工件
            2. **技能调试**：试运行技能，评估结果，诊断问题，迭代优化
            3. **技能交付**：生成 SKILL.md 元数据，打包为压缩包，输出最终产物

            ## 工作流程
            当用户说"创建一个技能"、"修改技能"、"调试技能"或"打包技能"时，
            按以下阶段逐步执行：

            ### 阶段一：意图识别
            - 分析用户需求，明确技能的目标、输入、输出
            - 与用户确认关键参数

            ### 阶段二：方法论萃取
            - 从对话中提取技能的核心操作范式
            - 构建技能的 SKILL.md 结构

            ### 阶段三：结构化工件
            - 生成技能元数据（名称、描述、输入输出 schema）
            - 绑定必要的工具

            ### 阶段四：调试与交付
            - 试运行技能并输出结果
            - 根据反馈迭代优化
            - 最终生成可下载的技能包

            ## 重要约束（必须遵守）
            - **禁止调用 generate_file 工具生成技能文件**：SKILL.md / skill.json / README.md 由平台在 skill_creator CREATE 动作完成时自动生成，并通过 skill.draft.created 事件下发前端右侧面板，无需也不应使用 generate_file。
            - 你只需调用 skill_creator 工具（action=CREATE/MODIFY/DEBUG/SUBMIT），平台会自动完成文件生成。
            - scope 字段强制为 LOCAL，用户不可修改
            - 所有创建的技能需通过安全扫描
            - 交付产物为标准 .zip 压缩包（由 PACKAGE 动作生成，非 generate_file）
            """;
    }

    private String buildDefaultTriggerExamples() {
        return """
            [
              {"pattern": "创建.*技能", "intent": "CREATE", "description": "创建新技能"},
              {"pattern": "新建.*技能", "intent": "CREATE", "description": "创建新技能"},
              {"pattern": "修改.*技能", "intent": "MODIFY", "description": "修改已有技能"},
              {"pattern": "编辑.*技能", "intent": "MODIFY", "description": "修改已有技能"},
              {"pattern": "调试.*技能", "intent": "DEBUG", "description": "调试技能"},
              {"pattern": "测试.*技能", "intent": "DEBUG", "description": "调试技能"},
              {"pattern": "打包.*技能", "intent": "PACKAGE", "description": "打包交付技能"},
              {"pattern": "交付.*技能", "intent": "PACKAGE", "description": "打包交付技能"}
            ]
            """;
    }
}