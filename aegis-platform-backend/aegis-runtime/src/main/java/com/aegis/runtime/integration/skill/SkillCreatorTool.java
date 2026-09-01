package com.aegis.runtime.integration.skill;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.util.SkillCodeGenerator;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 技能创建工具：意图识别、方法论萃取、结构化工件。
 *
 * <p>作为 skill_creator 的核心工具之一，负责将对话中用户的技能需求
 * 转化为结构化的技能元数据草稿。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCreatorTool {

    private final SkillMapper skillMapper;

    /**
     * 分析用户意图，识别技能创建/修改需求。
     */
    public IntentResult analyzeIntent(String userMessage) {
        IntentResult result = new IntentResult();
        result.setIntent(IntentResult.IntentType.UNKNOWN);
        
        if (userMessage == null) return result;
        String msg = userMessage.toLowerCase();
        
        if (msg.contains("创建") || msg.contains("新建") || msg.contains("create")) {
            result.setIntent(IntentResult.IntentType.CREATE);
        } else if (msg.contains("修改") || msg.contains("编辑") || msg.contains("update") || msg.contains("modify")) {
            result.setIntent(IntentResult.IntentType.MODIFY);
        } else if (msg.contains("调试") || msg.contains("测试") || msg.contains("debug") || msg.contains("test")) {
            result.setIntent(IntentResult.IntentType.DEBUG);
        } else if (msg.contains("打包") || msg.contains("交付") || msg.contains("package") || msg.contains("export")) {
            result.setIntent(IntentResult.IntentType.PACKAGE);
        }
        
        return result;
    }

    /**
     * 从对话内容中萃取方法论，生成 SKILL.md 正文草稿。
     */
    public String extractMethodology(String skillName, String skillPurpose, String keySteps, String inputs, String outputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(skillName != null ? skillName : "未命名技能").append("\n\n");
        sb.append("## 技能描述\n");
        sb.append(skillPurpose != null ? skillPurpose : "（待补充：技能的核心目标和使用场景）");
        sb.append("\n\n## 执行步骤\n");
        if (keySteps != null && !keySteps.isBlank()) {
            sb.append(keySteps);
        } else {
            sb.append("1. 接收输入参数\n2. 处理核心逻辑\n3. 生成输出结果");
        }
        sb.append("\n\n## 输入参数\n");
        sb.append(inputs != null ? inputs : "（待补充：JSON Schema 格式的输入定义）");
        sb.append("\n\n## 输出参数\n");
        sb.append(outputs != null ? outputs : "（待补充：JSON Schema 格式的输出定义）");
        return sb.toString();
    }

    /**
     * 结构化工件：创建技能草稿（scope 强制 LOCAL）。
     *
     * <p>P0 修复：原先使用 {@code Skill.builder()} 构建——Lombok @Builder 不会继承
     * 父类 {@code TenantEntity} 字段，导致 createTime/createBy/deleted 等审计字段缺失、
     * 部分默认值（tags/mappingConfig/subsCount 等）未初始化，插入产生脏数据。
     * 现改用无参构造 + 显式 set 全部字段（对齐 SkillCreatorOrchestrator.createDraftSkill）。
     */
    public SkillDraftResult structureArtifacts(Long tenantId, Long userId, String skillName,
                                                String description, String instructions,
                                                String inputs, String outputs, String bindingTools) {
        // U5: skillCode 统一由 SkillCodeGenerator 生成（原先本地实现保留中文+UUID后缀，
        // 与 admin/runtime 另两套生成器格式互不兼容，且中文 skillCode 用作 Toolkit
        // 注册名会被部分 LLM 供应商的工具名校验拒绝）
        String skillCode = SkillCodeGenerator.fromName(skillName);

        Long exists = skillMapper.selectCount(
            new QueryWrapper<Skill>()
                .eq("tenant_id", tenantId)
                .eq("skill_code", skillCode));
        if (exists != null && exists > 0) {
            return SkillDraftResult.builder()
                .success(false)
                .message("技能编码已存在: " + skillCode)
                .skillCode(skillCode)
                .build();
        }

        Skill skill = new Skill();
        skill.setTenantId(tenantId);
        skill.setSkillCode(skillCode);
        skill.setSkillName(skillName);
        skill.setDescription(description);
        skill.setSkillType(SkillType.COMPOSITE);
        skill.setCategory(SkillCategory.INTEGRATION);
        skill.setVisibility(Visibility.TENANT);
        skill.setScope(SkillScope.LOCAL);
        skill.setSecurityLevel(SecurityLevel.L2);
        skill.setLifeStatus(AgentLifeStatus.DRAFT);
        skill.setVersion("0.1.0");
        skill.setLatestVersion("0.1.0");
        skill.setActiveVersion("0.1.0");
        skill.setAuthorUserId(userId);
        skill.setInstructions(instructions);
        skill.setInputs(inputs != null ? inputs : "{}");
        skill.setOutputs(outputs != null ? outputs : "{}");
        skill.setBindingTools(bindingTools != null ? bindingTools : "[]");
        skill.setTags("[]");
        skill.setMappingConfig("{}");
        skill.setSubsCount(0);
        skill.setHealthScore(new java.math.BigDecimal("100.00"));
        skill.setIsSystem(false);
        skill.setCertified(false);
        skill.setCreateBy(userId);
        skill.setCreateTime(java.time.LocalDateTime.now());
        skill.setDeleted(0);

        skillMapper.insert(skill);
        log.info("技能草稿创建成功: skillId={}, skillCode={}, scope=LOCAL", skill.getId(), skillCode);

        return SkillDraftResult.builder()
            .success(true)
            .message("技能草稿创建成功")
            .skillId(skill.getId())
            .skillCode(skillCode)
            .build();
    }

    /**
     * 更新技能元数据。
     */
    public SkillDraftResult updateMetadata(Long skillId, Long userId, String description,
                                            String instructions, String inputs, String outputs) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return SkillDraftResult.builder().success(false).message("技能不存在").build();
        }
        if (skill.getAuthorUserId() != null && !skill.getAuthorUserId().equals(userId)) {
            return SkillDraftResult.builder().success(false).message("无权修改该技能").build();
        }
        
        if (description != null) skill.setDescription(description);
        if (instructions != null) skill.setInstructions(instructions);
        if (inputs != null) skill.setInputs(inputs);
        if (outputs != null) skill.setOutputs(outputs);
        
        skillMapper.updateById(skill);
        log.info("技能元数据更新成功: skillId={}", skillId);
        
        return SkillDraftResult.builder()
            .success(true)
            .message("技能元数据更新成功")
            .skillId(skillId)
            .skillCode(skill.getSkillCode())
            .build();
    }

    // ============ 内部类 ============
    
    @lombok.Data
    public static class IntentResult {
        public enum IntentType { CREATE, MODIFY, DEBUG, PACKAGE, UNKNOWN }
        private IntentType intent;
        private String targetSkillCode;
    }
    
    @lombok.Data
    @lombok.Builder
    public static class SkillDraftResult {
        private boolean success;
        private String message;
        private Long skillId;
        private String skillCode;
    }
}