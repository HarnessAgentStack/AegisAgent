package com.aegis.dal.security;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.security.SkillContentScanner;
import com.aegis.dal.mapper.resource.SkillMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能安全扫描器（数据访问层门面）。
 *
 * <p>核心扫描逻辑委托 {@link SkillContentScanner}，
 * 本类仅负责按 skillId 加载实体并触发扫描。
 *
 * <h3>风险等级</h3>
 * <ul>
 *   <li>P0（HIGH）：直接阻断发布，life_status 保持 DRAFT</li>
 *   <li>P1（MEDIUM）：标记警告，可继续审核流程</li>
 *   <li>P2（LOW）：仅记录日志</li>
 * </ul>
 *
 * @author wang.zhen
 * @see SkillContentScanner
 */
@Slf4j
@Component
public class SkillSecurityScanner {

    private final SkillMapper skillMapper;
    private final SkillContentScanner contentScanner;

    @Autowired
    public SkillSecurityScanner(SkillMapper skillMapper, SkillContentScanner contentScanner) {
        this.skillMapper = skillMapper;
        this.contentScanner = contentScanner;
    }

    /**
     * 执行安全扫描。
     *
     * @param skillId 技能ID
     * @return 扫描结果
     */
    public ScanResult scan(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return ScanResult.builder()
                    .passed(false)
                    .riskLevel("HIGH")
                    .summary("技能不存在")
                    .build();
        }

        SkillContentScanner.ScanResult core = contentScanner.scan(skill);
        return fromCore(core);
    }

    /**
     * 检查是否为 P0 风险（需要阻断发布）。
     */
    public boolean isBlockedByP0(Long skillId) {
        ScanResult result = scan(skillId);
        return !result.isPassed();
    }

    /** core 扫描结果 -> dal 扫描结果 */
    private ScanResult fromCore(SkillContentScanner.ScanResult core) {
        List<ScanIssue> issues = new ArrayList<>();
        if (core.getIssues() != null) {
            core.getIssues().forEach(i -> issues.add(ScanIssue.builder()
                    .dimension(i.getDimension())
                    .riskLevel(i.getRiskLevel())
                    .keyword(i.getKeyword())
                    .message(i.getMessage())
                    .build()));
        }
        return ScanResult.builder()
                .passed(core.isPassed())
                .riskLevel(core.getRiskLevel())
                .summary(core.getSummary())
                .issues(issues)
                .build();
    }

    // ============ DTO 定义 ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScanResult {
        private boolean passed;
        private String riskLevel;
        private String summary;
        private List<ScanIssue> issues;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScanIssue {
        private String dimension;
        private String riskLevel;
        private String keyword;
        private String message;
    }
}
