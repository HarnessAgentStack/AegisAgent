package com.aegis.runtime.integration.model;

import com.aegis.core.domain.model.ModelDef;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.model.ModelType;
import com.aegis.dal.mapper.model.ModelDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 模型定义仓储：按 tier 查询启用的对话模型定义，并提供进程内缓存。
 *
 * <p>统一收敛 {@link LlmClientFactory} 与 {@link ModelRouteResolver} 此前各自维护的
 * 逐字相同的 {@code findModelDef} 实现与独立缓存——两者共享同一份缓存，同 tier 首次查询
 * 后命中缓存，避免重复 SQL。Admin 修改模型配置后由调用方触发 {@link #clearCache()} 失效。
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

    /**
     * 按 tier 查询第一条启用的对话模型定义。
     *
     * <p>排除 EMBEDDING 类型模型（向量模型不支持 chat/completions），
     * 命中缓存时直接返回，未命中则查库并缓存（查询异常不缓存）。
     *
     * @param tier 模型档位
     * @return 启用状态的对话模型定义
     * @throws IllegalStateException 未找到可用模型时抛出
     */
    public ModelDef findModelDef(ModelTier tier) {
        return cache.computeIfAbsent(tier, this::queryFromDb);
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

    private ModelDef queryFromDb(ModelTier tier) {
        ModelDef modelDef = modelDefMapper.selectOne(
                new LambdaQueryWrapper<ModelDef>()
                        .eq(ModelDef::getTier, tier)
                        .eq(ModelDef::getStatus, "ENABLED")
                        .ne(ModelDef::getModelType, ModelType.EMBEDDING)
                        .last("LIMIT 1"));
        if (modelDef == null) {
            throw new IllegalStateException("未找到可用对话模型: tier=" + tier);
        }
        return modelDef;
    }
}
