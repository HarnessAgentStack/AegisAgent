package com.aegis.runtime.integration.skill;

import com.aegis.core.domain.resource.Skill;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.runtime.integration.tool.SkillExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能调试工具：试运行执行、问题诊断、迭代优化。
 *
 * <p>P0-ITEM-2 改造：调试功能从 MOCK 改为真实执行，
 * 通过 {@link SkillExecutor} 实际运行技能逻辑，返回真实的执行步骤和输出结果。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillDebuggerTool {

    private final SkillMapper skillMapper;
    private final SkillExecutor skillExecutor;

    /**
     * P0-ITEM-2：真实试运行执行技能。
     *
     * <p>通过 {@link SkillExecutor} 实际执行技能逻辑，返回真实的执行步骤和输出结果。
     * 对于 ATOMIC 技能，执行绑定的工具；对于 COMPOSITE 技能，按 DAG 编排执行。
     *
     * @param skillId    技能ID
     * @param testInputs 测试输入参数
     * @return 调试结果，含执行步骤、输出和问题发现
     */
    public DebugResult runTest(Long skillId, Map<String, Object> testInputs) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return DebugResult.fail("技能不存在: " + skillId);
        }

        log.info("技能调试开始: skillId={}, skillCode={}, inputs={}",
                skillId, skill.getSkillCode(), testInputs);

        DebugResult result = new DebugResult();
        Map<String, Object> steps = new HashMap<>();
        int stepIndex = 0;

        try {
            // 步骤1：参数校验与准备
            stepIndex++;
            steps.put("step" + stepIndex + "_validate", "校验技能配置与输入参数");

            if (testInputs == null || testInputs.isEmpty()) {
                result.addFinding("WARNING", "未提供测试输入参数，将使用空参数执行");
            }

            // 步骤2：加载技能配置
            stepIndex++;
            steps.put("step" + stepIndex + "_load", "加载技能定义与绑定工具");

            // 步骤3：真实执行技能
            stepIndex++;
            steps.put("step" + stepIndex + "_execute", "执行技能核心逻辑");

            long startTime = System.currentTimeMillis();
            Map<String, Object> execResult = skillExecutor.execute(
                    skill.getTenantId(), skillId, testInputs != null ? testInputs : Map.of());
            long duration = System.currentTimeMillis() - startTime;

            // 步骤4：验证输出
            stepIndex++;
            steps.put("step" + stepIndex + "_validate_output", "验证输出结果");

            boolean success = execResult != null && Boolean.TRUE.equals(execResult.get("success"));
            result.setSuccess(success);

            Object output = execResult != null ? execResult.get("output") : null;
            result.setOutput(output != null ? output.toString() : "无输出");

            if (success) {
                result.setMessage("调试执行成功（耗时 " + duration + "ms）");
                result.addFinding("INFO", "技能执行成功，耗时 " + duration + "ms");

                // 如果有节点执行结果，记录详情
                Object nodeResults = execResult.get("nodeResults");
                if (nodeResults instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> nodes = (List<Map<String, Object>>) nodeResults;
                    for (int i = 0; i < nodes.size(); i++) {
                        Map<String, Object> node = nodes.get(i);
                        String nodeId = node.get("nodeId") != null ? node.get("nodeId").toString()
                                : (node.get("toolCode") != null ? node.get("toolCode").toString() : "node_" + i);
                        boolean nodeSuccess = Boolean.TRUE.equals(node.get("success"));
                        result.addFinding(nodeSuccess ? "INFO" : "WARNING",
                                "节点 [" + nodeId + "] " + (nodeSuccess ? "执行成功" : "执行失败"));
                    }
                }
            } else {
                String errorMsg = output != null ? output.toString() : "未知错误";
                result.setMessage("调试执行失败: " + errorMsg);
                result.addFinding("ERROR", "技能执行失败: " + errorMsg);
            }

        } catch (Exception e) {
            log.error("技能调试异常: skillId={}", skillId, e);
            result.setSuccess(false);
            result.setMessage("调试执行异常: " + e.getMessage());
            result.setOutput("异常: " + e.getMessage());
            result.addFinding("ERROR", "执行异常: " + e.getMessage());
        }

        result.setSteps(steps);
        return result;
    }

    /**
     * 诊断技能问题。
     */
    public DebugResult diagnose(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return DebugResult.fail("技能不存在: " + skillId);
        }

        DebugResult result = DebugResult.success();
        int issues = 0;
        
        if (skill.getInstructions() == null || skill.getInstructions().isBlank()) {
            result.addFinding("WARNING", "技能缺少方法论正文（instructions 为空）");
            issues++;
        }
        if (skill.getInputs() == null || skill.getInputs().isBlank()) {
            result.addFinding("WARNING", "技能缺少输入参数定义");
            issues++;
        }
        if (skill.getOutputs() == null || skill.getOutputs().isBlank()) {
            result.addFinding("WARNING", "技能缺少输出参数定义");
            issues++;
        }
        if (skill.getBindingTools() == null || skill.getBindingTools().isBlank()) {
            result.addFinding("INFO", "技能未绑定任何工具（纯方法论技能）");
        }
        
        if (issues > 0) {
            result.setSuccess(false);
            result.setMessage("发现 " + issues + " 个需要关注的问题");
        } else {
            result.setMessage("技能结构完整，无明显问题");
        }
        
        return result;
    }

    /**
     * 生成迭代优化建议。
     */
    public String suggest(Long skillId, DebugResult debugResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 优化建议\n\n");
        
        if (debugResult == null || !debugResult.isSuccess()) {
            sb.append("### 需要修复的问题\n");
            if (debugResult != null && debugResult.getFindings() != null) {
                for (DebugResult.Finding f : debugResult.getFindings()) {
                    if ("WARNING".equals(f.getLevel()) || "ERROR".equals(f.getLevel())) {
                        sb.append("- **").append(f.getLevel()).append("**: ").append(f.getMessage()).append("\n");
                    }
                }
            }
            sb.append("\n### 建议操作\n");
            sb.append("1. 补充技能的方法论正文（instructions）\n");
            sb.append("2. 完善输入/输出参数的 JSON Schema 定义\n");
            sb.append("3. 绑定必要的工具以增强技能能力\n");
        } else {
            sb.append("技能状态良好。\n\n");
            sb.append("### 提升建议\n");
            sb.append("1. 添加触发示例，帮助模型更好地识别调用时机\n");
            sb.append("2. 考虑添加更多测试用例覆盖边界场景\n");
            sb.append("3. 完善技能描述，方便市场检索\n");
        }
        
        return sb.toString();
    }

    @lombok.Data
    public static class DebugResult {
        private boolean success;
        private String message;
        private Map<String, Object> steps;
        private String output;
        private List<Finding> findings = new ArrayList<>();

        public static DebugResult success() {
            DebugResult r = new DebugResult();
            r.setSuccess(true);
            r.setMessage("调试成功");
            return r;
        }

        public static DebugResult fail(String msg) {
            DebugResult r = new DebugResult();
            r.setSuccess(false);
            r.setMessage(msg);
            return r;
        }

        public void addFinding(String level, String message) {
            findings.add(new Finding(level, message));
        }

        @lombok.Data
        public static class Finding {
            private String level;
            private String message;
            public Finding() {}
            public Finding(String level, String message) {
                this.level = level;
                this.message = message;
            }
        }
    }
}
