package com.aegis.runtime.integration.skill;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.domain.resource.SkillFile;
import com.aegis.core.domain.resource.SkillPackage;
import com.aegis.core.domain.resource.SkillVersion;
import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.ReviewStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.helper.SkillDomainHelper;
import com.aegis.core.security.SkillContentScanner;
import com.aegis.core.util.SkillCodeGenerator;
import com.aegis.dal.mapper.resource.ResourceReviewMapper;
import com.aegis.dal.mapper.resource.SkillFileMapper;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.SkillPackageMapper;
import com.aegis.dal.mapper.resource.SkillVersionMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCreatorOrchestrator {

    private final SkillMapper skillMapper;
    private final SkillPackagerTool skillPackagerTool;
    private final ResourceReviewMapper resourceReviewMapper;
    private final SkillContentScanner skillContentScanner;
    private final SkillFileMapper skillFileMapper;
    private final SkillVersionMapper skillVersionMapper;
    private final SkillPackageMapper skillPackageMapper;

    /**
     * 处理 skill_creator 工具调用。
     */
    public Map<String, Object> handleSkillCreator(Long tenantId, Long userId,
                                                   Map<String, Object> inputs,
                                                   List<AgentEvent> events) {
        String action = getString(inputs, "action", getString(inputs, "intent", "CREATE"));
        log.info("skill_creator 编排: tenantId={}, userId={}, action={}, inputs={}", tenantId, userId, action, inputs);

        return switch (action.toUpperCase()) {
            case "CREATE", "CREATE_DRAFT" -> handleCreate(tenantId, userId, inputs, events);
            case "MODIFY", "UPDATE", "EDIT" -> handleModify(tenantId, userId, inputs, events);
            case "DEBUG", "TEST" -> handleDebug(tenantId, userId, inputs, events);
            case "PACKAGE", "EXPORT" -> handlePackage(tenantId, userId, inputs, events);
            case "SUBMIT", "SUBMIT_REVIEW" -> rejectLlmSubmit(events);
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
        String categoryStr = getString(inputs, "category", null);
        String bindingTools = getString(inputs, "bindingTools", null);
        String inputs_schema = getString(inputs, "inputs", null);
        String outputs = getString(inputs, "outputs", null);

        // ★ 尊重 LLM 传入的 skillType，没有才默认 COMPOSITE
        SkillType skillType = parseSkillType(getString(inputs, "skillType", getString(inputs, "type", null)));
        if (skillType == null) skillType = SkillType.COMPOSITE;

        // ★ 尊重 LLM 传入的 securityLevel，没有才默认 L2
        SecurityLevel secLevel = parseSecurityLevel(getString(inputs, "securityLevel", null));
        if (secLevel == null) secLevel = SecurityLevel.L2;

        SkillCategory category = parseCategory(categoryStr);

        emitStage(events, "structuring", "正在结构化工件...", 30);

        String skillCode = SkillCodeGenerator.fromName(skillName);

        Skill skill;
        try {
            skill = SkillDomainHelper.buildDefaultSkill(tenantId, userId, skillCode, skillName, skillType, category, secLevel);
        } catch (com.aegis.core.common.error.BusinessException e) {
            emitStage(events, "completed", e.getMessage(), 100);
            return buildResultMap(false, e.getMessage(), null, skillCode).build();
        }

        // runtime 对话特有字段补充（只覆盖 LLM 传了的，null 不覆盖默认值）
        if (description != null && !description.isEmpty()) skill.setDescription(description);
        if (instructions != null && !instructions.isEmpty()) skill.setInstructions(instructions);
        if (bindingTools != null && !bindingTools.isEmpty()) skill.setBindingTools(bindingTools);
        if (inputs_schema != null && !inputs_schema.isEmpty()) skill.setInputs(inputs_schema);
        if (outputs != null && !outputs.isEmpty()) skill.setOutputs(outputs);

        skillMapper.insert(skill);

        // ★ 持久化文件快照（CREATE 后立即写 res_skill_file）
        persistSkillFiles(skill, tenantId, userId);

        log.info("技能草稿创建: id={}, code={}, securityLevel={}, skillType={}", skill.getId(), skillCode, secLevel, skillType);

        emitStage(events, "completed", "技能草稿创建成功", 100);
        emitDraftCreated(events, skill);

        return buildResultMap(true, "技能草稿创建成功。SKILL.md 与依赖文件已由平台自动生成并通过 skill.draft.created 事件下发前端，无需调用 generate_file 工具。接下来可引导用户调试或提交审核。", skill.getId(), skill.getSkillCode())
                .appendData("skillName", skill.getSkillName())
                .appendData("description", skill.getDescription())
                .appendData("instructions", skill.getInstructions())
                .appendData("skillType", skill.getSkillType() != null ? skill.getSkillType().name() : null)
                .appendData("category", category != null ? category.name() : null)
                .appendData("securityLevel", skill.getSecurityLevel() != null ? skill.getSecurityLevel().name() : null)
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
                Skill s = skillMapper.selectOne(new QueryWrapper<Skill>().eq("skill_code", skillCode).last("LIMIT 1"));
                if (s != null) skillId = s.getId();
            }
        }
        if (skillId == null) return buildErrorResult("未指定 skillId 或 skillCode");

        emitStage(events, "updating", "正在更新技能元数据...", 30);

        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) return buildErrorResult("技能不存在: " + skillId);

        String desc = getString(inputs, "description", null);
        String ins = getString(inputs, "instructions", null);
        String bindTools = getString(inputs, "bindingTools", null);
        String skillName = getString(inputs, "skillName", null);
        String category = getString(inputs, "category", null);
        String secLevel = getString(inputs, "securityLevel", null);

        boolean updated = SkillDomainHelper.patchFields(
                skill,
                skillName,
                desc,
                ins,
                parseCategory(category),
                parseSecurityLevel(secLevel),
                bindTools);

        if (updated) {
            skillMapper.updateById(skill);
            persistSkillFiles(skill, tenantId, userId);
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
        if (skillId == null) return buildErrorResult("未指定 skillId");
        emitStage(events, "debugging", "正在调试技能...", 20);

        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) return buildErrorResult("技能不存在: " + skillId);

        // 1. 静态检查
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (skill.getInstructions() == null || skill.getInstructions().isBlank()) {
            issues.add("instructions 为空 — 技能没有核心指令");
        }
        if (skill.getDescription() == null || skill.getDescription().isBlank()) {
            warnings.add("description 为空 — 建议补充技能描述");
        }
        if (skill.getBindingTools() == null || "[]".equals(skill.getBindingTools())) {
            warnings.add("未绑定任何工具 — 可能是纯 Prompt 类技能");
        }

        // 2. 安全扫描（与 SUBMIT 共用 SkillContentScanner）
        SkillContentScanner.ScanResult scan = skillContentScanner.scan(skill);

        // 3. 模拟最终 Prompt
        String simulatedPrompt = buildSimulatedPrompt(skill);

        emitStage(events, "completed", "调试完成", 100);

        Map<String, Object> debugData = new HashMap<>();
        debugData.put("skillId", String.valueOf(skillId));
        debugData.put("success", issues.isEmpty());
        debugData.put("issues", issues);
        debugData.put("warnings", warnings);
        debugData.put("scanPassed", scan.isPassed());
        debugData.put("scanSummary", scan.getSummary());
        debugData.put("simulatedPrompt", simulatedPrompt);
        debugData.put("message", issues.isEmpty() ? "调试通过" : "发现 " + issues.size() + " 个问题");

        events.add(AgentEvent.of("skill.debug.result", debugData));

        return buildResultMap(issues.isEmpty(), debugData.get("message").toString(), skillId, null)
                .appendData("debugSuccess", issues.isEmpty())
                .appendData("issues", issues)
                .appendData("warnings", warnings)
                .appendData("scanPassed", scan.isPassed())
                .appendData("simulatedPrompt", simulatedPrompt)
                .build();
    }

    private String buildSimulatedPrompt(Skill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Skill Prompt Preview ===\n\n");
        sb.append("# ").append(skill.getSkillName()).append("\n\n");
        if (skill.getDescription() != null) sb.append(skill.getDescription()).append("\n\n");
        sb.append("## Instructions\n\n");
        sb.append(skill.getInstructions() != null ? skill.getInstructions() : "(空)").append("\n\n");
        return sb.toString();
    }

    private void createVersionSnapshot(Skill skill, String snapshotVersion,
                                        Long tenantId, Long userId) {
        SkillVersion sv = new SkillVersion();
        sv.setTenantId(tenantId);
        sv.setSkillId(skill.getId());
        sv.setSkillCode(skill.getSkillCode());
        sv.setVersion(snapshotVersion);
        sv.setSkillName(skill.getSkillName());
        sv.setDescription(skill.getDescription());
        sv.setInstructions(skill.getInstructions());
        sv.setBindingTools(skill.getBindingTools());
        sv.setInputs(skill.getInputs());
        sv.setOutputs(skill.getOutputs());
        sv.setMappingConfig(skill.getMappingConfig());
        sv.setSecurityLevel(skill.getSecurityLevel() != null ? skill.getSecurityLevel().name() : null);
        sv.setCategory(skill.getCategory() != null ? skill.getCategory().name() : null);
        sv.setIsSystem(skill.getIsSystem() != null && skill.getIsSystem() ? 1 : 0);
        sv.setCreateBy(userId);
        skillVersionMapper.insert(sv);
        log.info("版本快照创建: skillId={}, version={}", skill.getId(), snapshotVersion);
    }

    private Map<String, Object> handlePackage(Long tenantId, Long userId,
                                                Map<String, Object> inputs,
                                                List<AgentEvent> events) {
        Long skillId = getLong(inputs, "skillId");
        if (skillId == null) return buildErrorResult("未指定 skillId");

        emitStage(events, "packaging", "正在打包技能...", 30);

        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) return buildErrorResult("技能不存在: " + skillId);

        // 1. 先持久化当前文件快照（确保 zip 包含最新内容）
        persistSkillFiles(skill, tenantId, userId);

        // 2. 真正打包并上传 MinIO
        SkillPackagerTool.PackageResult pkgResult = skillPackagerTool.packageAndUpload(skillId, tenantId);
        if (!pkgResult.isSuccess()) {
            emitStage(events, "failed", "打包失败: " + pkgResult.getMessage(), 100);
            return buildErrorResult(pkgResult.getMessage());
        }

        // 3. 记录打包元数据到 res_skill_package
        SkillPackage pkg = SkillPackage.builder()
                .tenantId(tenantId)
                .skillId(skillId)
                .skillCode(skill.getSkillCode())
                .skillVersion(skill.getVersion() != null ? skill.getVersion() : "0.0.1")
                .packageName(pkgResult.getFileName())
                .storedKey(pkgResult.getStoredKey())
                .storageSize(pkgResult.getData() != null ? pkgResult.getData().length : 0)
                .triggerSource("USER_ACTION")
                .createBy(userId)
                .build();
        skillPackageMapper.insert(pkg);

        emitStage(events, "completed", "打包完成", 100);
        emitPackageResult(events, skillId, true, pkgResult.getFileName());

        log.info("技能打包完成: skillId={}, fileName={}, storedKey={}, size={}",
                skillId, pkgResult.getFileName(), pkgResult.getStoredKey(), pkg.getStorageSize());

        return buildResultMap(true, "打包完成", skillId, skill.getSkillCode())
                .appendData("fileName", pkgResult.getFileName())
                .appendData("packageUrl", pkgResult.getPackageUrl())
                .appendData("packageSuccess", true)
                .build();
    }

    /**
     * 拒绝 LLM 自动调 SUBMIT。
     *
     * <p>提交审核是用户显式操作，LLM 不应自动链式调用。
     * 用户提交走 REST 端点 {@code POST /runtime/skill/{id}/submit-review}（SkillChatController）。
     * LLM 误调时返回明确引导，不执行任何 DB 操作。</p>
     */
    private Map<String, Object> rejectLlmSubmit(List<AgentEvent> events) {
        log.warn("LLM 尝试自动 SUBMIT，已拒绝。提交审核须由用户显式触发（REST 端点）");
        emitStage(events, "rejected", "提交审核需用户显式操作，技能保持草稿态", 100);
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "提交审核须由用户显式触发（点击右侧面板\"提交审核\"按钮），不支持 AI 自动提交。技能保持草稿态，可继续调试或保存。");
        result.put("llmSubmitRejected", true);
        return result;
    }

    private Map<String, Object> handleSubmit(Long tenantId, Long userId,
                                              Map<String, Object> inputs,
                                              List<AgentEvent> events) {
        Long skillId = getLong(inputs, "skillId");
        if (skillId == null) return buildErrorResult("未指定 skillId");
        emitStage(events, "submitting", "正在提交审核...", 50);
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) return buildErrorResult("技能不存在: " + skillId);

        if (skill.getAuthorUserId() != null && !skill.getAuthorUserId().equals(userId)) {
            return buildErrorResult("无权提交他人创建的技能");
        }

        if (skill.getLifeStatus() != AgentLifeStatus.DRAFT
                && skill.getLifeStatus() != AgentLifeStatus.REJECTED) {
            if (skill.getLifeStatus() == AgentLifeStatus.REVIEWING) {
                emitStage(events, "completed", "已在审核中", 100);
                return buildResultMap(true, "技能已在审核中", skillId, skill.getSkillCode())
                        .appendData("submitted", true).build();
            }
            return buildErrorResult("当前状态不可提交审核: " + skill.getLifeStatus());
        }

        if (skill.getCreateTime() != null) {
            long ageSec = java.time.Duration.between(skill.getCreateTime(), java.time.LocalDateTime.now()).getSeconds();
            if (ageSec < 60) {
                emitStage(events, "failed", "技能刚创建，请确认内容后再提交审核", 100);
                return buildErrorResult("技能刚创建（" + ageSec + "秒前），请先确认内容无误后，由用户显式发起提交审核。");
            }
        }

        SkillContentScanner.ScanResult scanResult = skillContentScanner.scan(skill);
        if (!scanResult.isPassed()) {
            emitStage(events, "failed", "安全扫描未通过，提交已阻断", 100);
            return buildErrorResult("安全扫描未通过（P0 风险阻断）: " + scanResult.getSummary());
        }

        ResourceReview existingReview = resourceReviewMapper.selectOne(
                new QueryWrapper<ResourceReview>()
                        .eq("resource_type", ResourceType.SKILL.name())
                        .eq("resource_id", skillId)
                        .eq("review_status", ReviewStatus.PENDING.name())
                        .orderByDesc("id").last("LIMIT 1"));

        if (existingReview != null) {
            if (skill.getLifeStatus() != AgentLifeStatus.REVIEWING) {
                skill.setLifeStatus(AgentLifeStatus.REVIEWING);
                skillMapper.updateById(skill);
            }
            emitStage(events, "completed", "已提交审核", 100);
            return buildResultMap(true, "提交审核成功（已有待审核单）", skillId, skill.getSkillCode())
                    .appendData("submitted", true).appendData("reviewId", existingReview.getId()).build();
        }

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

        // ★ SUBMIT 前自动升 MINOR 版本 + 写快照
        String nextVersion = SkillDomainHelper.bumpVersion(skill.getVersion(), "MINOR");
        createVersionSnapshot(skill, nextVersion, tenantId, userId);
        skill.setVersion(nextVersion);
        skill.setLatestVersion(nextVersion);
        skill.setActiveVersion(nextVersion);
        skill.setLifeStatus(AgentLifeStatus.REVIEWING);
        skillMapper.updateById(skill);

        // 提交前再持久化一次文件快照（用新版本号）
        persistSkillFiles(skill, tenantId, userId);

        log.info("技能提交审核: skillId={}, skillCode={}, version={}, reviewId={}", skillId, skill.getSkillCode(), nextVersion, review.getId());

        emitStage(events, "completed", "已提交审核", 100);
        return buildResultMap(true, "提交审核成功", skillId, skill.getSkillCode())
                .appendData("submitted", true).appendData("reviewId", review.getId()).build();
    }

    private Map<String, Object> handleQuery(Map<String, Object> inputs, List<AgentEvent> events) {
        Long skillId = getLong(inputs, "skillId");
        if (skillId == null) {
            String skillCode = getString(inputs, "skillCode", "");
            if (!skillCode.isEmpty()) {
                Skill s = skillMapper.selectOne(new QueryWrapper<Skill>().eq("skill_code", skillCode).last("LIMIT 1"));
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

    // ============ 文件持久化（CREATE / MODIFY / SUBMIT 时写入 res_skill_file） ============

    /**
     * 将当前技能的虚拟文件树（SKILL.md / skill.json / README.md）落库到 res_skill_file。
     *
     * <p>同版本同路径的文件：已存在则 update（UPSERT 语义），不存在则 insert。</p>
     */
    private void persistSkillFiles(Skill skill, Long tenantId, Long userId) {
        String version = skill.getVersion() != null ? skill.getVersion() : "0.0.1";
        List<Map<String, Object>> tree = buildSkillFileTree(skill);
        List<SkillFile> toInsert = new ArrayList<>();
        List<SkillFile> toUpdate = new ArrayList<>();

        for (Map<String, Object> node : tree) {
            String filePath = (String) node.get("path");
            String content = (String) node.get("content");

            SkillFile existing = skillFileMapper.selectOne(
                    new LambdaQueryWrapper<SkillFile>()
                            .eq(SkillFile::getSkillId, skill.getId())
                            .eq(SkillFile::getVersion, version)
                            .eq(SkillFile::getFilePath, filePath));

            SkillFile file = existing != null ? existing : new SkillFile();
            file.setTenantId(tenantId);
            file.setSkillId(skill.getId());
            file.setSkillCode(skill.getSkillCode());
            file.setVersion(version);
            file.setFilePath(filePath);
            file.setFileName((String) node.get("name"));
            file.setFileType(detectFileType(filePath));
            file.setContent(content);
            file.setContentHash(sha256Hex(content));
            file.setSize(content != null ? content.getBytes(StandardCharsets.UTF_8).length : 0);
            file.setIsEntry("SKILL.md".equals(filePath) ? 1 : 0);
            file.setCreateBy(userId);

            if (existing != null) toUpdate.add(file);
            else toInsert.add(file);
        }

        for (SkillFile f : toInsert) skillFileMapper.insert(f);
        for (SkillFile f : toUpdate) skillFileMapper.updateById(f);

        log.info("技能文件持久化: skillId={}, version={}, insert={}, update={}",
                skill.getId(), version, toInsert.size(), toUpdate.size());
    }

    private String detectFileType(String filePath) {
        if (filePath == null) return "OTHER";
        if (filePath.endsWith(".md")) return "MARKDOWN";
        if (filePath.endsWith(".json")) return "JSON";
        if (filePath.endsWith(".py")) return "PYTHON";
        if (filePath.endsWith(".js") || filePath.endsWith(".ts")) return "SCRIPT";
        return "OTHER";
    }

    private String sha256Hex(String s) {
        if (s == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ============ 枚举解析（尊重 LLM 输入） ============

    private SkillType parseSkillType(String s) {
        if (s == null) return null;
        try { return SkillType.valueOf(s.toUpperCase()); }
        catch (Exception e) { return null; }
    }

    private SecurityLevel parseSecurityLevel(String s) {
        if (s == null) return null;
        try { return SecurityLevel.valueOf(s.toUpperCase()); }
        catch (Exception e) { return null; }
    }

    private SkillCategory parseCategory(String s) {
        if (s == null || s.isEmpty()) return SkillCategory.INTEGRATION;
        try { return SkillCategory.valueOf(s.toUpperCase()); }
        catch (Exception e) { return SkillCategory.INTEGRATION; }
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
        events.add(AgentEvent.of("skill.draft.created", buildDraftEventData(skill)));
    }

    private void emitDraftUpdated(List<AgentEvent> events, Skill skill) {
        events.add(AgentEvent.of("skill.draft.updated", buildDraftEventData(skill)));
    }

    private Map<String, Object> buildDraftEventData(Skill skill) {
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
        data.put("files", buildSkillFileTree(skill));
        return data;
    }

    private void emitPackageResult(List<AgentEvent> events, Long skillId, boolean success, String fileName) {
        Map<String, Object> data = new HashMap<>();
        data.put("skillId", skillId != null ? String.valueOf(skillId) : null);
        data.put("success", success);
        data.put("fileName", fileName);
        events.add(AgentEvent.of("skill.package.result", data));
    }

    // ============ 虚拟文件树构建（保留原逻辑） ============

    private List<Map<String, Object>> buildSkillFileTree(Skill skill) {
        List<Map<String, Object>> files = new ArrayList<>();
        String skillMd = skillPackagerTool.generateSkillMd(skill.getId());
        String version = skill.getVersion() != null ? skill.getVersion() : "0.0.1";

        Map<String, Object> skillMdFile = new HashMap<>();
        skillMdFile.put("path", "SKILL.md");
        skillMdFile.put("name", "SKILL.md");
        skillMdFile.put("type", "file");
        skillMdFile.put("content", skillMd);
        skillMdFile.put("language", "markdown");
        files.add(skillMdFile);

        Map<String, Object> skillJson = new HashMap<>();
        skillJson.put("skill_code", skill.getSkillCode());
        skillJson.put("skill_name", skill.getSkillName());
        skillJson.put("version", version);
        skillJson.put("type", skill.getSkillType() != null ? skill.getSkillType().name() : "COMPOSITE");
        skillJson.put("scope", skill.getScope() != null ? skill.getScope().name() : "LOCAL");
        skillJson.put("description", skill.getDescription());

        Map<String, Object> skillJsonFile = new HashMap<>();
        skillJsonFile.put("path", "skill.json");
        skillJsonFile.put("name", "skill.json");
        skillJsonFile.put("type", "file");
        skillJsonFile.put("content", JSON.toJSONString(skillJson));
        skillJsonFile.put("language", "json");
        files.add(skillJsonFile);

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
        sb.append("## 版权\n\n由 Aegis 技能生成器创建。\n");
        return sb.toString();
    }

    // ============ 工具方法 ============

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

    private SkillResultBuilder buildResultMap(boolean success, String message, Long skillId, String skillCode) {
        return new SkillResultBuilder(success, message, skillId, skillCode);
    }

    private Map<String, Object> buildErrorResult(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    private String toScanJson(SkillContentScanner.ScanResult scanResult) {
        try { return JSON.toJSONString(scanResult); }
        catch (Exception e) { log.warn("扫描结果序列化失败", e); return null; }
    }

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
        public Map<String, Object> build() { return result; }
    }
}
