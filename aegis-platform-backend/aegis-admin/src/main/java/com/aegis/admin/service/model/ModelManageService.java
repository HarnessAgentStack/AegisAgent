package com.aegis.admin.service.model;

import com.aegis.dal.mapper.model.ModelDefMapper;
import com.aegis.dal.mapper.model.ModelProviderMapper;
import com.aegis.dal.mapper.model.ModelRateLimitMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.model.ModelDef;
import com.aegis.core.domain.model.ModelProvider;
import com.aegis.core.domain.model.ModelRateLimit;
import com.aegis.core.dto.model.ModelDefCreateRequest;
import com.aegis.core.dto.model.ModelDefUpdateRequest;
import com.aegis.core.dto.model.ModelDefVO;
import com.aegis.core.dto.model.ModelProviderCreateRequest;
import com.aegis.core.dto.model.ModelProviderUpdateRequest;
import com.aegis.core.dto.model.ModelProviderVO;
import com.aegis.core.dto.model.ModelRateLimitSaveRequest;
import com.aegis.core.dto.model.ModelRateLimitVO;
import com.aegis.core.enums.model.ModelStatus;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.model.ModelType;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.util.XssSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import com.aegis.admin.web.model.ModelAdminController;

/**
 * 模型管理领域服务。
 *
 * <p>编排模型供应商、模型定义与限流策略的统一管理能力，
 * 为 {@code ModelAdminController} 提供平台级与租户级模型治理支撑。
 *
 * <h3>隔离设计</h3>
 * <ul>
 *   <li>{@code model_provider} / {@code model_def}：平台级表，无 tenant_id 隔离</li>
 *   <li>{@code model_rate_limit}：租户级表，
 *       由 MyBatis-Plus 多租户插件自动拼装 tenant_id 条件</li>
 * </ul>
 *
 * @author wang.zhen
 * @see ModelProvider
 * @see ModelDef
 * @see ModelRateLimit
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelManageService {

    private final ModelProviderMapper modelProviderMapper;
    private final ModelDefMapper modelDefMapper;
    private final ModelRateLimitMapper modelRateLimitMapper;

    // ============ 供应商管理 ============

    /**
     * 新增模型供应商（平台级）。
     *
     * @param req 供应商创建请求
     * @return 供应商ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createProvider(ModelProviderCreateRequest req) {
        if (req.getProviderCode() == null || req.getProviderCode().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "供应商编码不能为空");
        }
        if (req.getProviderName() == null || req.getProviderName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "供应商名称不能为空");
        }
        // XSS 清洗
        req.setProviderName(XssSanitizer.sanitize(req.getProviderName(), 200));
        // 编码全局唯一
        Long exists = modelProviderMapper.selectCount(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getProviderCode, req.getProviderCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "供应商编码已存在: " + req.getProviderCode());
        }

        ModelProvider provider = toProviderEntity(req);
        if (provider.getStatus() == null) provider.setStatus(ProviderStatus.PENDING);
        if (provider.getQpsLimit() == null) provider.setQpsLimit(100);
        if (provider.getMonthlyQuota() == null) provider.setMonthlyQuota(BigDecimal.ZERO);
        if (provider.getUsedQuota() == null) provider.setUsedQuota(BigDecimal.ZERO);
        if (provider.getModelCount() == null) provider.setModelCount(0);

        modelProviderMapper.insert(provider);
        log.info("模型供应商新增: id={}, code={}", provider.getId(), provider.getProviderCode());
        return provider.getId();
    }

    /**
     * 更新供应商配置。
     *
     * @param id  供应商ID
     * @param req 供应商更新请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProvider(Long id, ModelProviderUpdateRequest req) {
        ModelProvider existing = requireProvider(id);
        // XSS 清洗
        if (req.getProviderName() != null) {
            req.setProviderName(XssSanitizer.sanitize(req.getProviderName(), 200));
        }
        ModelProvider provider = toProviderEntity(req);
        provider.setId(id);
        // 编码不可变更，防止唯一性破坏
        provider.setProviderCode(existing.getProviderCode());
        modelProviderMapper.updateById(provider);
        log.info("模型供应商更新: id={}", id);
    }

    /**
     * 分页查询供应商（平台级）。
     *
     * @param page 页码
     * @param size 每页条数
     * @return 分页结果
     */
    public Page<ModelProviderVO> pageProviders(int page, int size) {
        Page<ModelProvider> pageObj = new Page<>(page, size);
        Page<ModelProvider> entityPage = modelProviderMapper.selectPage(pageObj, new LambdaQueryWrapper<ModelProvider>()
                .orderByDesc(ModelProvider::getCreateTime));
        Page<ModelProviderVO> voPage = new Page<>(page, size);
        voPage.setRecords(entityPage.getRecords().stream().map(this::toProviderVO).collect(Collectors.toList()));
        voPage.setTotal(entityPage.getTotal());
        return voPage;
    }

    /**
     * 测试供应商连接（真实 API 调用验证 API Key 有效性）。
     *
     * <p>向供应商端点发送一个最小化的 chat/completions 请求，
     * 验证 endpoint 可达且 API Key 有效。
     *
     * @param providerId 供应商ID
     * @return true 表示连接成功且 API Key 有效
     */
    public boolean testProviderConnection(Long providerId) {
        ModelProvider provider = requireProvider(providerId);
        String endpoint = provider.getEndpoint();
        String apiKey = provider.getApiKey();
        if (endpoint == null || endpoint.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "供应商端点未配置");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "供应商 API Key 未配置");
        }

        // 查找该供应商下第一个启用的对话模型用于测试（排除 EMBEDDING 模型）
        ModelDef testModel = modelDefMapper.selectOne(new LambdaQueryWrapper<ModelDef>()
                .eq(ModelDef::getProviderId, providerId)
                .eq(ModelDef::getStatus, "ENABLED")
                .ne(ModelDef::getModelType, com.aegis.core.enums.model.ModelType.EMBEDDING)
                .orderByAsc(ModelDef::getTier)
                .last("LIMIT 1"));
        String modelCode = testModel != null ? testModel.getModelCode() : "doubao-seed-2.0-lite";

        HttpURLConnection conn = null;
        try {
            String testUrl = endpoint.endsWith("/")
                    ? endpoint + "chat/completions"
                    : endpoint + "/chat/completions";
            URL url = new URL(testUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            // 最小化请求体
            String body = "{\"model\":\"" + modelCode + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}";
            conn.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                log.info("供应商连接测试成功: id={}, endpoint={}, model={}", providerId, testUrl, modelCode);
                return true;
            } else if (code == 401 || code == 403) {
                log.warn("供应商连接测试失败(API Key无效): id={}, code={}", providerId, code);
                throw new BusinessException(ResultCode.PARAM_ERROR, "API Key 无效或已过期 (HTTP " + code + ")");
            } else {
                log.warn("供应商连接测试异常: id={}, code={}", providerId, code);
                throw new BusinessException(ResultCode.PARAM_ERROR, "连接测试失败 (HTTP " + code + ")");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("供应商连接测试失败: id={}, endpoint={}, error={}", providerId, endpoint, e.getMessage());
            throw new BusinessException(ResultCode.PARAM_ERROR, "连接失败: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ============ 模型定义管理 ============

    /**
     * 新增模型定义（平台级）。
     *
     * @param req 模型定义创建请求
     * @return 模型ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createModel(ModelDefCreateRequest req) {
        if (req.getProviderId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "所属供应商ID不能为空");
        }
        if (req.getModelCode() == null || req.getModelCode().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "模型编码不能为空");
        }
        if (req.getModelName() == null || req.getModelName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "模型名称不能为空");
        }
        // XSS 清洗
        req.setModelName(XssSanitizer.sanitize(req.getModelName(), 200));
        // 校验供应商存在
        requireProvider(req.getProviderId());
        // 同供应商下模型编码唯一
        Long exists = modelDefMapper.selectCount(new LambdaQueryWrapper<ModelDef>()
                .eq(ModelDef::getProviderId, req.getProviderId())
                .eq(ModelDef::getModelCode, req.getModelCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "模型编码已存在: " + req.getModelCode());
        }

        ModelDef modelDef = toModelDefEntity(req);
        if (modelDef.getModelType() == null) modelDef.setModelType(com.aegis.core.enums.model.ModelType.TEXT);
        if (modelDef.getStatus() == null) modelDef.setStatus(ModelStatus.ENABLED.name());
        if (modelDef.getQpsLimit() == null) modelDef.setQpsLimit(100);
        if (modelDef.getInputCost() == null) modelDef.setInputCost(BigDecimal.ZERO);
        if (modelDef.getOutputCost() == null) modelDef.setOutputCost(BigDecimal.ZERO);

        modelDefMapper.insert(modelDef);
        // 更新供应商模型数量
        Long count = modelDefMapper.selectCount(new LambdaQueryWrapper<ModelDef>()
                .eq(ModelDef::getProviderId, modelDef.getProviderId()));
        modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProvider>()
                .eq(ModelProvider::getId, modelDef.getProviderId())
                .set(ModelProvider::getModelCount, count == null ? 0 : count.intValue()));
        log.info("模型定义新增: id={}, code={}, providerId={}", modelDef.getId(), modelDef.getModelCode(), modelDef.getProviderId());
        return modelDef.getId();
    }

    /**
     * 更新模型定义。
     *
     * @param id  模型ID
     * @param req 模型定义更新请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateModel(Long id, ModelDefUpdateRequest req) {
        ModelDef existing = requireModel(id);
        // XSS 清洗
        if (req.getModelName() != null) {
            req.setModelName(XssSanitizer.sanitize(req.getModelName(), 200));
        }
        ModelDef modelDef = toModelDefEntity(req);
        modelDef.setId(id);
        // 编码不可变更
        modelDef.setModelCode(existing.getModelCode());
        modelDefMapper.updateById(modelDef);
        log.info("模型定义更新: id={}", id);
    }

    /**
     * 查询模型列表（按供应商与档位过滤）。
     *
     * @param providerId 供应商ID（可空）
     * @param tier       模型档位字符串（LIGHT/STANDARD/STRONG，可空）
     * @return 模型列表
     */
    public List<ModelDefVO> listModels(Long providerId, String tier) {
        ModelTier modelTier = parseTier(tier);
        List<ModelDef> list = modelDefMapper.selectList(new LambdaQueryWrapper<ModelDef>()
                .eq(providerId != null, ModelDef::getProviderId, providerId)
                .eq(modelTier != null, ModelDef::getTier, modelTier)
                .orderByAsc(ModelDef::getTier));

        // 批量查询关联的 Provider，避免 N+1
        Set<Long> providerIds = list.stream()
                .map(ModelDef::getProviderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ModelProvider> providerMap = providerIds.isEmpty()
                ? new HashMap<>()
                : modelProviderMapper.selectBatchIds(providerIds).stream()
                        .collect(Collectors.toMap(ModelProvider::getId, p -> p, (a, b) -> a));

        return list.stream().map(m -> toModelDefVO(m, providerMap.get(m.getProviderId()))).collect(Collectors.toList());
    }

    /**
     * 用户侧：启用中的嵌入模型列表（知识库创建等场景的嵌入模型下拉）。
     *
     * <p>与 {@link #listModels} 的区别：仅返回 EMBEDDING + ENABLED，面向所有已认证用户，
     * 不暴露供应商密钥等管理细节。
     *
     * @return 启用中的嵌入模型列表
     */
    public List<ModelDefVO> listEnabledEmbeddingModels() {
        List<ModelDef> list = modelDefMapper.selectList(new LambdaQueryWrapper<ModelDef>()
                .eq(ModelDef::getModelType, ModelType.EMBEDDING)
                .eq(ModelDef::getStatus, ModelStatus.ENABLED.name())
                .orderByAsc(ModelDef::getTier));
        return list.stream().map(m -> toModelDefVO(m, null)).collect(Collectors.toList());
    }

    // ============ 限流策略管理 ============

    /**
     * 保存限流策略（租户级）。
     *
     * @param req 限流策略保存请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveRateLimit(ModelRateLimitSaveRequest req) {
        if (req.getScope() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "限流作用域不能为空");
        }
        ModelRateLimit limit = toRateLimitEntity(req);
        if (limit.getId() != null) {
            modelRateLimitMapper.updateById(limit);
            log.info("限流策略更新: id={}, scope={}", limit.getId(), limit.getScope());
        } else {
            if (limit.getUsedQps() == null) limit.setUsedQps(0);
            modelRateLimitMapper.insert(limit);
            log.info("限流策略新增: id={}, scope={}", limit.getId(), limit.getScope());
        }
    }

    /**
     * 查询租户限流策略列表。
     *
     * @param tenantId 租户ID（由租户上下文过滤）
     * @return 限流策略列表
     */
    public List<ModelRateLimitVO> listRateLimits(Long tenantId) {
        List<ModelRateLimit> list = modelRateLimitMapper.selectList(new LambdaQueryWrapper<ModelRateLimit>()
                .eq(tenantId != null, ModelRateLimit::getTenantId, tenantId)
                .orderByAsc(ModelRateLimit::getScope));
        return list.stream().map(this::toRateLimitVO).collect(Collectors.toList());
    }

    // ============ 内部方法 ============

    private ModelProvider requireProvider(Long providerId) {
        ModelProvider provider = modelProviderMapper.selectById(providerId);
        if (provider == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模型供应商不存在: " + providerId);
        }
        return provider;
    }

    private ModelDef requireModel(Long modelId) {
        ModelDef model = modelDefMapper.selectById(modelId);
        if (model == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模型定义不存在: " + modelId);
        }
        return model;
    }

    private ModelTier parseTier(String tier) {
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        try {
            return ModelTier.valueOf(tier.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的模型档位: " + tier);
        }
    }

    // ============ Entity <-> DTO/VO 转换 ============

    private ModelProvider toProviderEntity(ModelProviderCreateRequest req) {
        ModelProvider entity = new ModelProvider();
        entity.setProviderCode(req.getProviderCode());
        entity.setProviderName(req.getProviderName());
        entity.setEndpoint(req.getEndpoint());
        entity.setApiKey(req.getApiKey());
        entity.setStatus(req.getStatus());
        return entity;
    }

    private ModelProvider toProviderEntity(ModelProviderUpdateRequest req) {
        ModelProvider entity = new ModelProvider();
        entity.setProviderCode(req.getProviderCode());
        entity.setProviderName(req.getProviderName());
        entity.setEndpoint(req.getEndpoint());
        entity.setApiKey(req.getApiKey());
        entity.setStatus(req.getStatus());
        return entity;
    }

    private ModelProviderVO toProviderVO(ModelProvider entity) {
        String apiKey = entity.getApiKey();
        String masked = null;
        if (apiKey != null && apiKey.length() >= 10) {
            masked = apiKey.substring(0, 6) + "***...***" + apiKey.substring(apiKey.length() - 4);
        }
        Long modelCount = modelDefMapper.selectCount(new LambdaQueryWrapper<ModelDef>()
                .eq(ModelDef::getProviderId, entity.getId()));
        return ModelProviderVO.builder()
                .id(entity.getId())
                .providerCode(entity.getProviderCode())
                .providerName(entity.getProviderName())
                .endpoint(entity.getEndpoint())
                .apiKeyMasked(masked)
                .status(entity.getStatus())
                .modelCount(modelCount != null ? modelCount.intValue() : 0)
                .createTime(entity.getCreateTime())
                .build();
    }

    private ModelDef toModelDefEntity(ModelDefCreateRequest req) {
        ModelDef entity = new ModelDef();
        entity.setProviderId(req.getProviderId());
        entity.setModelCode(req.getModelCode());
        entity.setModelName(req.getModelName());
        entity.setTier(req.getTier());
        entity.setModelType(req.getModelType());
        entity.setContextWindow(req.getContextWindow());
        entity.setInputCost(req.getInputPrice());
        entity.setOutputCost(req.getOutputPrice());
        entity.setCapabilities(serializeCapabilities(req.getCapabilities()));
        entity.setStatus(req.getStatus() != null ? req.getStatus().name() : null);
        return entity;
    }

    private ModelDef toModelDefEntity(ModelDefUpdateRequest req) {
        ModelDef entity = new ModelDef();
        entity.setProviderId(req.getProviderId());
        entity.setModelCode(req.getModelCode());
        entity.setModelName(req.getModelName());
        entity.setTier(req.getTier());
        entity.setModelType(req.getModelType());
        entity.setContextWindow(req.getContextWindow());
        entity.setInputCost(req.getInputPrice());
        entity.setOutputCost(req.getOutputPrice());
        entity.setCapabilities(serializeCapabilities(req.getCapabilities()));
        entity.setStatus(req.getStatus() != null ? req.getStatus().name() : null);
        return entity;
    }

    /**
     * 将 capabilities 序列化为 JSON 字符串入库。
     *
     * <p>前端传 JSON 对象（Map/POJO），历史调用方可能传 String，统一归一化为 String。
     */
    private String serializeCapabilities(Object capabilities) {
        if (capabilities == null) {
            return null;
        }
        if (capabilities instanceof String s) {
            return s;
        }
        return com.alibaba.fastjson2.JSON.toJSONString(capabilities);
    }

    private ModelDefVO toModelDefVO(ModelDef entity, ModelProvider provider) {
        return ModelDefVO.builder()
                .id(entity.getId())
                .providerId(entity.getProviderId())
                .providerName(provider != null ? provider.getProviderName() : null)
                .providerCode(provider != null ? provider.getProviderCode() : null)
                .modelCode(entity.getModelCode())
                .modelName(entity.getModelName())
                .tier(entity.getTier())
                .modelType(entity.getModelType())
                .contextWindow(entity.getContextWindow())
                .inputPrice(entity.getInputCost())
                .outputPrice(entity.getOutputCost())
                .capabilities(entity.getCapabilities())
                .status(entity.getStatus() != null ? ModelStatus.valueOf(entity.getStatus()) : null)
                .qpsLimit(entity.getQpsLimit())
                .latency(entity.getLatency())
                .createTime(entity.getCreateTime())
                .build();
    }

    private ModelRateLimit toRateLimitEntity(ModelRateLimitSaveRequest req) {
        ModelRateLimit entity = new ModelRateLimit();
        entity.setId(req.getId());
        entity.setScope(req.getScope());
        entity.setScopeTargetId(req.getScopeTargetId());
        entity.setLightQps(req.getLightQps());
        entity.setStandardQps(req.getStandardQps());
        entity.setStrongQps(req.getStrongQps());
        entity.setTotalQps(req.getTotalQps());
        entity.setUsedQps(req.getUsedQps());
        entity.setAction(req.getAction());
        return entity;
    }

    private ModelRateLimitVO toRateLimitVO(ModelRateLimit entity) {
        return ModelRateLimitVO.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .scope(entity.getScope())
                .scopeTargetId(entity.getScopeTargetId())
                .lightQps(entity.getLightQps())
                .standardQps(entity.getStandardQps())
                .strongQps(entity.getStrongQps())
                .totalQps(entity.getTotalQps())
                .usedQps(entity.getUsedQps())
                .action(entity.getAction())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }
}
