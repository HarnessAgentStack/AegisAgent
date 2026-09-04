package com.aegis.runtime.integration.skill;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.common.ReviewStatus;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.security.SkillContentScanner;
import com.aegis.core.util.SkillCodeGenerator;
import com.aegis.core.util.XssSanitizer;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.ResourceReviewMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能创建编排器（skill_creator 核心执行引擎）。
 *
 * <p>当 LLM 在对话中调用 {@code skill_creator} 工具时，
 * 本编排器负责：识别意图 → 创建/更新草稿 → 收集 SSE 事件 → 返回结果。
 *
 * <h3>事件收集方式</h3>
 * <p>使用外部传入的 {@code List<AgentEvent>} 收集事件，避免 ThreadLocal 在线程切换时失效。
 * 调用方负责创建事件列表并在编排完成后读取事件。
 *
 * <h3>与 SkillCreatorTool 的关系</h3>
 * <p>{@code SkillCreatorTool} 是低级工具（直接操作 DB），
 * 本编排器在其之上封装了意图识别、阶段推进、事件发射等高层编排逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCreatorOrchestrator {

    private final SkillMapper skillMapper;
    private final SkillPackagerTool skillPackagerTool;
    private final ResourceReviewMapper resourceReviewMapper;
    private final SkillContentScanner skillContentScanner;

    /**
     * 处理 skill_creator 工具调用。
     *
     * <p>根据 LLM 传入的 action 和参数，执行对应的编排逻辑，
     * 将事件收集到外部传入的事件列表中。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @param inputs   LLM 传入的参数（含 action, skillName, description 等）
     * @param events   事件收集列表（由调用方创建并持有引用）
     * @return 执行结果（包含 skillId, success 等）
     */
    public Map<String, Object> handleSkillCreator(Long tenantId, Long userId,
                                                   Map<String, Object> inputs,
                                                   List<AgentEvent> events) {
        String action = getString(inputs, "action", getString(inputs, "intent", "CREATE"));
        log.info("skill_creator 编排: tenantId={}, userId={}, action={}, inputs={}",
                tenantId, userId, action, inputs);

        return switch (action.toUpperCase()) {
            case "CREATE", "CREATE_DRAFT" -> handleCreate(tenantId, userId, inputs, events);
            case "MODIFY", "UPDATE", "EDIT" -> handleModify(tenantId, userId, inputs, events);
            case "DEBUG", "TEST" -> handleDebug(tenantId, userId, inputs, events);
            case "PACKAGE", "EXPORT" -> handlePackage(tenantId, userId, inputs, events);
            case "SUBMIT", "SUBMIT_REVIEW" -> handleSubmit(tenantId, userId, inputs, events);
            case "GET_METADATA", "QUERY" -> handleQuery(inputs, events);
            default -> handleCreate(tenantId, userId, inputs, events);
        };
    }

    // ============ 核心动作 ============

    private Map<String, Object> handleCreate(Long tenantId, Long userId,
                                              Map<String, Object> inputs,
                                              List<AgentEvent> events) {
        emitStage(events, "analyzing", "正在分析技能需求...", 10);

        String skillName = getString(inputs, "skillName", getString(inputs, "name", "新技能"));
        String description = getString(inputs, "description", getString(inputs, "desc", ""));
        String instructions = getString(inputs, "instructions", "");
        String category = getString(inputs, "category", SkillCategory.INTEGRATION.name());
        String bindingTools = getString(inputs, "bindingTools", "");
        String inputs_schema = getString(inputs, "inputs", "");
        String outputs = getString(inputs, "outputs", "");

        emitStage(events, "structuring", "正在结构化工件...", 30);

        // U5: skillCode 统一由 SkillCodeGenerator 生成（三套实现合一）
        String skillCode = SkillCodeGenerator.fromName(skillName);

        // 统一创建逻辑（版本号 0.0.1、XSS、编码唯一、默认值）
        Skill skill;
        try {
            skill = createDraftSkill(
                    tenantId, userId,
                    skillCode, skillName,
                    // 对话创建的是编排式技能
                    SkillType.COMPOSITE,
                    parseCategory(category),
                    // 对话创建默认 L2（保持原 runtime 行为，由用户调整）
                    SecurityLevel.L2);
        } catch (com.aegis.core.common.error.BusinessException e) {
            // 编码已存在等情况，返回友好提示
            emitStage(events, "completed", e.getMessage(), 100);
            return buildResultMap(false, e.getMessage(), null, skillCode).build();
        }

        // ========== runtime 对话特有字段补充 ==========
        if (description != null && !description.isEmpty()) {
            skill.setDescription(description);
        }
        if (instructions != null && !instructions.isEmpty()) {
            skill.setInstructions(instructions);
        }
        if (bindingTools != null && !bindingTools.isEmpty()) {
            skill.setBindingTools(bindingTools);
        }
        if (inputs_schema != null && !inputs_schema.isEmpty()) {
            skill.setInputs(inputs_schema);
        }
        if (outputs != null && !outputs.isEmpty()) {
            skill.setOutputs(outputs);
        }
        // scope 保持 LOCAL（原 runtime 行为）
        skill.setScope(SkillScope.LOCAL);
        skillMapper.updateById(skill);

        log.info("技能草稿创建 (unified via runtime): id={}, code={}, userId={}", skill.getId(), skillCode, userId);

        emitStage(events, "completed", "技能草稿创建成功", 100);
        emitDraftCreated(events, skill);

        log.info("skill_creator CREATE 完成: skillId={}, 已自动生成 SKILL.md/skill.json/README.md，明确告知 LLM 无需再调用 generate_file", skill.getId());
        return buildResultMap(true, "技能草稿创建成功。SKILL.md 与依赖文件已由平台自动生成并通过 skill.draft.created 事件下发前端，无需调用 generate_file 工具。接下来可引导用户调试或提交审核。", skill.getId(), skill.getSkillCode())
                .appendData("skillName", skill.getSkillName())
                .appendData("description", description)
                .appendData("instructions", instructions)
                .appendData("skillType", SkillType.COMPOSITE.name())
                .appendData("category", category)
                .appendData("securityLevel", SecurityLevel.L2.name())
                .appendData("scope", SkillScope.LOCAL.name())
                .appendData("bindingTools", bindingTools)
                .appendData("filesAutoGenerated", true)
                .build();
    }

    private Map<String, Object> handleModify(Long tenantId, Long userId,
                                              Map<String, Object> inputs,
                                              List<AgentEvent> events) {
        Long skillId = getLong(inputs, "skillId");
        if (skillId == null) {
            String skillCode = getString(inputs, "skillCode", "");
            if (!skillCode.isEmpty()) {
                Skill s = skillMapper.selectOne(new QueryWrapper<Skill>()
                        .eq("skill_code", skillCode).last("LIMIT 1"));
                if (s != null) skillId = s.getId();
            }
        }
        if (skillId == null) {
            return buildErrorResult("未指定 skillId 或 skillCode");
        }

        emitStage(events, "updating", "正在更新技能元数据...", 30);

        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) return buildErrorResult("技能不存在: " + skillId);

        String desc = getString(inputs, "description", null);
        String ins = getString(inputs, "instructions", null);
        String bindTools = getString(inputs, "bindingTools", null);
        String skillName = getString(inputs, "skillName", null);
        String category = getString(inputs, "category", null);
        String secLevel = getString(inputs, "securityLevel", null);

        // 使用本地方法的增量更新（带 XSS 清洗 + 变更检测）
        boolean updated = patchSkillFields(
                skill,
                skillName,
                desc,
                ins,
                category != null ? parseCategory(category) : null,
                secLevel != null ? SecurityLevel.valueOf(secLevel) : null,
                bindTools);

        if (updated) {
            emitStage(events, "completed", "技能元数据更新成功", 100);
            emitDraftUpdated(events, skill);
        } else {
            emitStage(events, "completed", "无需更新", 100);
        }

        return buildResultMap(true, "更新完成", skill.getId(), skill.getSkillCode())
                .appendData("skillName", skill.getSkillName())
                .appendData("description", skill.getDescription())
                .appendData("instructions", skill.getInstructions())
                .appendData("bindingTools", skill.getBindingTools() != null ? skill.getBindingTools() : "")
                .build();
    }

    private Map<String, Object> handleDebug(Long tenantId, Long userId,
                                             Map<String, Object> inputs,
                                             List<AgentEvent> events) {
        Long skillId = getLong(inputs, "skillId");
        if (skillId == null) {
            return buildErrorResult("未指定 skillId");
        }
        emitStage(events, "debugging", "正在调试技能...", 50);
        emitDebugResult(events, skillId, true, "调试完成（模拟执行）");
        return buildResultMap(true, "调试完成", skillId, null)
                .appendData("debugSuccess", true)
                .appendData("debugMessage", "调试完成")
                .build();
    }

    private Map<String, Object> handlePackage(Long tenantId, Long userId,
                                                Map<String, Object> inputs,
                                                List<AgentEvent> events) {
        Long skillId = getLong(inputs, "skillId");
        if (skillId == null) {
            return buildErrorResult("未指定 skillId");
        }
        emitStage(events, "packaging", "正在打包技能...", 50);
        String fileName = "skill_" + skillId + ".zip";
        emitPackageResult(events, skillId, true, fileName);
        return buildResultMap(true, "打包完成", skillId, null)
                .appendData("fileName", fileName)
                .appendData("packageSuccess", true)
                .build();
    }

    /**
     * 提交审核：创建 ResourceReview 审核记录并更新技能状态，支持幂等。
     * 提交前执行 {@link SkillContentScanner} 安全扫描，HIGH 级风险直接阻断提交，扫描结果写入审核单。
     */
    private Map<String, Object> handleSubmit(Long tenantId, Long userId,
                                              Map<String, Object> inputs,
                                              List<AgentEvent> events) {
        Long skillId = getLong(inputs, "skillId");
        if (skillId == null) {
            return buildErrorResult("未指定 skillId");
        }
        emitStage(events, "submitting", "正在提交审核...", 50);

        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) return buildErrorResult("技能不存在: " + skillId);

        // 用户级权限校验
        if (skill.getAuthorUserId() != null && !skill.getAuthorUserId().equals(userId)) {
            return buildErrorResult("无权提交他人创建的技能");
        }

        // 状态校验：仅 DRAFT/REJECTED 可提交审核
        if (skill.getLifeStatus() != AgentLifeStatus.DRAFT
                && skill.getLifeStatus() != AgentLifeStatus.REJECTED) {
            if (skill.getLifeStatus() == AgentLifeStatus.REVIEWING) {
                emitStage(events, "completed", "已在审核中", 100);
                return buildResultMap(true, "技能已在审核中", skillId, skill.getSkillCode())
                        .appendData("submitted", true)
                        .build();
            }
            return buildErrorResult("当前状态不可提交审核: " + skill.getLifeStatus());
        }

        // 安全扫描（不静默，HIGH 风险阻断提交）
        SkillContentScanner.ScanResult scanResult = skillContentScanner.scan(skill);
        if (!scanResult.isPassed()) {
            log.warn("技能提交审核被安全扫描阻断: skillId={}, summary={}", skillId, scanResult.getSummary());
            emitStage(events, "failed", "安全扫描未通过，提交已阻断", 100);
            Map<String, Object> blocked = buildErrorResult("安全扫描未通过（P0 风险阻断）: " + scanResult.getSummary());
            blocked.put("scanResult", scanResult);
            return blocked;
        }

        // 幂等检查：同一资源已有 PENDING 审核时直接返回
        ResourceReview existingReview = resourceReviewMapper.selectOne(
                new QueryWrapper<ResourceReview>()
                        .eq("resource_type", ResourceType.SKILL.name())
                        .eq("resource_id", skillId)
                        .eq("review_status", ReviewStatus.PENDING.name())
                        .orderByDesc("id")
                        .last("LIMIT 1"));

        if (existingReview != null) {
            // 确保技能状态正确
            if (skill.getLifeStatus() != AgentLifeStatus.REVIEWING) {
                skill.setLifeStatus(AgentLifeStatus.REVIEWING);
                skillMapper.updateById(skill);
            }
            emitStage(events, "completed", "已提交审核", 100);
            return buildResultMap(true, "提交审核成功（已有待审核单）", skillId, skill.getSkillCode())
                    .appendData("submitted", true)
                    .appendData("reviewId", existingReview.getId())
                    .build();
        }

        // 创建审核记录（附扫描结果，供审核员查看）
        ResourceReview review = ResourceReview.builder()
                .resourceType(ResourceType.SKILL)
                .resourceId(skillId)
                .resourceName(skill.getSkillName())
                .resourceVersion(skill.getVersion())
                .applicantUserId(userId)
                .securityLevel(skill.getSecurityLevel() != null ? skill.getSecurityLevel().getLevel() : null)
                .reviewStatus(ReviewStatus.PENDING)
                .submitTime(LocalDateTime.now())
                .build();
        review.setTenantId(tenantId);
        review.setScanResult(toScanJson(scanResult));
        resourceReviewMapper.insert(review);

        // 更新技能状态为审核中
        skill.setLifeStatus(AgentLifeStatus.REVIEWING);
        skillMapper.updateById(skill);

        log.info("技能提交审核: skillId={}, skillCode={}, reviewId={}, userId={}, scanPassed={}",
                skillId, skill.getSkillCode(), review.getId(), userId, scanResult.isPassed());

        emitStage(events, "completed", "已提交审核", 100);
        return buildResultMap(true, "提交审核成功", skillId, skill.getSkillCode())
                .appendData("submitted", true)
                .appendData("reviewId", review.getId())
                .build();
    }

    private Map<String, Object> handleQuery(Map<String, Object> inputs,
                                             List<AgentEvent> events) {
        Long skillId = getLong(inputs, "skillId");
        if (skillId == null) {
            String skillCode = getString(inputs, "skillCode", "");
            if (!skillCode.isEmpty()) {
                Skill s = skillMapper.selectOne(new QueryWrapper<Skill>()
                        .eq("skill_code", skillCode).last("LIMIT 1"));
                if (s != null) skillId = s.getId();
            }
        }
        if (skillId == null) return buildErrorResult("未找到技能");

        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) return buildErrorResult("技能不存在: " + skillId);

        emitDraftUpdated(events, skill);

        return buildResultMap(true, "查询成功", skill.getId(), skill.getSkillCode())
                .appendData("skillName", skill.getSkillName())
                .appendData("description", skill.getDescription())
                .appendData("instructions", skill.getInstructions())
                .appendData("skillType", skill.getSkillType() != null ? skill.getSkillType().name() : null)
                .appendData("category", skill.getCategory() != null ? skill.getCategory().name() : null)
                .appendData("securityLevel", skill.getSecurityLevel() != null ? skill.getSecurityLevel().name() : null)
                .appendData("scope", skill.getScope() != null ? skill.getScope().name() : null)
                .appendData("bindingTools", skill.getBindingTools() != null ? skill.getBindingTools() : "")
                .appendData("lifeStatus", skill.getLifeStatus() != null ? skill.getLifeStatus().name() : null)
                .build();
    }

    // ============ 事件发射 ============

    private void emitStage(List<AgentEvent> events, String phase, String description, int progress) {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", phase);
        data.put("description", description);
        data.put("progress", progress);
        events.add(AgentEvent.of("skill.creator.stage", data));
    }

    private void emitDraftCreated(List<AgentEvent> events, Skill skill) {
        Map<String, Object> data = new HashMap<>();
        data.put("skillId", skill.getId() != null ? String.valueOf(skill.getId()) : null);
        data.put("skillCode", skill.getSkillCode());
        data.put("skillName", skill.getSkillName());
        data.put("description", skill.getDescription());
        data.put("instructions", skill.getInstructions());
        data.put("skillType", skill.getSkillType() != null ? skill.getSkillType().name() : null);
        data.put("category", skill.getCategory() != null ? skill.getCategory().name() : null);
        data.put("securityLevel", skill.getSecurityLevel() != null ? skill.getSecurityLevel().name() : null);
        data.put("scope", skill.getScope() != null ? skill.getScope().name() : null);
        data.put("bindingTools", skill.getBindingTools() != null ? skill.getBindingTools() : "");
        // 返回技能文件树数据
        data.put("files", buildSkillFileTree(skill));
        events.add(AgentEvent.of("skill.draft.created", data));
    }

    private void emitDraftUpdated(List<AgentEvent> events, Skill skill) {
        Map<String, Object> data = new HashMap<>();
        data.put("skillId", skill.getId() != null ? String.valueOf(skill.getId()) : null);
        data.put("skillCode", skill.getSkillCode());
        data.put("skillName", skill.getSkillName());
        data.put("description", skill.getDescription());
        data.put("instructions", skill.getInstructions());
        data.put("skillType", skill.getSkillType() != null ? skill.getSkillType().name() : null);
        data.put("category", skill.getCategory() != null ? skill.getCategory().name() : null);
        data.put("securityLevel", skill.getSecurityLevel() != null ? skill.getSecurityLevel().name() : null);
        data.put("scope", skill.getScope() != null ? skill.getScope().name() : null);
        data.put("bindingTools", skill.getBindingTools() != null ? skill.getBindingTools() : "");
        // 返回技能文件树数据
        data.put("files", buildSkillFileTree(skill));
        events.add(AgentEvent.of("skill.draft.updated", data));
    }

    private void emitDebugResult(List<AgentEvent> events, Long skillId, boolean success, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("skillId", skillId != null ? String.valueOf(skillId) : null);
        data.put("success", success);
        data.put("message", message);
        events.add(AgentEvent.of("skill.debug.result", data));
    }

    private void emitPackageResult(List<AgentEvent> events, Long skillId, boolean success, String fileName) {
        Map<String, Object> data = new HashMap<>();
        data.put("skillId", skillId != null ? String.valueOf(skillId) : null);
        data.put("success", success);
        data.put("fileName", fileName);
        events.add(AgentEvent.of("skill.package.result", data));
    }

    // ============ 工具方法 ============

    /**
     * 构建技能文件树。
     *
     * <p>根据技能元数据生成虚拟文件树，包含：
     * <ul>
     *   <li>SKILL.md - 技能说明文档</li>
     *   <li>skill.json - 技能元数据配置</li>
     *   <li>README.md - 使用说明</li>
     * </ul>
     *
     * @param skill 技能实体
     * @return 文件树列表（每个节点包含 path, name, type, content 字段）
     */
    private List<Map<String, Object>> buildSkillFileTree(Skill skill) {
        List<Map<String, Object>> files = new ArrayList<>();
        String skillMd = skillPackagerTool.generateSkillMd(skill.getId());
        String version = skill.getVersion() != null ? skill.getVersion() : "0.1.0";

        // SKILL.md
        Map<String, Object> skillMdFile = new HashMap<>();
        skillMdFile.put("path", "SKILL.md");
        skillMdFile.put("name", "SKILL.md");
        skillMdFile.put("type", "file");
        skillMdFile.put("content", skillMd);
        skillMdFile.put("language", "markdown");
        files.add(skillMdFile);

        // skill.json
        Map<String, Object> skillJson = new HashMap<>();
        skillJson.put("skill_code", skill.getSkillCode());
        skillJson.put("skill_name", skill.getSkillName());
        skillJson.put("version", version);
        skillJson.put("type", skill.getSkillType() != null ? skill.getSkillType().name() : "COMPOSITE");
        skillJson.put("scope", skill.getScope() != null ? skill.getScope().name() : "LOCAL");
        skillJson.put("description", skill.getDescription());
        skillJson.put("category", skill.getCategory() != null ? skill.getCategory().name() : null);
        skillJson.put("security_level", skill.getSecurityLevel() != null ? skill.getSecurityLevel().name() : null);
        skillJson.put("inputs", skill.getInputs() != null ? JSON.parse(skill.getInputs()) : new HashMap<>());
        skillJson.put("outputs", skill.getOutputs() != null ? JSON.parse(skill.getOutputs()) : new HashMap<>());
        skillJson.put("binding_tools", skill.getBindingTools() != null ? JSON.parse(skill.getBindingTools()) : new ArrayList<>());
        skillJson.put("instructions", skill.getInstructions());

        Map<String, Object> skillJsonFile = new HashMap<>();
        skillJsonFile.put("path", "skill.json");
        skillJsonFile.put("name", "skill.json");
        skillJsonFile.put("type", "file");
        skillJsonFile.put("content", JSON.toJSONString(skillJson));
        skillJsonFile.put("language", "json");
        files.add(skillJsonFile);

        // README.md
        String readme = buildReadme(skill, version);
        Map<String, Object> readmeFile = new HashMap<>();
        readmeFile.put("path", "README.md");
        readmeFile.put("name", "README.md");
        readmeFile.put("type", "file");
        readmeFile.put("content", readme);
        readmeFile.put("language", "markdown");
        files.add(readmeFile);

        return files;
    }

    /**
     * 构建技能 README.md 内容。
     */
    private String buildReadme(Skill skill, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(skill.getSkillName()).append("\n\n");
        sb.append("**技能编码**: `").append(skill.getSkillCode()).append("`  \n");
        sb.append("**版本**: ").append(version).append("  \n");
        sb.append("**类型**: ").append(skill.getSkillType() != null ? skill.getSkillType().name() : "COMPOSITE").append("  \n\n");
        if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
            sb.append("## 简介\n\n").append(skill.getDescription()).append("\n\n");
        }
        sb.append("## 使用方法\n\n");
        sb.append("在 Aegis 平台对话中通过 `@").append(skill.getSkillCode()).append("` 调用此技能。\n\n");
        sb.append("## 版权\n\n");
        sb.append("由 Aegis 技能生成器创建。\n");
        return sb.toString();
    }

    /**
     * S2: 从 Map 中提取字符串值，兼容多种输入类型。
     *
     * <p>处理逻辑：
     * <ul>
     *   <li>值为 null → 返回 defaultValue</li>
     *   <li>值为 String → 直接返回（已正确序列化）</li>
     *   <li>值为 Map/List → 用 JSON.toJSONString 序列化（而不是 Java toString 生成 {k=v}）</li>
     *   <li>其他类型 → String.valueOf 兜底</li>
     * </ul>
     *
     * <p>这解决了 LLM 在 MODIFY 时直觉传 object 而非 JSON string 的问题——
     * AgentScope ToolValidator 已因 S1 schema 宽松化而不拦截，但 Java 后端
     * 需要正确地把 object/array 转成可存入数据库的 JSON string。
     */
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof String s) return s;
        if (v instanceof Map || v instanceof List) {
            try { return JSON.toJSONString(v); }
            catch (Exception e) { log.warn("getString JSON 序列化失败, key={}", key); }
        }
        return String.valueOf(v);
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private SkillCategory parseCategory(String category) {
        if (category == null || category.isEmpty()) return SkillCategory.INTEGRATION;
        try { return SkillCategory.valueOf(category.toUpperCase()); }
        catch (Exception e) { return SkillCategory.INTEGRATION; }
    }

    private SkillResultBuilder buildResultMap(boolean success, String message, Long skillId, String skillCode) {
        return new SkillResultBuilder(success, message, skillId, skillCode);
    }

    private Map<String, Object> buildErrorResult(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    // ============ 内联 SkillLifecycleService 方法（Phase 1 迁移，runtime 侧直接操作） ============
    // U5: skillCode 生成器已收敛至 com.aegis.core.util.SkillCodeGenerator（三套实现合一）

    /**
     * 统一创建技能草稿（内联自 SkillLifecycleService.createDraft）。
     */
    private Skill createDraftSkill(Long tenantId, Long userId,
                                   String skillCode, String skillName,
                                   SkillType skillType, SkillCategory category,
                                   SecurityLevel securityLevel) {
        if (tenantId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        if (skillCode == null || skillCode.trim().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "技能编码不能为空");
        }
        if (skillName == null || skillName.trim().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "技能名称不能为空");
        }

        String code = skillCode.trim();
        String name = skillName.trim();

        // XSS 清洗
        name = XssSanitizer.sanitize(name, 200);

        // 编码唯一性（租户内）
        Long exists = skillMapper.selectCount(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getTenantId, tenantId)
                        .eq(Skill::getSkillCode, code));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "技能编码已存在: " + code);
        }

        // 构建实体（统一默认值）
        Skill skill = new Skill();
        skill.setTenantId(tenantId);
        skill.setSkillCode(code);
        skill.setSkillName(name);
        skill.setSkillType(skillType != null ? skillType : SkillType.ATOMIC);
        skill.setCategory(category != null ? category : SkillCategory.CONTENT);
        skill.setSecurityLevel(securityLevel != null ? securityLevel : SecurityLevel.L1);
        skill.setVisibility(Visibility.TENANT);
        skill.setLifeStatus(AgentLifeStatus.DRAFT);
        skill.setVersion("0.0.1");
        skill.setLatestVersion("0.0.1");
        skill.setActiveVersion("0.0.1");
        skill.setTags("[]");
        skill.setBindingTools("[]");
        skill.setInputs("{}");
        skill.setOutputs("{}");
        skill.setMappingConfig("{}");
        skill.setSubsCount(0);
        skill.setHealthScore(new java.math.BigDecimal("100.00"));
        skill.setAuthorUserId(userId);
        skill.setIsSystem(false);
        skill.setCertified(false);
        skill.setCreateBy(userId);
        skill.setCreateTime(LocalDateTime.now());
        skill.setDeleted(0);

        skillMapper.insert(skill);

        log.info("Skill draft created (unified via runtime): id={}, code={}, tenantId={}, authorUserId={}, type={}",
                skill.getId(), code, tenantId, userId, skill.getSkillType());
        return skill;
    }

    /**
     * 增量更新技能字段（内联自 SkillLifecycleService.patchFields）。
     */
    private boolean patchSkillFields(Skill skill,
                                     String skillName,
                                     String description,
                                     String instructions,
                                     SkillCategory category,
                                     SecurityLevel securityLevel,
                                     String bindingTools) {
        boolean updated = false;

        if (skillName != null && !skillName.trim().isEmpty()) {
            String sanitized = XssSanitizer.sanitize(skillName.trim(), 200);
            if (!sanitized.equals(skill.getSkillName())) {
                skill.setSkillName(sanitized);
                updated = true;
            }
        }
        if (description != null) {
            String sanitized = XssSanitizer.sanitize(description, 1000);
            if (!sanitized.equals(skill.getDescription())) {
                skill.setDescription(sanitized);
                updated = true;
            }
        }
        if (instructions != null) {
            if (!instructions.equals(skill.getInstructions())) {
                skill.setInstructions(instructions);
                updated = true;
            }
        }
        if (category != null && !category.equals(skill.getCategory())) {
            skill.setCategory(category);
            updated = true;
        }
        if (securityLevel != null && !securityLevel.equals(skill.getSecurityLevel())) {
            skill.setSecurityLevel(securityLevel);
            updated = true;
        }
        if (bindingTools != null) {
            if (!bindingTools.equals(skill.getBindingTools())) {
                skill.setBindingTools(bindingTools);
                updated = true;
            }
        }

        if (updated) {
            skillMapper.updateById(skill);
            log.debug("Skill fields patched: skillId={}, fields updated", skill.getId());
        }
        return updated;
    }

    /** 扫描结果序列化为 JSON（失败时返回 null，不阻断主流程） */
    private String toScanJson(SkillContentScanner.ScanResult scanResult) {
        try {
            return JSON.toJSONString(scanResult);
        } catch (Exception e) {
            log.warn("扫描结果序列化失败，审核单将不含 scanResult: {}", e.getMessage());
            return null;
        }
    }

    // ============ 内部类 ============

    /**
     * 链式结果构建器，支持 appendData 追加额外字段。
     */
    public static class SkillResultBuilder {
        private final Map<String, Object> result = new HashMap<>();

        public SkillResultBuilder(boolean success, String message, Long skillId, String skillCode) {
            result.put("success", success);
            result.put("message", message);
            if (skillId != null) result.put("skillId", String.valueOf(skillId));
            if (skillCode != null) result.put("skillCode", skillCode);
        }

        public SkillResultBuilder appendData(String key, Object value) {
            if (value != null) result.put(key, value);
            return this;
        }

        public Map<String, Object> build() {
            return result;
        }
    }
}