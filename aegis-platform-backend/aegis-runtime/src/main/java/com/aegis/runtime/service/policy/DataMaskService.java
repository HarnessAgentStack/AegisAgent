package com.aegis.runtime.service.policy;

import com.aegis.core.domain.security.MaskRule;
import com.aegis.core.enums.security.MaskWay;
import com.aegis.dal.mapper.security.MaskRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 数据脱敏处理器。
 *
 * <p>基于脱敏规则（sec_mask_rule）对文本中的敏感数据进行正则脱敏，支持多种脱敏方式：
 * <ul>
 *   <li>MIDDLE4：保留前3位与第7位后内容，中间4位替换为*</li>
 *   <li>KEEP_HEAD_TAIL：保留首尾各1位，中间替换为*</li>
 *   <li>KEEP_LAST4：仅保留最后4位，其余替换为*</li>
 *   <li>ALL：全部替换为*</li>
 *   <li>HASH：取 MD5 前8位</li>
 * </ul>
 *
 * <h3>P0-4 接入运行时消费链</h3>
 * <p>脱敏规则消费入口——对文本中的结构化敏感数据（身份证/手机号等）
 * 按规则遮蔽。Phase 1: delegated to AgentScope PermissionSystem。
 *
 * <h3>P0-5 显式租户条件</h3>
 * <p>sec_mask_rule 已加入 TENANT_IGNORE_TABLES，不再依赖 ThreadLocal 拦截器，
 * 本服务查询显式带 {@code .eq(tenantId)} 条件。
 *
 * <h3>P2-3 正则编译缓存</h3>
 * <p>不再每次调用 {@code Pattern.compile}——预编译 Pattern 按 ruleId+regex 缓存。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataMaskService {

    private final MaskRuleMapper maskRuleMapper;

    /** 预编译正则缓存：ruleId+regex → Pattern（P2-3，避免每次 Pattern.compile） */
    private final Map<String, Pattern> compiledCache = new ConcurrentHashMap<>();

    /**
     * 对输入文本应用所有启用的脱敏规则（按租户隔离）。
     *
     * @param text     原始文本
     * @param tenantId 租户 ID（显式租户条件，不依赖 ThreadLocal）
     * @return 脱敏后文本；无规则或无匹配时原样返回
     */
    public String mask(String text, Long tenantId) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        List<MaskRule> rules = loadCachedMaskRules(tenantId);
        if (rules == null || rules.isEmpty()) {
            return text;
        }

        String result = text;
        for (MaskRule rule : rules) {
            if (rule.getRegex() == null || rule.getRegex().isEmpty()) {
                continue;
            }
            try {
                Pattern pattern = compilePattern(rule);
                if (pattern == null) {
                    continue;
                }
                java.util.regex.Matcher matcher = pattern.matcher(result);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String matched = matcher.group();
                    String masked = maskValue(matched, rule.getMaskWay());
                    matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(masked));
                }
                matcher.appendTail(sb);
                result = sb.toString();
            } catch (Exception e) {
                log.warn("DataMask rule apply failed: regex={}, maskWay={}",
                        rule.getRegex(), rule.getMaskWay(), e);
            }
        }
        return result;
    }

    /**
     * 按租户加载启用的脱敏规则（直接查 DB）。
     * Phase 1: delegated to AgentScope PermissionSystem（原二级缓存依赖已移除）。
     */
    private List<MaskRule> loadCachedMaskRules(Long tenantId) {
        if (tenantId == null) {
            return java.util.Collections.emptyList();
        }
        List<MaskRule> fromDb = maskRuleMapper.selectList(new LambdaQueryWrapper<MaskRule>()
                .eq(MaskRule::getTenantId, tenantId)
                .eq(MaskRule::getEnabled, true));
        if (fromDb == null) {
            return java.util.Collections.emptyList();
        }
        return fromDb;
    }

    /**
     * 编译并缓存脱敏正则（P2-3：避免每次调用 Pattern.compile）。
     *
     * <p>按 ruleId+regex 组合 key 缓存预编译 Pattern；规则文本变更会生成不同 key
     * 自动失效旧条目（无显式淘汰，规则总量小且覆盖式写入，内存可忽略）。
     */
    private Pattern compilePattern(MaskRule rule) {
        String key = (rule.getId() != null ? rule.getId() : "noid") + "#" + rule.getRegex();
        Pattern cached = compiledCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Pattern pattern = Pattern.compile(rule.getRegex());
            compiledCache.put(key, pattern);
            return pattern;
        } catch (Exception e) {
            log.warn("MaskRule 正则编译失败，跳过: id={}, regex={}", rule.getId(), rule.getRegex());
            return null;
        }
    }

    /**
     * 按脱敏方式对单个值进行脱敏。
     *
     * @param value   原始值
     * @param maskWay 脱敏方式
     * @return 脱敏后值
     */
    private String maskValue(String value, MaskWay maskWay) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int len = value.length();
        return switch (maskWay) {
            case MIDDLE4 -> {
                if (len <= 7) {
                    yield "*".repeat(len);
                }
                yield value.substring(0, 3) + "****" + value.substring(7);
            }
            case KEEP_HEAD_TAIL -> {
                if (len <= 2) {
                    yield "*".repeat(len);
                }
                yield value.charAt(0) + "*".repeat(len - 2) + value.charAt(len - 1);
            }
            case KEEP_LAST4 -> {
                if (len <= 4) {
                    yield "*".repeat(len);
                }
                yield "*".repeat(len - 4) + value.substring(len - 4);
            }
            case ALL -> "*".repeat(len);
            case HASH -> DigestUtils.md5Hex(value).substring(0, 8);
        };
    }
}
