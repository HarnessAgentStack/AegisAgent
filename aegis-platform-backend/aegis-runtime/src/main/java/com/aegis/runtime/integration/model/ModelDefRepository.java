package com.aegis.runtime.integration.model;

import com.aegis.core.domain.model.ModelDef;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.model.ModelType;
import com.aegis.dal.mapper.model.ModelDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 模型定义仓储：按 tier 查询启用的对话模型定义，并提供进程内缓存。
 *
 * <p>统一收敛 {@link LlmClientFactory} 与 {@link ModelRouteResolver} 此前各自维护的
 * 逐字相同的 {@code findModelDef} 实现与独立缓存——两者共享同一份缓存，同 tier 首次查询
 * 后命中缓存，避免重复 SQL。Admin 修改模型配置后由调用方触发 {@link #clearCache()} 失效。
 *
 * <h3>Tier 降级链</h3>
 * <p>当请求的 tier（如 STRONG）没有可用模型时，自动按 STRONG → LIGHT → STANDARD
 * 逐级降级，确保 Vision LLM / 摘要 / 改写等辅助 LLM 调用不会因档位缺失而断链。
 * 降级会在 WARN 日志中显式记录，便于运维发现配置缺口。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelDefRepository {

    private final ModelDefMapper modelDefMapper;

    /** tier → ModelDef 缓存，避免同档位重复 SQL */
    private final ConcurrentMap<ModelTier, ModelDef> cache = new ConcurrentHashMap<>();

    /** Tier 降级链：请求档查不到时依次尝试 */
    private static final List<ModelTier> TIER_FALLBACK = List.of(
            ModelTier.STRONG, ModelTier.LIGHT, ModelTier.STANDARD);

    /**
     * 按 tier 查询第一条启用的对话模型定义（带降级链）。
     *
     * <p>排除 EMBEDDING 类型模型（向量模型不支持 chat/completions），
     * 命中缓存时直接返回，未命中则查库并缓存（查询异常不缓存）。
     *
     * <p>若指定 tier 无模型，自动沿 TIER_FALLBACK 降级链找到第一个有模型的档位，
     * 再用 computeIfAbsent 对最终命中的 tier 做单次缓存——避免在 mapping function
     * 内部跨 key 手动 put 触发 ConcurrentHashMap Recursive update 并发异常。
     * 降级时在 WARN 日志中记录，方便运维补齐配置。
     *
     * @param tier 模型档位
     * @return 启用状态的对话模型定义
     * @throws IllegalStateException 所有档位（STRONG/LIGHT/STANDARD）均无可用模型时抛出
     */
    public ModelDef findModelDef(ModelTier tier) {
        // 1. 先沿降级链找到最终有模型的 tier（纯查询，不写缓存）
        ModelTier resolved = resolveTierWithFallback(tier);

        // 2. 用 computeIfAbsent 对最终 tier 做单次缓存（只操作一个 key，安全）
        ModelDef hit = cache.get(resolved);
        if (hit != null) {
            return hit;
        }
        return cache.computeIfAbsent(resolved, this::queryByTier);
    }

    /**
     * 清除 ModelDef 缓存。
     *
     * <p>由工厂的 {@code clearCache} 串联触发，使下次查询重新走库。
     */
    public void clearCache() {
        cache.clear();
        log.info("ModelDef 缓存已清除");
    }

    /**
     * 降级链解析：沿 TIER_FALLBACK 找第一个查得到模型的 tier。
     *
     * <p>只做只读查询，不触碰 ConcurrentHashMap。
     * 查不到任何档位时直接抛异常，不向上层返回。
     */
    private ModelTier resolveTierWithFallback(ModelTier requested) {
        // 优先按请求 tier 精确查
        if (queryByTier(requested) != null) {
            return requested;
        }

        // 沿降级链依次尝试
        for (ModelTier t : TIER_FALLBACK) {
            if (queryByTier(t) != null) {
                if (t != requested) {
                    log.warn("[ModelDef] tier 降级: requested={} → actual={}, modelDefTier={}",
                            requested, t, t);
                }
                return t;
            }
        }

        throw new IllegalStateException(
                "未找到任何可用对话模型（STRONG/LIGHT/STANDARD 全空），requested=" + requested);
    }

    /**
     * 按单个 tier 查询启用的对话模型（排除 EMBEDDING）。
     */
    private ModelDef queryByTier(ModelTier tier) {
        return modelDefMapper.selectOne(
                new LambdaQueryWrapper<ModelDef>()
                        .eq(ModelDef::getTier, tier)
                        .eq(ModelDef::getStatus, "ENABLED")
                        .ne(ModelDef::getModelType, ModelType.EMBEDDING)
                        .last("LIMIT 1"));
    }
}
