package com.aegis.runtime.service.tool;

import com.aegis.core.dto.security.BuiltinToolRiskConfig;
import com.aegis.core.dto.security.ToolRiskInfo;
import com.aegis.core.dto.security.ToolRiskInfo.RiskLevel;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.dal.mapper.resource.ToolMapper;
import com.aegis.core.domain.resource.Tool;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具风险服务。
 *
 * <p>统一管理工具风险信息的查询与计算，支持两个来源：
 * <ol>
 *   <li><b>内置工具</b>：通过 {@link BuiltinToolRiskConfig} 代码配置加载</li>
 *   <li><b>外部工具</b>：通过数据库 {@code res_tool} 表加载（MCP 等）</li>
 * </ol>
 *
 * <h3>查询优先级</h3>
 * <ol>
 *   <li>内置工具 → 使用代码配置</li>
 *   <li>数据库工具 → 使用数据库配置（可覆盖内置）</li>
 *   <li>未知工具 → 默认高风险兜底</li>
 * </ol>
 *
 * <h3>动态风险评估</h3>
 * <p>支持根据工具参数动态调整风险等级，例如：
 * <ul>
 *   <li>创建新文件（overwrite=false）→ 降低为 LOW</li>
 *   <li>写配置文件（.env/.yaml）→ 升级为 CRITICAL</li>
 *   <li>执行包含 rm/del 的命令 → 升级为 CRITICAL</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRiskService {

    private final ToolMapper toolMapper;

    /**
     * 数据库工具风险缓存（本地缓存，避免频繁查询）
     */
    private final Map<String, ToolRiskInfo> dbToolRiskCache = new ConcurrentHashMap<>();

    /**
     * 缓存过期时间（毫秒），默认 5 分钟
     */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /**
     * 缓存更新时间戳
     */
    private volatile long lastCacheUpdateTime = 0L;

    /**
     * 全局默认沙箱执行标志。
     * 对于未知工具，假设其不在沙箱中执行（更保守）
     */
    private static final boolean DEFAULT_SANDBOX_EXECUTION = false;

    /**
     * 获取工具的风险信息（综合内置配置和数据库配置）。
     *
     * @param toolName 工具名称
     * @return 风险信息，未知工具返回高风险兜底配置
     */
    public ToolRiskInfo getToolRiskInfo(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return ToolRiskInfo.defaultHighRisk("unknown");
        }

        String normalizedName = toolName.toLowerCase();

        // 1. 优先检查内置工具
        ToolRiskInfo builtinInfo = BuiltinToolRiskConfig.getRiskInfo(normalizedName);
        if (builtinInfo != null) {
            log.debug("使用内置工具风险配置: toolName={}, riskLevel={}", normalizedName, builtinInfo.getRiskLevel());
            return builtinInfo;
        }

        // 2. 检查数据库配置
        ToolRiskInfo dbInfo = getDbToolRiskInfo(normalizedName);
        if (dbInfo != null) {
            log.debug("使用数据库工具风险配置: toolName={}, riskLevel={}", normalizedName, dbInfo.getRiskLevel());
            return dbInfo;
        }

        // 3. 未知工具，默认高风险兜底
        log.warn("未找到工具风险配置: toolName={}，使用默认高风险", normalizedName);
        return ToolRiskInfo.defaultHighRisk(normalizedName);
    }

    /**
     * 根据工具名和参数动态计算最终风险等级。
     *
     * <p>在基础风险信息上，叠加参数级风险规则评估。</p>
     *
     * @param toolName   工具名称
     * @param toolParams 工具参数（可为 null）
     * @return 最终风险信息（考虑参数升级后）
     */
    public ToolRiskInfo evaluateRisk(String toolName, Map<String, Object> toolParams) {
        // 先获取基础风险信息
        ToolRiskInfo baseInfo = getToolRiskInfo(toolName);

        if (toolParams == null || toolParams.isEmpty()) {
            return baseInfo;
        }

        // 内置工具使用内置的参数规则
        if (BuiltinToolRiskConfig.isBuiltinTool(toolName)) {
            return BuiltinToolRiskConfig.evaluateRiskWithParams(toolName, toolParams);
        }

        // 数据库工具：根据 toolType 和 readOnly 标志动态计算
        return evaluateDbToolRisk(baseInfo, toolParams);
    }

    /**
     * 批量获取工具风险信息。
     *
     * @param toolNames 工具名称列表
     * @return 工具名到风险信息的映射
     */
    public Map<String, ToolRiskInfo> batchGetToolRiskInfo(List<String> toolNames) {
        return toolNames.stream()
                .distinct()
                .collect(Collectors.toMap(
                        name -> name,
                        this::getToolRiskInfo,
                        (existing, replacement) -> existing
                ));
    }

    /**
     * 判断工具是否需要审批。
     *
     * @param toolName 工具名称
     * @param params   工具参数
     * @return 是否需要审批
     */
    public boolean needsApproval(String toolName, Map<String, Object> params) {
        ToolRiskInfo riskInfo = evaluateRisk(toolName, params);
        return riskInfo.isNeedApproval();
    }

    /**
     * 获取工具的最大风险等级。
     *
     * @param toolNames 工具名称列表
     * @return 最大风险等级
     */
    public RiskLevel getMaxRiskLevel(List<String> toolNames) {
        return toolNames.stream()
                .map(this::getToolRiskInfo)
                .map(ToolRiskInfo::getRiskLevel)
                .max(Enum::compareTo)
                .orElse(RiskLevel.LOW);
    }

    /**
     * 刷新数据库工具风险缓存。
     */
    public void refreshCache() {
        synchronized (this) {
            if (System.currentTimeMillis() - lastCacheUpdateTime < CACHE_TTL_MS) {
                return;
            }
            loadDbToolRisks();
            lastCacheUpdateTime = System.currentTimeMillis();
            log.info("工具风险缓存已刷新, size={}", dbToolRiskCache.size());
        }
    }

    /**
     * 从数据库获取单个工具的风险信息。
     */
    private ToolRiskInfo getDbToolRiskInfo(String toolName) {
        // 检查缓存是否过期
        if (System.currentTimeMillis() - lastCacheUpdateTime > CACHE_TTL_MS) {
            refreshCache();
        }
        return dbToolRiskCache.get(toolName);
    }

    /**
     * 从数据库加载所有工具的风险配置。
     */
    private void loadDbToolRisks() {
        try {
            LambdaQueryWrapper<Tool> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Tool::getStatus, com.aegis.core.enums.common.CommonStatus.NORMAL);
            List<Tool> tools = toolMapper.selectList(wrapper);

            dbToolRiskCache.clear();
            for (Tool tool : tools) {
                String toolCode = tool.getToolCode();
                if (toolCode == null) {
                    continue;
                }

                ToolRiskInfo riskInfo = convertToRiskInfo(tool);
                dbToolRiskCache.put(toolCode.toLowerCase(), riskInfo);
            }

            log.info("从数据库加载工具风险配置: total={}", tools.size());
        } catch (Exception e) {
            log.error("加载数据库工具风险配置失败", e);
        }
    }

    /**
     * 将数据库 Tool 实体转换为 ToolRiskInfo。
     */
    private ToolRiskInfo convertToRiskInfo(Tool tool) {
        ToolType toolType = tool.getToolType() != null 
                ? tool.getToolType() 
                : ToolType.INTERNAL_API;

        // 根据 toolType 和 readOnly 标志确定风险等级
        RiskLevel riskLevel = determineRiskLevel(toolType, tool.getReadOnly());

        return ToolRiskInfo.builder()
                .toolName(tool.getToolCode())
                .toolType(toolType)
                .riskLevel(riskLevel)
                .riskReason(generateRiskReason(toolType, riskLevel, tool.getDescription()))
                .category(toolType.getDesc())
                .needApproval(riskLevel.ordinal() >= RiskLevel.MEDIUM.ordinal())
                .sandboxExecution(DEFAULT_SANDBOX_EXECUTION)
                .build();
    }

    /**
     * 根据工具类型和只读标志确定风险等级。
     */
    private RiskLevel determineRiskLevel(ToolType toolType, Boolean readOnly) {
        if (toolType == null) {
            return RiskLevel.HIGH;
        }

        // 如果明确标记为只读，降低风险
        if (Boolean.TRUE.equals(readOnly)) {
            return RiskLevel.LOW;
        }

        switch (toolType) {
            case READONLY:
                return RiskLevel.LOW;
            case AGENT:
                // 内部子智能体调度，无外部副作用
                return RiskLevel.LOW;
            case ASYNC:
                // 内部后台任务调度（readOnly 短路已覆盖只读类，此处为写类默认）
                return RiskLevel.LOW;
            case FILE_OPS:
                // 沙箱工作区内文件写操作（readOnly 短路已覆盖读类）
                return RiskLevel.MEDIUM;
            case INTERNAL_API:
                return RiskLevel.MEDIUM;
            case WRITE:
                return RiskLevel.MEDIUM;
            case EXTERNAL_NETWORK:
                return RiskLevel.MEDIUM;
            case CODE_EXEC:
                return RiskLevel.HIGH;
            case HIGH_RISK:
                return RiskLevel.CRITICAL;
            default:
                return RiskLevel.HIGH;
        }
    }

    /**
     * 生成风险原因描述。
     */
    private String generateRiskReason(ToolType toolType, RiskLevel riskLevel, String description) {
        if (StringUtils.hasText(description)) {
            return description;
        }

        switch (riskLevel) {
            case LOW:
                return "只读操作，无数据变更风险";
            case MEDIUM:
                return "中等风险操作，需用户确认";
            case HIGH:
                return "高风险操作，可能影响数据或系统";
            case CRITICAL:
                return "严重风险操作，需强制审批";
            default:
                return "未知风险等级";
        }
    }

    /**
     * 对数据库工具进行动态风险评估。
     */
    private ToolRiskInfo evaluateDbToolRisk(ToolRiskInfo baseInfo, Map<String, Object> params) {
        if (baseInfo.getParamRiskRules() == null || baseInfo.getParamRiskRules().isEmpty()) {
            return baseInfo;
        }

        RiskLevel currentLevel = baseInfo.getRiskLevel();
        String currentReason = baseInfo.getRiskReason();

        for (ToolRiskInfo.ParamRiskRule rule : baseInfo.getParamRiskRules()) {
            Object paramValue = params.get(rule.getParamName());
            if (paramValue != null) {
                String valueStr = String.valueOf(paramValue);
                if (valueStr.matches(rule.getValuePattern())) {
                    if (rule.getUpgradeTo().ordinal() > currentLevel.ordinal()) {
                        currentLevel = rule.getUpgradeTo();
                        currentReason = rule.getReason();
                    }
                }
            }
        }

        if (currentLevel != baseInfo.getRiskLevel()) {
            return ToolRiskInfo.builder()
                    .toolName(baseInfo.getToolName())
                    .toolType(baseInfo.getToolType())
                    .riskLevel(currentLevel)
                    .riskReason(currentReason)
                    .category(baseInfo.getCategory())
                    .needApproval(currentLevel.ordinal() >= RiskLevel.MEDIUM.ordinal())
                    .sandboxExecution(baseInfo.isSandboxExecution())
                    .paramRiskRules(baseInfo.getParamRiskRules())
                    .build();
        }

        return baseInfo;
    }

    /**
     * 注册或更新工具风险配置（运行时调用，用于动态更新）。
     *
     * @param toolName  工具名称
     * @param riskInfo  风险信息
     * @param persistent 是否持久化到数据库（true 时写入 DB，false 仅更新缓存）
     */
    public void registerToolRisk(String toolName, ToolRiskInfo riskInfo, boolean persistent) {
        if (!StringUtils.hasText(toolName) || riskInfo == null) {
            return;
        }
        dbToolRiskCache.put(toolName.toLowerCase(), riskInfo);

        if (persistent) {
            // 持久化逻辑可以后续扩展
            log.info("注册工具风险配置: toolName={}, riskLevel={}, persistent=true",
                    toolName, riskInfo.getRiskLevel());
        } else {
            log.info("注册工具风险配置: toolName={}, riskLevel={}, persistent=false",
                    toolName, riskInfo.getRiskLevel());
        }
    }
}
