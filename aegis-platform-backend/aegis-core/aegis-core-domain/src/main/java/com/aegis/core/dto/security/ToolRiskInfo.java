package com.aegis.core.dto.security;

import com.aegis.core.enums.resource.ToolType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 工具风险信息 DTO。
 *
 * <p>描述单个工具的风险元数据，包括风险等级、是否需要审批、
 * 是否在沙箱中执行以及参数级风险规则。用于 {@link com.aegis.runtime.service.ToolRiskService}
 * 的风险决策与 HITL 审批流程。</p>
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolRiskInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String toolName;

    private ToolType toolType;

    private RiskLevel riskLevel;

    private String riskReason;

    private String category;

    private boolean needApproval;

    private boolean sandboxExecution;

    private List<ParamRiskRule> paramRiskRules;

    /**
     * 风险等级枚举， ordinal 越高风险越大。
     */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /**
     * 参数级风险规则，用于动态参数风险评估。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParamRiskRule implements Serializable {

        private static final long serialVersionUID = 1L;

        private String paramName;

        private String valuePattern;

        private RiskLevel upgradeTo;

        private String reason;
    }

    /**
     * 创建默认高风险（CRITICAL + needApproval=true）的风险信息。
     *
     * @param toolName 工具名称
     * @return 高风险 ToolRiskInfo
     */
    public static ToolRiskInfo defaultHighRisk(String toolName) {
        return ToolRiskInfo.builder()
                .toolName(toolName)
                .riskLevel(RiskLevel.CRITICAL)
                .needApproval(true)
                .sandboxExecution(false)
                .riskReason("未知工具，默认高风险兜底")
                .category("unknown")
                .build();
    }
}