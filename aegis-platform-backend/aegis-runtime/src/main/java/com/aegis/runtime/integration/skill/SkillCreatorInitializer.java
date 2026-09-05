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
                if (instr == null || !instr.contains("SUBMIT 提交审核时机")) {
                    existing.setInstructions(buildDefaultInstructions());
                    needUpdate = true;
                    log.info("skill_creator 检测到旧版 instructions，升级为含 SUBMIT 时机约束版本");
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
            2. **技能修改**：用 MODIFY 补充/修正已有技能的 description/instructions/绑定工具等字段
            3. **技能调试**：用 DEBUG 做静态检查和安全扫描，诊断 instructions 是否为空、工具是否绑定等问题
            4. **技能提交审核**：用 SUBMIT 走审核流程，提交时会自动升级版本号并写快照

            ## 完整输出约束（CREATE 动作必须遵守）

            每次 CREATE 新技能，你必须输出以下完整字段：
            - skillName：技能名称（简洁，≤20 字）
            - description：一句话描述技能做什么（≤100 字）
            - instructions：核心方法论正文（可多段，≥3 行。这是技能的灵魂——描述执行步骤、决策规则、边界条件）
            - inputs：JSON Schema 格式的输入参数定义（字符串化 JSON），至少包含 required 数组
            - outputs：JSON Schema 格式的输出参数定义（字符串化 JSON）
            - bindingTools：绑定工具清单（JSON 数组字符串，如 ["web_search", "shell"]）
            - securityLevel：根据技能内容判断（处理用户敏感数据→L3；通用工具→L2；纯内容生成→L1）
            - skillType：COMPOSITE（组合技能，调用其他工具）或 ATOMIC（原子技能，纯 Prompt）
            - category：从 CONTENT / INTEGRATION / ANALYSIS / DATA / COMPUTE 中选
            - scope：强制 LOCAL，不可改

            如果某个字段你暂时不确定，也要给一个合理的默认值，而不是省略。
            SKILL.md / skill.json / README.md 会由平台自动生成并通过 skill.draft.created 事件下发前端，
            你**绝对不需要**调用 generate_file 工具自己创建文件。

            ## SUBMIT 提交审核时机（必须遵守）

            - **只有当用户显式说出"提交审核"、"提交"、"publish"、"发布"时，才调 SUBMIT**
            - CREATE 后、用户没说要提交——**不要自动 SUBMIT**。给用户时间在右侧面板确认、调试、保存后再显式发起
            - SUBMIT 有 60 秒时间窗口保护：技能创建未满 60 秒时调用 SUBMIT 会被后端拒绝
            - 如果用户只是创建后想看看预览——那就是 CREATE 完了，流程结束，等用户说下一步

            ## 工作流程示例

            用户说"帮我创建宝宝起名技能，男孩，姓张，2024 年出生"
            1. 调 CREATE → 传完整 fields（skillName/description/instructions/inputs/outputs/bindingTools/securityLevel 等）
            2. 返回后引导用户："草稿已创建，右侧面板可预览文件和调整内容，确认无误后告诉我'提交审核'我就帮你提交"
            3. **不要自动 SUBMIT**——等用户说
            4. 用户说"提交审核" → 才调 SUBMIT
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