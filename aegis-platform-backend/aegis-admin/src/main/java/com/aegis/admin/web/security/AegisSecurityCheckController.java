package com.aegis.admin.web.security;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.security.MaskRule;
import com.aegis.core.domain.security.ToolPolicy;
import com.aegis.core.enums.resource.ToolPolicyAction;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.core.enums.security.MaskWay;
import com.aegis.dal.mapper.security.MaskRuleMapper;
import com.aegis.dal.mapper.security.ToolPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全策略诊断接口。
 *
 * <p>提供出站策略、内容脱敏、权限评估的可视化测试端点，供管理台运营人员
 * 在配置策略后快速验证拦截 / 脱敏 / 处置行为是否符合预期。
 *
 * <p>实现复用 aegis-admin 已有的数据层（{@link ToolPolicyMapper} / {@link MaskRuleMapper}）
 * 与 aegis-core 枚举，内联复刻 aegis-runtime 中
 * {@code AegisPermissionRuleLoader#evaluateBehavior} 与 {@code DataMaskService#mask}
 * 的决策语义，避免跨越 admin ↔ runtime 的模块边界（admin 启动类显式排除了
 * {@code com.aegis.runtime.*} 的组件扫描）。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/security/check")
@RequiredArgsConstructor
public class AegisSecurityCheckController {

    private final ToolPolicyMapper toolPolicyMapper;
    private final MaskRuleMapper maskRuleMapper;

    @PostMapping("/outbound/test")
    public Result<OutboundTestResult> testOutbound(@RequestBody OutboundTestRequest req,
                                                    @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        if (req == null || req.url() == null || req.url().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "url 不能为空");
        }
        String url = req.url().trim();
        String host;
        try {
            URI uri = new URI(url);
            host = uri.getHost();
        } catch (URISyntaxException e) {
            return Result.success(new OutboundTestResult(url, null, true, "URL 格式非法，按拦截处理"));
        }
        if (host == null || host.isBlank()) {
            return Result.success(new OutboundTestResult(url, null, true, "URL 缺少主机地址，按拦截处理"));
        }
        boolean blocked = isInternalHost(host.toLowerCase());
        String reason = blocked ? "命中内网地址拦截策略" : "未命中拦截策略";
        return Result.success(new OutboundTestResult(url, host, blocked, reason));
    }

    @PostMapping("/content/test")
    public Result<ContentTestResult> testContent(@RequestBody ContentTestRequest req,
                                                  @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        if (req == null || req.text() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "text 不能为空");
        }
        String original = req.text();
        if (original.isEmpty() || tenantId == null) {
            return Result.success(new ContentTestResult(original, original, 0));
        }
        List<MaskRule> rules = maskRuleMapper.selectList(new LambdaQueryWrapper<MaskRule>()
                .eq(MaskRule::getTenantId, tenantId)
                .eq(MaskRule::getEnabled, true));
        if (rules == null || rules.isEmpty()) {
            return Result.success(new ContentTestResult(original, original, 0));
        }
        String result = original;
        int applied = 0;
        for (MaskRule rule : rules) {
            if (rule.getRegex() == null || rule.getRegex().isEmpty() || rule.getMaskWay() == null) {
                continue;
            }
            Pattern pattern;
            try {
                pattern = Pattern.compile(rule.getRegex());
            } catch (Exception e) {
                log.warn("脱敏正则编译失败，跳过: id={}, regex={}", rule.getId(), rule.getRegex());
                continue;
            }
            Matcher matcher = pattern.matcher(result);
            StringBuffer sb = new StringBuffer();
            boolean matched = false;
            while (matcher.find()) {
                matched = true;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(maskValue(matcher.group(), rule.getMaskWay())));
            }
            matcher.appendTail(sb);
            if (matched) {
                result = sb.toString();
                applied++;
            }
        }
        return Result.success(new ContentTestResult(original, result, applied));
    }

    @PostMapping("/permission/test")
    public Result<PermissionTestResult> testPermission(@RequestBody PermissionTestRequest req,
                                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        if (req == null || req.toolType() == null || req.securityLevel() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "toolType 与 securityLevel 不能为空");
        }
        ToolType toolType = req.toolType();
        int securityLevel = req.securityLevel();
        String governanceTier = req.governanceTier();

        ToolPolicyAction action = lookupPolicyAction(tenantId, toolType, securityLevel, governanceTier);
        String behavior;
        String source;
        if (action != null) {
            behavior = toBehavior(action);
            source = "DB_POLICY";
        } else {
            behavior = defaultBehavior(securityLevel);
            source = "DEFAULT";
        }
        return Result.success(new PermissionTestResult(toolType.name(), securityLevel, governanceTier, behavior, source));
    }

    private boolean isInternalHost(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost")
                || "0.0.0.0".equals(host) || "::1".equals(host) || host.startsWith("fe80:")) {
            return true;
        }
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            try {
                long ip = ipToLong(host);
                if ((ip & 0xFF000000L) == (127L << 24)) {
                    return true;
                }
                if ((ip & 0xFF000000L) == (10L << 24)) {
                    return true;
                }
                if ((ip & 0xFFF00000L) == ipToLong("172.16.0.0")) {
                    return true;
                }
                if ((ip & 0xFFFF0000L) == ipToLong("192.168.0.0")) {
                    return true;
                }
            } catch (NumberFormatException ignore) {
                return false;
            }
            return false;
        }
        return host.endsWith(".internal") || host.endsWith(".local")
                || host.endsWith(".lan") || host.endsWith(".intranet");
    }

    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0L;
        for (String p : parts) {
            result = (result << 8) | (Integer.parseInt(p) & 0xFF);
        }
        return result;
    }

    private ToolPolicyAction lookupPolicyAction(Long tenantId, ToolType toolType, int securityLevel, String governanceTier) {
        LambdaQueryWrapper<ToolPolicy> wrapper = new LambdaQueryWrapper<ToolPolicy>()
                .eq(tenantId != null, ToolPolicy::getTenantId, tenantId)
                .eq(ToolPolicy::getEnabled, true);
        List<ToolPolicy> all = toolPolicyMapper.selectList(wrapper);
        Map<String, ToolPolicyAction> matrix = new HashMap<>();
        for (ToolPolicy p : all) {
            if (p.getToolType() == null || p.getSecurityLevel() == null || p.getAction() == null) {
                continue;
            }
            if (!appliesToTier(p.getGovernanceTierMin(), governanceTier)) {
                continue;
            }
            matrix.put(p.getToolType().name() + ":" + p.getSecurityLevel(), p.getAction());
        }
        return matrix.get(toolType.name() + ":" + securityLevel);
    }

    private boolean appliesToTier(String minTier, String currentTier) {
        if (minTier == null || minTier.isBlank()) {
            return true;
        }
        if (currentTier == null) {
            return true;
        }
        return tierRank(currentTier) >= tierRank(minTier);
    }

    private int tierRank(String tier) {
        return switch (tier.toUpperCase()) {
            case "STANDARD" -> 1;
            case "ENHANCED" -> 2;
            case "STRICT" -> 3;
            default -> 1;
        };
    }

    private String toBehavior(ToolPolicyAction action) {
        return switch (action) {
            case ALLOW -> "ALLOW";
            case APPROVE -> "ASK";
            case REJECT -> "DENY";
        };
    }

    private String defaultBehavior(int securityLevel) {
        if (securityLevel <= 2) {
            return "ALLOW";
        }
        if (securityLevel == 3) {
            return "ASK";
        }
        return "DENY";
    }

    private String maskValue(String value, MaskWay maskWay) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int len = value.length();
        return switch (maskWay) {
            case MIDDLE4 -> len <= 7 ? "*".repeat(len) : value.substring(0, 3) + "****" + value.substring(7);
            case KEEP_HEAD_TAIL -> len <= 2 ? "*".repeat(len)
                    : value.charAt(0) + "*".repeat(len - 2) + value.charAt(len - 1);
            case KEEP_LAST4 -> len <= 4 ? "*".repeat(len) : "*".repeat(len - 4) + value.substring(len - 4);
            case ALL -> "*".repeat(len);
            case HASH -> md5Hex8(value);
        };
    }

    private String md5Hex8(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (Exception e) {
            return "*".repeat(value.length());
        }
    }

    public record OutboundTestRequest(String url) {
    }

    public record ContentTestRequest(String text) {
    }

    public record PermissionTestRequest(ToolType toolType, Integer securityLevel, String governanceTier) {
    }

    public record OutboundTestResult(String url, String host, boolean blocked, String reason) {
    }

    public record ContentTestResult(String original, String masked, int rulesApplied) {
    }

    public record PermissionTestResult(String toolType, int securityLevel, String governanceTier,
                                        String behavior, String source) {
    }
}
