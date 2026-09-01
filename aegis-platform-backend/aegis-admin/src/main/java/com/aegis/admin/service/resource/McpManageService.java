package com.aegis.admin.service.resource;

import com.aegis.dal.mapper.resource.McpServiceMapper;
import com.aegis.dal.mapper.resource.McpSubscriptionMapper;
import com.aegis.dal.mapper.resource.ToolMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.McpService;
import com.aegis.core.domain.resource.McpSubscription;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.dto.resource.McpServiceCreateRequest;
import com.aegis.core.dto.resource.McpServiceRegisterRequest;
import com.aegis.core.dto.resource.McpServiceVO;
import com.aegis.core.dto.resource.ToolVO;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.McpProtocol;
import com.aegis.core.enums.resource.SubscriberType;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.util.XssSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP 管理领域服务。
 *
 * <p>编排 MCP 服务发布（平台级）与用户订阅能力，
 * 并提供工具注册到 res_tool 表的统一入口。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>安全等级仅在资源层（MCP/Tool/Skill/KB），智能体仅使用治理档位</li>
 *   <li>MCP Server 自动注册到 ADMIN，管理员审核配置安全等级后发布</li>
 *   <li>去除 McpClient 模型，简化为 MCP 服务直接订阅</li>
 * </ul>
 *
 * @author wang.zhen
 * @see McpService
 * @see Tool
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpManageService {

    private final McpServiceMapper mcpServiceMapper;
    private final McpSubscriptionMapper mcpSubscriptionMapper;
    private final McpClientService mcpClientService;
    private final McpToolSyncService mcpToolSyncService;
    private final ToolMapper toolMapper;
    private final ResourceChangePublisher resourceChangePublisher;

    /**
     * 发布/注册 MCP 服务（管理员平台级）。
     *
     * <p>创建 MCP 服务记录（lifeStatus=DRAFT），需经审核后方可进入市场。
     *
     * @param req MCP 服务创建请求
     * @return 服务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long publishService(McpServiceCreateRequest req) {
        if (req.getMcpCode() == null || req.getMcpCode().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "MCP服务编码不能为空");
        }
        if (req.getMcpName() == null || req.getMcpName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "MCP服务名称不能为空");
        }
        if (req.getEndpoint() == null || req.getEndpoint().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "MCP服务端点不能为空");
        }
        // 编码全局唯一
        Long exists = mcpServiceMapper.selectCount(new LambdaQueryWrapper<McpService>()
                .eq(McpService::getMcpCode, req.getMcpCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "MCP服务编码已存在: " + req.getMcpCode());
        }

        McpService mcpService = new McpService();
        BeanUtils.copyProperties(req, mcpService);
        // XSS 清洗
        mcpService.setMcpName(XssSanitizer.sanitize(req.getMcpName(), 200));
        mcpService.setDescription(XssSanitizer.sanitize(req.getDescription(), 1000));

        if (mcpService.getVersion() == null) mcpService.setVersion("1.0.0");
        if (mcpService.getStatus() == null) mcpService.setStatus(ProviderStatus.PENDING);
        if (mcpService.getLifeStatus() == null) mcpService.setLifeStatus(AgentLifeStatus.DRAFT);
        if (mcpService.getSecurityLevel() == null) mcpService.setSecurityLevel(SecurityLevel.L1);
        if (mcpService.getToolCount() == null) mcpService.setToolCount(0);
        if (mcpService.getSubsCount() == null) mcpService.setSubsCount(0);

        try {
            mcpServiceMapper.insert(mcpService);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "MCP服务编码已存在: " + req.getMcpCode());
        }
        log.info("MCP服务发布: id={}, code={}", mcpService.getId(), mcpService.getMcpCode());
        return mcpService.getId();
    }

    /**
     * MCP Server 自注册（Service-to-Service）：仅注册服务元信息，工具动态发现。
     *
     * <p>供外部 MCP Server（如 aegis-mcp-demo）在启动时上送自身元信息，
     * 仅完成服务创建/复用，不再持久化工具到 res_tool 表。
     * 工具列表将在运行时由智能体通过 SSE/HTTP 协议动态查询 MCP 服务获取。
     *
     * <p>设计原则：MCP 工具是服务的动态属性，不应静态存储。
     * 智能体运行时通过 McpInvoker.listTools() 实时获取工具列表。
     *
     * @param req 自注册请求（含 MCP 服务元信息，工具列表作为可选参考信息）
     * @return 服务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long registerFromServer(McpServiceRegisterRequest req) {
        McpServiceCreateRequest serviceReq = McpServiceCreateRequest.builder()
                .mcpCode(req.getMcpCode())
                .mcpName(req.getMcpName())
                .icon(req.getIcon())
                .provider(req.getProvider())
                .description(req.getDescription())
                .version(req.getVersion())
                .endpoint(req.getEndpoint())
                .protocol(req.getProtocol() != null
                        ? com.aegis.core.enums.resource.McpProtocol.valueOf(req.getProtocol()) : null)
                .authType(req.getAuthType() != null
                        ? com.aegis.core.enums.api.ApiAuthType.valueOf(req.getAuthType()) : null)
                .authConfig(req.getAuthConfig())
                .securityLevel(req.getSecurityLevel() != null
                        ? SecurityLevel.valueOf(req.getSecurityLevel()) : null)
                .status(req.getStatus() != null
                        ? ProviderStatus.valueOf(req.getStatus()) : null)
                .build();

        Long id;
        try {
            id = publishService(serviceReq);
        } catch (BusinessException e) {
            if (e.getResultCode() == ResultCode.CONFLICT) {
                McpService existing = mcpServiceMapper.selectOne(new LambdaQueryWrapper<McpService>()
                        .eq(McpService::getMcpCode, req.getMcpCode())
                        .last("LIMIT 1"));
                if (existing != null) {
                    id = existing.getId();
                    log.info("MCP服务已存在，复用并更新元数据: id={}, code={}", id, req.getMcpCode());

                    // 更新已有服务的端点、协议等元数据（MCP Server 可能在重启时变更配置）
                    mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                            .eq(McpService::getId, id)
                            .set(McpService::getEndpoint, serviceReq.getEndpoint())
                            .set(McpService::getProtocol, serviceReq.getProtocol())
                            .set(McpService::getVersion, serviceReq.getVersion())
                            .set(McpService::getDescription, serviceReq.getDescription())
                            .set(McpService::getStatus, serviceReq.getStatus()));
                } else {
                    throw e;
                }
            } else {
                throw e;
            }
        }

        // 不再持久化工具到 res_tool 表
        // 工具列表在运行时由智能体通过 SSE/HTTP 协议动态查询
        // 此处仅更新初始工具数量（如果 MCP Server 上送了工具列表作为参考）
        if (req.getTools() != null && !req.getTools().isEmpty()) {
            int toolCount = req.getTools().size();
            mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                    .eq(McpService::getId, id)
                    .set(McpService::getToolCount, toolCount));
            log.info("MCP服务初始工具数已记录（仅供参考，运行时动态获取）: serviceId={}, toolCount={}",
                    id, toolCount);
        }

        return id;
    }

    /**
     * 启用 MCP 服务。
     *
     * <p>需服务已审核发布（lifeStatus=PUBLISHED），状态置为 ACTIVE。
     *
     * @param serviceId 服务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void activateService(Long serviceId) {
        McpService existing = requireService(serviceId);
        if (existing.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "MCP服务尚未审核发布，无法激活。当前状态: " + existing.getLifeStatus());
        }
        mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                .eq(McpService::getId, serviceId)
                .set(McpService::getStatus, ProviderStatus.ACTIVE)
                .set(McpService::getPublishedTime, LocalDateTime.now()));
        log.info("MCP服务启用: id={}, code={}", serviceId, existing.getMcpCode());

        mcpToolSyncService.asyncSyncTools(serviceId, existing.getEndpoint(),
                existing.getProtocol(), existing.getSecurityLevel());

        resourceChangePublisher.publishMcpSubscriptionChanged(null, null, "ACTIVATE");
    }

    /**
     * 禁用 MCP 服务。
     *
     * <p>状态置为 PENDING。MCP 工具为动态发现，无需在数据库中禁用。
     *
     * @param serviceId 服务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deactivateService(Long serviceId) {
        McpService existing = requireService(serviceId);
        mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                .eq(McpService::getId, serviceId)
                .set(McpService::getStatus, ProviderStatus.PENDING));
        log.info("MCP服务禁用: id={}, code={}", serviceId, existing.getMcpCode());

        resourceChangePublisher.publishMcpSubscriptionChanged(null, null, "DEACTIVATE");
    }

    /**
     * 删除 MCP 服务。
     *
     * <p>MCP 工具为动态发现，删除服务时无需清理工具表。
     *
     * @param serviceId 服务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteService(Long serviceId) {
        McpService existing = requireService(serviceId);
        mcpServiceMapper.deleteById(serviceId);
        log.info("MCP服务删除: id={}, code={}", serviceId, existing.getMcpCode());
    }

    /**
     * 分页查询 MCP 服务（平台级）。
     *
     * @param page 页码
     * @param size 每页条数
     * @return 分页结果
     */
    public Page<McpServiceVO> pageServices(int page, int size) {
        Page<McpService> pageObj = new Page<>(page, size);
        Page<McpService> entityPage = mcpServiceMapper.selectPage(pageObj, new LambdaQueryWrapper<McpService>()
                .orderByDesc(McpService::getCreateTime));
        return convertPage(entityPage, this::toServiceVO, page, size);
    }

    /**
     * 查询 MCP 服务详情。
     *
     * @param serviceId 服务ID
     * @return 服务详情
     */
    public McpServiceVO getServiceDetail(Long serviceId) {
        return toServiceVO(requireService(serviceId));
    }

    /**
     * 更新 MCP 服务的工具数量（元数据更新，不持久化工具本身）。
     *
     * <p>MCP 工具为动态发现，仅更新服务的 tool_count 字段用于列表展示。
     * 实际工具列表通过 {@link McpClientService} 动态查询。
     *
     * @param sourceId 来源ID（MCP 服务ID）
     * @param tools    工具列表（仅用于计数，不持久化）
     */
    @Transactional(rollbackFor = Exception.class)
    public void registerTools(Long sourceId, List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        // 仅更新工具数量元数据，工具本身不持久化
        int count = tools.size();
        mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                .eq(McpService::getId, sourceId)
                .set(McpService::getToolCount, count));
        log.info("MCP服务工具数量更新（元数据）: sourceId={}, count={}", sourceId, count);
    }

    /**
     * 查询 MCP 服务详情（含工具列表和审核记录）。
     *
     * <p>工具列表通过 {@link McpClientService} 动态查询 MCP Server 获取。
     *
     * @param serviceId 服务ID
     * @return 服务详情 VO
     */
    public McpServiceVO getServiceDetailWithTools(Long serviceId) {
        McpService service = requireService(serviceId);
        McpServiceVO vo = toServiceVO(service);
        List<ToolVO> tools = listServiceTools(serviceId);
        vo.setTools(tools);
        vo.setToolCount(tools.size());
        return vo;
    }

    /**
     * 查询 MCP 服务提供的工具列表（动态查询 + DB 降级）。
     *
     * <p>优先通过 MCP 标准协议实时查询；查询失败或返回空时，
     * 降级从 res_tool 表读取已缓存的工具。
     *
     * @param serviceId 服务ID
     * @return 工具 VO 列表
     */
    public List<ToolVO> listServiceTools(Long serviceId) {
        McpService service = requireService(serviceId);
        if (service.getStatus() != ProviderStatus.ACTIVE) {
            log.warn("listServiceTools: MCP 服务未激活, serviceId={}, status={}", serviceId, service.getStatus());
            return queryToolsFromDb(serviceId);
        }

        McpProtocol protocol = service.getProtocol();
        log.info("listServiceTools: 查询 MCP 工具列表, serviceId={}, endpoint={}, protocol={}",
                serviceId, service.getEndpoint(), protocol);

        try {
            List<ToolVO> tools = mcpClientService.queryTools(service.getEndpoint(), protocol);
            if (tools != null && !tools.isEmpty()) {
                log.info("listServiceTools: 实时查询成功, serviceId={}, toolCount={}", serviceId, tools.size());
                return tools;
            }
            log.warn("listServiceTools: 实时查询返回空, 降级读DB, serviceId={}", serviceId);
        } catch (Exception e) {
            log.warn("listServiceTools: 实时查询失败, 降级读DB, serviceId={}, error={}", serviceId, e.getMessage());
        }

        return queryToolsFromDb(serviceId);
    }

    /**
     * 从 res_tool 表查询已缓存的 MCP 工具列表。
     */
    private List<ToolVO> queryToolsFromDb(Long serviceId) {
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getMcpServiceId, serviceId)
                .eq(Tool::getStatus, CommonStatus.NORMAL)
                .eq(Tool::getSourceType, ToolSourceType.MCP));
        if (tools == null || tools.isEmpty()) {
            log.warn("queryToolsFromDb: DB中无缓存工具, serviceId={}", serviceId);
            return List.of();
        }
        log.info("queryToolsFromDb: 从DB加载缓存工具, serviceId={}, count={}", serviceId, tools.size());
        return tools.stream().map(this::toToolVO).collect(Collectors.toList());
    }

    private ToolVO toToolVO(Tool tool) {
        ToolVO vo = new ToolVO();
        vo.setId(tool.getId());
        vo.setToolCode(tool.getToolCode());
        vo.setToolName(tool.getToolName());
        vo.setDescription(tool.getDescription());
        vo.setToolType(tool.getToolType());
        vo.setSourceType(tool.getSourceType());
        vo.setReadOnly(tool.getReadOnly());
        vo.setInputSchema(tool.getInputSchema());
        vo.setOutputSchema(tool.getOutputSchema());
        vo.setSecurityLevel(tool.getSecurityLevel());
        vo.setStatus(tool.getStatus());
        return vo;
    }

    private McpService requireService(Long serviceId) {
        McpService service = mcpServiceMapper.selectById(serviceId);
        if (service == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "MCP服务不存在: " + serviceId);
        }
        return service;
    }

    private McpServiceVO toServiceVO(McpService service) {
        McpServiceVO vo = new McpServiceVO();
        BeanUtils.copyProperties(service, vo);
        return vo;
    }

    private <E, V> Page<V> convertPage(Page<E> entityPage, Function<E, V> converter, int page, int size) {
        Page<V> voPage = new Page<>(page, size);
        voPage.setTotal(entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(converter).collect(Collectors.toList()));
        return voPage;
    }

    // ============ 用户侧：MCP 服务市场 ============

    /**
     * 市场分页列表（仅已发布 PUBLISHED 的 MCP 服务）。
     *
     * <p>对于 ACTIVE 状态的服务，实时查询 MCP Server 获取工具摘要（最多5个工具名称预览）。
     * 使用并行查询 + 3s 超时，确保列表加载性能。
     *
     * @param keyword 关键字搜索（可选，按 mcpName/mcpCode/description 模糊匹配）
     * @param page    页码
     * @param size    每页条数
     * @return 分页结果
     */
    public Page<McpServiceVO> pageMarketServices(Long tenantId, Long userId, String keyword, int page, int size) {
        Page<McpService> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<McpService> wrapper = new LambdaQueryWrapper<McpService>()
                .eq(McpService::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .orderByDesc(McpService::getPublishedTime);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like(McpService::getMcpName, keyword)
                    .or().like(McpService::getMcpCode, keyword)
                    .or().like(McpService::getDescription, keyword));
        }
        Page<McpService> entityPage = mcpServiceMapper.selectPage(pageObj, wrapper);
        Page<McpServiceVO> result = convertPage(entityPage, this::toServiceVO, page, size);

        enrichWithToolPreviews(result.getRecords());

        if (tenantId != null && userId != null) {
            for (McpServiceVO vo : result.getRecords()) {
                vo.setSubscribed(isSubscribed(tenantId, userId, vo.getId()));
            }
        }
        return result;
    }

    // ============ 用户侧：订阅操作 ============

    /**
     * 订阅 MCP 服务（即订即用，无需审核）。
     *
     * <p>创建订阅记录到 res_mcp_subscription 表，
     * 同一用户同一服务在同一租户下不可重复订阅。
     *
     * @param tenantId  租户ID
     * @param userId    当前用户ID
     * @param serviceId MCP 服务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void subscribeService(Long tenantId, Long userId, Long serviceId) {
        McpService service = requireService(serviceId);
        if (service.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT, "MCP服务尚未审核发布，无法订阅: " + service.getMcpName());
        }

        // 检查是否已订阅（同一租户、同一用户、同一服务）
        Long exists = mcpSubscriptionMapper.selectCount(new LambdaQueryWrapper<McpSubscription>()
                .eq(McpSubscription::getTenantId, tenantId)
                .eq(McpSubscription::getMcpServiceId, serviceId)
                .eq(McpSubscription::getSubscriberType, SubscriberType.USER)
                .eq(McpSubscription::getSubscriberId, userId));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "您已订阅该MCP服务");
        }

        // 创建订阅记录
        McpSubscription subscription = McpSubscription.builder()
                .tenantId(tenantId)
                .mcpServiceId(serviceId)
                .mcpCode(service.getMcpCode())
                .subscriberType(SubscriberType.USER)
                .subscriberId(userId)
                .createBy(userId)
                .createTime(LocalDateTime.now())
                .build();
        mcpSubscriptionMapper.insert(subscription);

        // 更新订阅计数
        mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                .eq(McpService::getId, serviceId)
                .setSql("subs_count = IFNULL(subs_count, 0) + 1"));
        log.info("MCP服务订阅: serviceId={}, tenantId={}, userId={}", serviceId, tenantId, userId);

        resourceChangePublisher.publishMcpSubscriptionChanged(tenantId, userId, "SUBSCRIBE");
    }

    /**
     * 取消订阅 MCP 服务。
     *
     * @param tenantId  租户ID
     * @param userId    当前用户ID
     * @param serviceId MCP 服务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void unsubscribeService(Long tenantId, Long userId, Long serviceId) {
        McpService service = requireService(serviceId);

        // 查找并删除订阅记录
        McpSubscription subscription = mcpSubscriptionMapper.selectOne(new LambdaQueryWrapper<McpSubscription>()
                .eq(McpSubscription::getTenantId, tenantId)
                .eq(McpSubscription::getMcpServiceId, serviceId)
                .eq(McpSubscription::getSubscriberType, SubscriberType.USER)
                .eq(McpSubscription::getSubscriberId, userId)
                .last("LIMIT 1"));
        if (subscription != null) {
            mcpSubscriptionMapper.deleteById(subscription.getId());
        }

        // 更新订阅计数
        mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                .eq(McpService::getId, serviceId)
                .setSql("subs_count = GREATEST(IFNULL(subs_count, 0) - 1, 0)"));
        log.info("MCP服务取消订阅: serviceId={}, tenantId={}, userId={}", serviceId, tenantId, userId);

        resourceChangePublisher.publishMcpSubscriptionChanged(tenantId, userId, "UNSUBSCRIBE");
    }

    /**
     * 查询当前用户是否已订阅某 MCP 服务。
     *
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @param serviceId MCP 服务ID
     * @return 是否已订阅
     */
    public boolean isSubscribed(Long tenantId, Long userId, Long serviceId) {
        Long count = mcpSubscriptionMapper.selectCount(new LambdaQueryWrapper<McpSubscription>()
                .eq(McpSubscription::getTenantId, tenantId)
                .eq(McpSubscription::getMcpServiceId, serviceId)
                .eq(McpSubscription::getSubscriberType, SubscriberType.USER)
                .eq(McpSubscription::getSubscriberId, userId));
        return count != null && count > 0;
    }

    /**
     * 分页查询当前用户已订阅的 MCP 服务列表。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @param page     页码
     * @param size     每页条数
     * @return 已订阅服务分页
     */
    public Page<McpServiceVO> pageSubscribedServices(Long tenantId, Long userId, int page, int size) {
        List<Long> subscribedIds = mcpSubscriptionMapper.selectList(new LambdaQueryWrapper<McpSubscription>()
                        .eq(McpSubscription::getTenantId, tenantId)
                        .eq(McpSubscription::getSubscriberType, SubscriberType.USER)
                        .eq(McpSubscription::getSubscriberId, userId))
                .stream()
                .map(McpSubscription::getMcpServiceId)
                .collect(Collectors.toList());

        if (subscribedIds.isEmpty()) {
            Page<McpServiceVO> emptyPage = new Page<>(page, size);
            emptyPage.setTotal(0);
            emptyPage.setRecords(List.of());
            return emptyPage;
        }

        Page<McpService> pageObj = new Page<>(page, size);
        Page<McpService> entityPage = mcpServiceMapper.selectPage(pageObj, new LambdaQueryWrapper<McpService>()
                .in(McpService::getId, subscribedIds)
                .orderByDesc(McpService::getCreateTime));
        Page<McpServiceVO> result = convertPage(entityPage, this::toServiceVO, page, size);

        enrichWithToolPreviews(result.getRecords());

        return result;
    }

    /**
     * 为分页列表中的 MCP 服务并行查询工具摘要。
     *
     * <p>优先从 MCP Server 实时查询；查询失败时降级从 res_tool 表读取缓存。
     *
     * @param services 服务 VO 列表（就地修改 toolPreview / toolCount）
     */
    private void enrichWithToolPreviews(List<McpServiceVO> services) {
        if (services == null || services.isEmpty()) return;

        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();

        for (McpServiceVO vo : services) {
            if (vo.getStatus() != ProviderStatus.ACTIVE) {
                loadToolPreviewFromDb(vo);
                continue;
            }
            if (vo.getEndpoint() == null || vo.getEndpoint().isBlank()) {
                loadToolPreviewFromDb(vo);
                continue;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    List<ToolVO> tools = mcpClientService.queryTools(vo.getEndpoint(), vo.getProtocol());
                    if (tools != null && !tools.isEmpty()) {
                        vo.setToolCount(tools.size());
                        List<String> preview = tools.stream()
                                .limit(5)
                                .map(ToolVO::getToolName)
                                .collect(Collectors.toList());
                        vo.setToolPreview(preview);
                    } else {
                        loadToolPreviewFromDb(vo);
                    }
                } catch (Exception e) {
                    log.debug("enrichWithToolPreviews: 实时查询失败, 降级读DB, serviceId={}, error={}",
                            vo.getId(), e.getMessage());
                    loadToolPreviewFromDb(vo);
                }
            });
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("enrichWithToolPreviews: 部分查询超时或失败");
        }
    }

    /**
     * 从 res_tool 表加载缓存的工具预览作为降级方案。
     */
    private void loadToolPreviewFromDb(McpServiceVO vo) {
        try {
            List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                    .eq(Tool::getMcpServiceId, vo.getId())
                    .eq(Tool::getStatus, CommonStatus.NORMAL)
                    .eq(Tool::getSourceType, ToolSourceType.MCP));
            if (tools != null && !tools.isEmpty()) {
                vo.setToolCount(tools.size());
                List<String> preview = tools.stream()
                        .limit(5)
                        .map(Tool::getToolName)
                        .collect(Collectors.toList());
                vo.setToolPreview(preview);
            }
        } catch (Exception e) {
            log.debug("loadToolPreviewFromDb: DB查询失败, serviceId={}, error={}", vo.getId(), e.getMessage());
        }
    }
}
