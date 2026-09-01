package com.aegis.core.util;

/**
 * 技能编码（skillCode）统一生成器。
 *
 * <p>U5 统一：此前存在三套独立的 skillCode 生成逻辑——
 * admin 的 {@code SkillLifecycleService.generateSkillCodeFromName}、
 * runtime 的 {@code SkillCreatorOrchestrator.generateSkillCodeFromName}（内联复制）、
 * runtime 的 {@code SkillCreatorTool.generateSkillCode}（保留中文 + UUID 后缀，格式互不兼容）。
 * 中文 skillCode 会被直接用作 AgentScope Toolkit 注册名（见 SkillAsToolAdapter），
 * 部分 LLM 供应商对工具名做 {@code [a-zA-Z0-9_-]} 校验，中文将导致 tool_call 失败。
 *
 * <p>统一规则（沿用 SkillLifecycleService 成熟规则）：
 * <ol>
 *   <li>小写化，非 {@code [a-z0-9_]} 字符（含中文）统一转 {@code _}</li>
 *   <li>压缩连续 {@code _}，去除首尾 {@code _}</li>
 *   <li>长度截断 32 字符</li>
 *   <li>规范化为空（纯中文名等场景）时兜底 {@code skill_ + 时间戳}</li>
 * </ol>
 *
 * <p>租户内唯一性由 {@code res_skill.uk_skill_code(tenant_id, skill_code)} 唯一键 +
 * {@code SkillLifecycleService.createDraft} 的 CONFLICT 校验兜底，生成器不负责查库去重。
 *
 * @author wang.zhen
 */
public final class SkillCodeGenerator {

    /** skillCode 最大长度（与 SkillLifecycleService 历史规则一致） */
    private static final int MAX_LENGTH = 32;

    private SkillCodeGenerator() {
    }

    /**
     * 从技能名称生成 skillCode。
     *
     * @param name 技能名称（可为 null/空/纯中文）
     * @return 规范化后的 skillCode，永不返回 null/空
     */
    public static String fromName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "skill_" + System.currentTimeMillis();
        }
        String code = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (code.isEmpty()) {
            return "skill_" + System.currentTimeMillis();
        }
        if (code.length() > MAX_LENGTH) {
            code = code.substring(0, MAX_LENGTH);
        }
        return code;
    }
}
