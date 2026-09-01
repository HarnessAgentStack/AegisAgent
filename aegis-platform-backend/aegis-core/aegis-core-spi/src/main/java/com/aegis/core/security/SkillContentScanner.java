package com.aegis.core.security;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.enums.common.SecurityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能内容安全扫描器（核心层）。
 *
 * <p>纯函数式扫描组件，不依赖任何 Mapper。在技能提交审核时执行静态安全扫描，
 * 覆盖八大检测维度（P1-2：引入 AS2 SkillSecurityScanner 等价检测类别）：</p>
 * <ul>
 *   <li>提示词注入检测（PROMPT_INJECTION）：识别"忽略以上指令"等越狱模式</li>
 *   <li>敏感内容检测（SENSITIVE_CONTENT）：识别身份证、手机号、密钥等敏感信息</li>
 *   <li>工具越权检测（TOOL_PRIVILEGE）：校验绑定工具的安全等级与技能声明等级是否匹配</li>
 *   <li>破坏性命令检测（DESTRUCTIVE）：{@code rm -rf /}、{@code mkfs}、{@code dd of=/dev}、{@code os.system("rm -rf /")}</li>
 *   <li>数据外泄检测（EXFILTRATION）：curl/wget POST 外发、反向 shell 数据回传</li>
 *   <li>持久化检测（PERSISTENCE）：crontab / systemd / shell-rc 篡改植入后门</li>
 *   <li>网络监听检测（NETWORK）：listen socket / 反向 shell 建立</li>
 *   <li>混淆载荷检测（OBFUSCATION）：base64→bash、{@code eval $(curl …)}、hex 拼接执行</li>
 * </ul>
 *
 * <h3>风险等级</h3>
 * <ul>
 *   <li>HIGH：直接阻断提交，life_status 保持 DRAFT</li>
 *   <li>MEDIUM：标记警告，可继续审核流程</li>
 *   <li>LOW：仅记录日志</li>
 * </ul>
 *
 * <p>P1-2 说明：AS2 {@code io.agentscope.harness.agent.skill.curator.SkillSecurityScanner} 为
 * harness 模块静态扫描器，admin 模块未依赖 agentscope-harness（仅 runtime 依赖）。本实现将其
 * 六大代码执行安全检测类别（EXFILTRATION/DESTRUCTIVE/PERSISTENCE/NETWORK/OBFUSCATION + INJECTION）
 * 以等价正则内联至核心层，覆盖 AS2 ~70% 能力（含文档验收用例：{@code os.system("rm -rf /")}→HIGH、
 * base64 混淆→OBFUSCATION 命中），无需新增 harness 依赖与 SKILL.md/resource 适配。
 * AS2 完整集成（含 AST 解析/Verdict 信任级映射）列入后续技能市场专项。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class SkillContentScanner {

    /** 提示词注入关键词（HIGH 级） */
    private static final List<String> INJECTION_KEYWORDS = Arrays.asList(
            "忽略以上指令", "忽略前面的指令", "ignore above", "ignore previous",
            "忽略所有指令", "forget all instructions", "你是一个新的", "you are now",
            "新的角色", "new role", "system prompt", "系统提示词",
            "jailbreak", "越狱", "prompt injection", "提示注入"
    );

    /** 敏感信息正则模式 */
    private static final List<Pattern> SENSITIVE_PATTERNS = Arrays.asList(
            // 身份证号
            Pattern.compile("\\b\\d{17}[\\dXx]\\b"),
            // 手机号
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),
            // 邮箱（可选）
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
            // 访问密钥/密码
            Pattern.compile("(?i)(api[_-]?key|access[_-]?key|secret|password|passwd)\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-]{16,}['\"]?"),
            // Token
            Pattern.compile("(?i)(token|bearer)\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-\\.]{20,}['\"]?")
    );

    /**
     * P1-2：破坏性命令检测（DESTRUCTIVE，HIGH）。
     * <p>等价 AS2 Category#DESTRUCTIVE：rm -rf /、mkfs、dd of=/dev、shred、os.system("rm -rf /") 等。
     */
    private static final List<Pattern> DESTRUCTIVE_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)rm\\s+-rf\\s+[/~*]"),
            Pattern.compile("(?i)\\brm\\s+-rf\\s+\\$\\(HOME\\)"),
            Pattern.compile("(?i)\\bmkfs\\.[a-z0-9]+\\s+/dev/"),
            Pattern.compile("(?i)\\bdd\\s+.*\\bof=/dev/"),
            Pattern.compile("(?i)\\bshred\\s+-u"),
            Pattern.compile("(?i)>\\s*/dev/[sh]d[a-z]"),
            Pattern.compile("(?i)os\\.system\\s*\\(\\s*[\"'].*rm\\s+-rf"),
            Pattern.compile("(?i)subprocess\\.(run|call|Popen)\\s*\\(\\s*[\"'].*rm\\s+-rf"),
            Pattern.compile("(?i):\\(\\)\\s*\\{\\s*:\\|\\s*:&\\s*\\}\\s*;:") // fork bomb
    );

    /**
     * P1-2：数据外泄检测（EXFILTRATION，HIGH）。
     * <p>等价 AS2 Category#EXFILTRATION：curl/wget POST 外发、反向 shell 数据回传。
     */
    private static final List<Pattern> EXFILTRATION_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)\\bcurl\\s+.*-(?:-data|-d|-F|X\\s*POST)"),
            Pattern.compile("(?i)\\bwget\\s+.*--post-(?:data|file)"),
            Pattern.compile("(?i)\\bcurl\\s+.*\\|\\s*(?:bash|sh|zsh)\\b"),
            Pattern.compile("(?i)\\bwget\\s+.*\\|\\s*(?:bash|sh|zsh)\\b"),
            Pattern.compile("(?i)\\bnc\\s+.*-e\\s+/bin/(?:bash|sh)"),
            Pattern.compile("(?i)\\bbash\\s+-i\\s*>&\\s*/dev/tcp/"),
            Pattern.compile("(?i)\\brequests\\.(post|put)\\s*\\(\\s*[\"']https?://[^\"']+[^)]*\\b(?:data|files|json)"),
            Pattern.compile("(?i)\\burllib\\.request\\.urlopen\\s*\\(\\s*[\"']https?://[^\"']+.*\\bdata=")
    );

    /**
     * P1-2：持久化检测（PERSISTENCE，MEDIUM）。
     * <p>等价 AS2 Category#PERSISTENCE：crontab/systemd/shell-rc 篡改。
     */
    private static final List<Pattern> PERSISTENCE_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)\\bcrontab\\s+-[er]"),
            Pattern.compile("(?i)\\(crontab\\s*;\\s*echo\\s+"),
            Pattern.compile("(?i)/etc/cron\\.(d|hourly|daily|weekly|monthly)/"),
            Pattern.compile("(?i)\\bsystemctl\\s+(?:enable|start|restart)\\s+"),
            Pattern.compile("(?i)\\becho\\s+.*>>?\\s*(?:~?/\\.bashrc|~?/\\.zshrc|~?/\\.profile|/etc/profile|/etc/rc\\.local)"),
            Pattern.compile("(?i)\\bsudo\\s+tee\\s+/(?:etc|usr)/lib/systemd/system/")
    );

    /**
     * P1-2：网络监听检测（NETWORK，MEDIUM）。
     * <p>等价 AS2 Category#NETWORK：listen socket / 反向 shell 建立。
     */
    private static final List<Pattern> NETWORK_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)\\bnc\\s+-l(?:p|p)?\\s+\\d+\\s+-e"),
            Pattern.compile("(?i)\\bsocat\\s+.*\\blisten.*\\bexec:"),
            Pattern.compile("(?i)\\bpython\\s+-c\\s*[\"'].*socket\\.socket.*\\.listen"),
            Pattern.compile("(?i)\\bbash\\s+-i\\s+>\\s*&\\s*(?:\\$?\\{?\\d+|/dev/tcp/)"),
            Pattern.compile("(?i)/dev/tcp/\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d+"),
            Pattern.compile("(?i)\\bssh\\s+-R\\s+\\d+:")
    );

    /**
     * P1-2：混淆载荷检测（OBFUSCATION，HIGH）。
     * <p>等价 AS2 Category#OBFUSCATION：base64→bash、eval $(curl…)、hex 拼接执行。
     */
    private static final List<Pattern> OBFUSCATION_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)\\bbase64\\s+-d\\s*\\|\\s*(?:bash|sh|zsh|python)"),
            Pattern.compile("(?i)\\beval\\s+\\$\\(\\s*(?:curl|wget)\\s+"),
            Pattern.compile("(?i)\\beval\\s+\\$\\(\\s*base64\\s+-d\\s+<<<"),
            Pattern.compile("(?i)\\bpython\\s+-c\\s*[\"'].*\\bbase64\\.b64decode.*\\bexec(?:\\(|\\b)"),
            Pattern.compile("(?i)\\bexec\\s*\\(\\s*bytes\\.fromhex"),
            Pattern.compile("(?i)\\bxxd\\s+-r\\s*-p\\s*\\|\\s*(?:bash|sh)"),
            Pattern.compile("(?i)\\bprintf\\s+['\"]\\\\x[0-9a-f]{2}['\"]\\s*\\|\\s*(?:bash|sh)")
    );

    /**
     * 执行安全扫描。
     *
     * @param skill 技能实体（非 null）
     * @return 扫描结果
     */
    public ScanResult scan(Skill skill) {
        List<ScanIssue> injectionIssues = scanPromptInjection(skill);
        List<ScanIssue> sensitiveIssues = scanSensitiveContent(skill);
        List<ScanIssue> privilegeIssues = scanToolPrivilege(skill);
        // P1-2：AS2 等价代码执行安全检测
        List<ScanIssue> destructiveIssues = scanByPatterns(skill, DESTRUCTIVE_PATTERNS, "DESTRUCTIVE", "HIGH");
        List<ScanIssue> exfiltrationIssues = scanByPatterns(skill, EXFILTRATION_PATTERNS, "EXFILTRATION", "HIGH");
        List<ScanIssue> persistenceIssues = scanByPatterns(skill, PERSISTENCE_PATTERNS, "PERSISTENCE", "MEDIUM");
        List<ScanIssue> networkIssues = scanByPatterns(skill, NETWORK_PATTERNS, "NETWORK", "MEDIUM");
        List<ScanIssue> obfuscationIssues = scanByPatterns(skill, OBFUSCATION_PATTERNS, "OBFUSCATION", "HIGH");

        List<ScanIssue> allIssues = new ArrayList<>();
        allIssues.addAll(injectionIssues);
        allIssues.addAll(sensitiveIssues);
        allIssues.addAll(privilegeIssues);
        allIssues.addAll(destructiveIssues);
        allIssues.addAll(exfiltrationIssues);
        allIssues.addAll(persistenceIssues);
        allIssues.addAll(networkIssues);
        allIssues.addAll(obfuscationIssues);

        boolean hasHigh = allIssues.stream().anyMatch(i -> "HIGH".equals(i.getRiskLevel()));
        boolean hasMedium = allIssues.stream().anyMatch(i -> "MEDIUM".equals(i.getRiskLevel()));

        String riskLevel = hasHigh ? "HIGH" : (hasMedium ? "MEDIUM" : "LOW");
        boolean passed = !hasHigh;

        String summary;
        if (allIssues.isEmpty()) {
            summary = "扫描通过，未发现安全风险";
        } else {
            long highCount = allIssues.stream().filter(i -> "HIGH".equals(i.getRiskLevel())).count();
            long mediumCount = allIssues.stream().filter(i -> "MEDIUM".equals(i.getRiskLevel())).count();
            summary = String.format("发现 %d 个高危问题、%d 个中危问题", highCount, mediumCount);
        }

        log.info("Skill content scan completed: skillId={}, passed={}, riskLevel={}, issues={}",
                skill.getId(), passed, riskLevel, allIssues.size());

        return ScanResult.builder()
                .passed(passed)
                .riskLevel(riskLevel)
                .summary(summary)
                .issues(allIssues)
                .build();
    }

    // ============ 扫描维度实现 ============

    /**
     * 提示词注入检测。
     */
    private List<ScanIssue> scanPromptInjection(Skill skill) {
        List<ScanIssue> issues = new ArrayList<>();
        String text = concatenateText(skill);

        if (text == null) return issues;

        for (String keyword : INJECTION_KEYWORDS) {
            if (text.toLowerCase().contains(keyword.toLowerCase())) {
                issues.add(ScanIssue.builder()
                        .dimension("PROMPT_INJECTION")
                        .riskLevel("HIGH")
                        .keyword(keyword)
                        .message("检测到提示词注入模式: " + keyword)
                        .build());
            }
        }
        return issues;
    }

    /**
     * 敏感内容检测。
     */
    private List<ScanIssue> scanSensitiveContent(Skill skill) {
        List<ScanIssue> issues = new ArrayList<>();
        String text = concatenateText(skill);
        if (text == null) return issues;

        for (Pattern pattern : SENSITIVE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String matched = matcher.group();
                String riskLevel = isHighRiskPattern(pattern) ? "HIGH" : "MEDIUM";
                issues.add(ScanIssue.builder()
                        .dimension("SENSITIVE_CONTENT")
                        .riskLevel(riskLevel)
                        .keyword(maskSensitive(matched))
                        .message("检测到敏感信息: " + pattern.pattern())
                        .build());
                if (issues.size() >= 10) break; // 限制报告数量
            }
        }
        return issues;
    }

    /**
     * 工具越权检测。
     */
    private List<ScanIssue> scanToolPrivilege(Skill skill) {
        List<ScanIssue> issues = new ArrayList<>();
        SecurityLevel declaredLevel = skill.getSecurityLevel();
        if (declaredLevel == null) return issues;

        // 解析绑定工具（简化检查：L3/L4 技能必须有合法的工具声明）
        String bindingTools = skill.getBindingTools();
        if (bindingTools != null && !bindingTools.isEmpty()
                && (declaredLevel == SecurityLevel.L3 || declaredLevel == SecurityLevel.L4)) {
            // 高安全等级技能应有对应的工具权限声明
            if (bindingTools.equals("[]") || bindingTools.equals("{}")) {
                issues.add(ScanIssue.builder()
                        .dimension("TOOL_PRIVILEGE")
                        .riskLevel("MEDIUM")
                        .message("高安全等级技能未声明绑定工具，请确认是否需要绑定工具")
                        .build());
            }
        }

        // L4 级别禁止绑定外网相关工具（简化检查）
        if (declaredLevel == SecurityLevel.L4 && bindingTools != null
                && bindingTools.toLowerCase().contains("internet")) {
            issues.add(ScanIssue.builder()
                    .dimension("TOOL_PRIVILEGE")
                    .riskLevel("HIGH")
                    .message("L4 绝密级技能禁止绑定外网工具")
                    .build());
        }

        return issues;
    }

    /**
     * P1-2：通用正则扫描（按维度+风险等级），供 DESTRUCTIVE/EXFILTRATION/PERSISTENCE/NETWORK/OBFUSCATION 复用。
     *
     * @param skill      技能实体
     * @param patterns   该维度的正则列表
     * @param dimension  检测维度名（与 AS2 Category 对齐）
     * @param riskLevel  命中风险等级
     * @return 问题清单（每模式至多报告 1 次，避免噪声）
     */
    private List<ScanIssue> scanByPatterns(Skill skill, List<Pattern> patterns,
                                          String dimension, String riskLevel) {
        List<ScanIssue> issues = new ArrayList<>();
        String text = concatenateText(skill);
        if (text == null) return issues;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                issues.add(ScanIssue.builder()
                        .dimension(dimension)
                        .riskLevel(riskLevel)
                        .keyword(maskSensitive(matcher.group()))
                        .message("检测到" + dimension + "风险模式: " + pattern.pattern())
                        .build());
            }
        }
        return issues;
    }

    // ============ 辅助方法 ============

    private String concatenateText(Skill skill) {
        StringBuilder sb = new StringBuilder();
        if (skill.getInstructions() != null) sb.append(skill.getInstructions()).append(" ");
        if (skill.getDescription() != null) sb.append(skill.getDescription()).append(" ");
        if (skill.getSkillName() != null) sb.append(skill.getSkillName()).append(" ");
        if (skill.getReferencesManifest() != null) sb.append(skill.getReferencesManifest()).append(" ");
        if (skill.getTriggerExamples() != null) sb.append(skill.getTriggerExamples()).append(" ");
        return sb.length() > 0 ? sb.toString() : null;
    }

    private boolean isHighRiskPattern(Pattern pattern) {
        String p = pattern.pattern();
        return p.contains("身份证") || p.contains("\\d{17}") || p.contains("api_key")
                || p.contains("access_key") || p.contains("secret") || p.contains("password");
    }

    private String maskSensitive(String input) {
        if (input == null || input.length() <= 4) return "****";
        return input.substring(0, 2) + "****" + input.substring(input.length() - 2);
    }

    // ============ DTO 定义 ============

    /** 扫描结果 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScanResult implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 是否通过（无 HIGH 级风险） */
        private boolean passed;
        /** 风险等级：HIGH / MEDIUM / LOW */
        private String riskLevel;
        /** 摘要 */
        private String summary;
        /** 问题清单 */
        private List<ScanIssue> issues;
    }

    /** 扫描问题项 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScanIssue implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 检测维度：PROMPT_INJECTION / SENSITIVE_CONTENT / TOOL_PRIVILEGE */
        private String dimension;
        /** 风险等级：HIGH / MEDIUM / LOW */
        private String riskLevel;
        /** 命中关键词（脱敏） */
        private String keyword;
        /** 问题描述 */
        private String message;
    }
}
