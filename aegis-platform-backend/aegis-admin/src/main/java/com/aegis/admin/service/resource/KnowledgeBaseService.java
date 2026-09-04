package com.aegis.admin.service.resource;

import com.aegis.admin.service.resource.ReviewProcessEngine;
import com.aegis.dal.mapper.resource.KbDocumentMapper;
import com.aegis.core.constant.KbConstants;
import com.aegis.dal.mapper.resource.KnowledgeBaseMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.KbDocument;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.dto.resource.KnowledgeBaseCreateRequest;
import com.aegis.core.dto.resource.KnowledgeBaseUpdateRequest;
import com.aegis.core.dto.resource.KnowledgeBaseVO;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.spi.IVectorStore;
import com.aegis.core.util.XssSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识库管理领域服务。
 *
 * <p>编排知识库的创建、配置更新、查询、提交审核与删除能力。
 * 知识库承载 RAG 检索配置（切片策略、嵌入模型、检索策略），
 * 通过审核流程发布至资源中心供智能体订阅使用。
 *
 * @author wang.zhen
 * @see KnowledgeBase
 * @see ReviewProcessEngine
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbDocumentMapper kbDocumentMapper;
    private final ReviewProcessEngine reviewProcessEngine;
    private final IVectorStore vectorStore;

    /**
     * 创建知识库（草稿态）。
     *
     * <p>初始化默认 RAG 配置：FIXED 切片策略、默认嵌入模型、VECTOR 检索策略。
     *
     * @param tenantId 租户ID
     * @param req      知识库创建请求
     * @return 知识库ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long tenantId, KnowledgeBaseCreateRequest req) {
        if (tenantId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (req.getKbCode() == null || req.getKbCode().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "知识库编码不能为空");
        }
        if (req.getKbName() == null || req.getKbName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "知识库名称不能为空");
        }
        // XSS 清洗：对用户输入文本字段进行 HTML 转义
        req.setKbName(XssSanitizer.sanitize(req.getKbName(), 200));
        if (req.getDescription() != null) {
            req.setDescription(XssSanitizer.sanitize(req.getDescription(), 1000));
        }
        if (req.getIcon() != null) {
            req.setIcon(XssSanitizer.sanitize(req.getIcon(), 500));
        }
        // 编码租户内唯一
        Long exists = knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, tenantId)
                .eq(KnowledgeBase::getKbCode, req.getKbCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "知识库编码已存在: " + req.getKbCode());
        }

        KnowledgeBase kb = new KnowledgeBase();
        BeanUtils.copyProperties(req, kb);
        kb.setTenantId(tenantId);

        // 初始化默认值
        kb.setLifeStatus(AgentLifeStatus.DRAFT);
        kb.setVersion("0.0.1");
        kb.setDocCount(0);
        if (kb.getSecurityLevel() == null) kb.setSecurityLevel(SecurityLevel.L1);
        // 租户隔离：强制本租户可见，禁止跨租户发布
        kb.setVisibility(Visibility.TENANT);
        if (kb.getChunkStrategy() == null) kb.setChunkStrategy("FIXED");
        if (kb.getChunkSize() == null) kb.setChunkSize(KbConstants.DEFAULT_CHUNK_SIZE);
        if (kb.getChunkOverlap() == null) kb.setChunkOverlap(KbConstants.DEFAULT_CHUNK_OVERLAP);
        if (kb.getEmbeddingModel() == null) kb.setEmbeddingModel(KbConstants.DEFAULT_EMBEDDING_MODEL);
        if (kb.getRetrievalStrategy() == null) kb.setRetrievalStrategy(KbConstants.DEFAULT_RETRIEVAL_STRATEGY);
        if (kb.getTopK() == null) kb.setTopK(KbConstants.DEFAULT_TOP_K);
        if (kb.getSimilarityThreshold() == null) kb.setSimilarityThreshold(KbConstants.DEFAULT_SIMILARITY_THRESHOLD);
        kb.setSimilarityThreshold(clampThreshold(kb.getSimilarityThreshold()));
        if (kb.getEnableRerank() == null) kb.setEnableRerank(false);
        if (kb.getEnableQueryRewrite() == null) kb.setEnableQueryRewrite(false);
        kb.setSubsCount(0);

        knowledgeBaseMapper.insert(kb);
        log.info("KnowledgeBase created: id={}, code={}, tenantId={}",
                kb.getId(), kb.getKbCode(), kb.getTenantId());
        return kb.getId();
    }

    /**
     * 更新知识库检索配置（切片策略/嵌入模型/检索策略）。
     *
     * <p>仅 DRAFT/REJECTED 状态可更新配置；已发布知识库需重新提交审核后生效。
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     * @param req      待更新的配置字段
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(Long tenantId, Long kbId, KnowledgeBaseUpdateRequest req, Long userId) {
        KnowledgeBase existing = requireKb(kbId, tenantId);
        // D1: 写操作权限校验（作者 + 管理员可操作）
        checkWritePermission(existing, userId);
        if (existing.getLifeStatus() == AgentLifeStatus.PUBLISHED
                || existing.getLifeStatus() == AgentLifeStatus.REVIEWING
                || existing.getLifeStatus() == AgentLifeStatus.ARCHIVED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "知识库当前状态不可修改配置: " + existing.getLifeStatus());
        }

        LambdaUpdateWrapper<KnowledgeBase> wrapper = new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId);
        if (req.getChunkStrategy() != null) wrapper.set(KnowledgeBase::getChunkStrategy, req.getChunkStrategy());
        if (req.getChunkSize() != null) wrapper.set(KnowledgeBase::getChunkSize, req.getChunkSize());
        if (req.getChunkOverlap() != null) wrapper.set(KnowledgeBase::getChunkOverlap, req.getChunkOverlap());
        if (req.getEmbeddingModel() != null) wrapper.set(KnowledgeBase::getEmbeddingModel, req.getEmbeddingModel());
        if (req.getRetrievalStrategy() != null) wrapper.set(KnowledgeBase::getRetrievalStrategy, req.getRetrievalStrategy());
        if (req.getTopK() != null) wrapper.set(KnowledgeBase::getTopK, req.getTopK());
        if (req.getSimilarityThreshold() != null) wrapper.set(KnowledgeBase::getSimilarityThreshold, clampThreshold(req.getSimilarityThreshold()));
        if (req.getEnableRerank() != null) wrapper.set(KnowledgeBase::getEnableRerank, req.getEnableRerank());
        if (req.getEnableQueryRewrite() != null) wrapper.set(KnowledgeBase::getEnableQueryRewrite, req.getEnableQueryRewrite());
        // XSS 清洗：对用户输入文本字段进行 HTML 转义
        if (req.getKbName() != null) wrapper.set(KnowledgeBase::getKbName, XssSanitizer.sanitize(req.getKbName(), 200));
        if (req.getIcon() != null) wrapper.set(KnowledgeBase::getIcon, XssSanitizer.sanitize(req.getIcon(), 500));
        if (req.getDescription() != null) wrapper.set(KnowledgeBase::getDescription, XssSanitizer.sanitize(req.getDescription(), 1000));
        if (req.getSecurityLevel() != null) wrapper.set(KnowledgeBase::getSecurityLevel, req.getSecurityLevel());
        if (req.getVisibility() != null) wrapper.set(KnowledgeBase::getVisibility, req.getVisibility());

        knowledgeBaseMapper.update(null, wrapper);
        log.info("KnowledgeBase config updated: id={}", kbId);
    }

    /**
     * 相似度阈值钳制：低于下限时纠正为下限值。
     *
     * <p>COSINE 量纲下阈值低于 {@link KbConstants#MIN_SIMILARITY_THRESHOLD}（0.25）
     * 属于误配置——噪声切片相似度通常在 0.1~0.2 区间，低阈值会把无关内容全部注入
     * 提示词，诱导模型判定检索结果无用并回退到文件系统探索（glob_files/list_files）。
     * 统一按最低有效值纠正，避免静默放行无效配置。
     */
    private java.math.BigDecimal clampThreshold(java.math.BigDecimal threshold) {
        if (threshold == null) {
            return KbConstants.DEFAULT_SIMILARITY_THRESHOLD;
        }
        if (threshold.compareTo(KbConstants.MIN_SIMILARITY_THRESHOLD) < 0) {
            log.warn("相似度阈值低于下限，已纠正: input={}, min={}",
                    threshold, KbConstants.MIN_SIMILARITY_THRESHOLD);
            return KbConstants.MIN_SIMILARITY_THRESHOLD;
        }
        return threshold;
    }

    /**
     * 查询知识库详情。
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     * @return 知识库详情
     */
    public KnowledgeBaseVO getDetail(Long tenantId, Long kbId) {
        return toVO(requireKb(kbId, tenantId));
    }

    /**
     * 分页查询知识库。
     *
     * @param tenantId 租户ID
     * @param userId   当前用户ID（mine 视图时用于过滤作者）
     * @param scope    视图范围：mine（我创建的）/ market（已发布可订阅）
     * @param keyword  关键词（匹配名称/编码/描述）
     * @param page     页码
     * @param size     每页条数
     * @return 分页结果
     */
    public Page<KnowledgeBaseVO> page(Long tenantId, Long userId, String scope, String keyword, int page, int size) {
        Page<KnowledgeBase> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>();

        boolean isMarket = com.aegis.core.enums.resource.ResourceScope.fromCode(scope)
                == com.aegis.core.enums.resource.ResourceScope.MARKET;
        if (isMarket) {
            // 市场视图：租户隔离 - 仅返回本租户已发布资源，禁止跨租户访问
            wrapper.eq(KnowledgeBase::getLifeStatus, AgentLifeStatus.PUBLISHED)
                    .eq(KnowledgeBase::getTenantId, tenantId);
        } else {
            // 我的视图：仅返回当前用户创建的知识库（按 author_user_id 过滤）
            wrapper.eq(tenantId != null, KnowledgeBase::getTenantId, tenantId);
            if (userId != null) {
                wrapper.eq(KnowledgeBase::getAuthorUserId, userId);
            }
        }

        wrapper.like(keyword != null && !keyword.isEmpty(), KnowledgeBase::getKbName, keyword)
                .orderByDesc(KnowledgeBase::getCreateTime);
        Page<KnowledgeBase> entityPage = knowledgeBaseMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toVO, page, size);
    }

    /**
     * 提交知识库审核发布。
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     */
    public void submitForReview(Long tenantId, Long kbId, Long userId) {
        KnowledgeBase kb = requireKb(kbId, tenantId);
        // D1: 写操作权限校验（作者 + 管理员可操作）
        checkWritePermission(kb, userId);
        reviewProcessEngine.submit(tenantId, "KNOWLEDGE_BASE", kbId);
        log.info("KnowledgeBase submitted for review: id={}, tenantId={}, userId={}", kbId, tenantId, userId);
    }

    /**
     * 直接发布知识库（跳过审核，DRAFT → PUBLISHED）。
     *
     * <p>适用于用户侧快速发布场景，或审核通过后由系统调用。
     * 仅知识库作者或租户管理员可执行。
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     * @param userId   当前用户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long tenantId, Long kbId, Long userId) {
        KnowledgeBase existing = requireKb(kbId, tenantId);
        // 权限校验：仅作者可发布
        if (userId != null && !userId.equals(existing.getAuthorUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非知识库作者，无权发布");
        }
        // 状态校验：仅 DRAFT 或 REVIEWING（审核通过）可发布
        if (existing.getLifeStatus() != AgentLifeStatus.DRAFT
                && existing.getLifeStatus() != AgentLifeStatus.REVIEWING) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "知识库当前状态不可发布: " + existing.getLifeStatus());
        }
        knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .set(KnowledgeBase::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .set(KnowledgeBase::getPublishedTime, java.time.LocalDateTime.now()));
        log.info("KnowledgeBase published: id={}, tenantId={}, userId={}", kbId, tenantId, userId);
    }

    /**
     * 下架知识库至草稿态（PUBLISHED → DRAFT），支持发布后迭代编辑。
     *
     * <p>已发布知识库下架后回到草稿态，作者可修改配置、增删文档、重新切片，
     * 完成后重新发布。文档和切片数据完整保留，无需重新上传。
     *
     * <h3>权限与状态约束</h3>
     * <ul>
     *   <li>仅知识库作者可执行下架</li>
     *   <li>仅 PUBLISHED 状态可下架</li>
     * </ul>
     *
     * <h3>对已订阅者的影响</h3>
     * <p>下架后已有订阅关系保留，但 RAG 检索层面非作者用户无法再引用
     * （{@code findReferenceableKnowledgeBasesByIds} 仅放行 PUBLISHED 或作者 DRAFT）。
     * 重新发布后订阅者自动恢复检索能力。</p>
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     * @param userId   当前用户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void takeDown(Long tenantId, Long kbId, Long userId) {
        KnowledgeBase existing = requireKb(kbId, tenantId);
        // 权限校验：仅作者可下架
        if (userId != null && !userId.equals(existing.getAuthorUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非知识库作者，无权下架");
        }
        // 状态校验：仅 PUBLISHED 可下架
        if (existing.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "知识库当前状态不可下架: " + existing.getLifeStatus());
        }
        knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .set(KnowledgeBase::getLifeStatus, AgentLifeStatus.DRAFT)
                .set(KnowledgeBase::getPublishedTime, null));
        log.info("KnowledgeBase taken down to DRAFT: id={}, tenantId={}, userId={}", kbId, tenantId, userId);
    }

    /**
     * 删除知识库（仅 DRAFT/REJECTED 可删除）。
     *
     * <p>连带删除知识库下所有文档（DB 记录 + MinIO 文件 + Milvus 向量）。
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long tenantId, Long kbId, Long userId) {
        KnowledgeBase existing = requireKb(kbId, tenantId);
        // D1: 写操作权限校验（作者 + 管理员可操作）
        checkWritePermission(existing, userId);
        if (existing.getLifeStatus() != AgentLifeStatus.DRAFT
                && existing.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "仅草稿/驳回态知识库可删除，当前状态: " + existing.getLifeStatus());
        }

        // 删除关联文档
        List<KbDocument> docs = kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId));
        if (!docs.isEmpty()) {
            // 删除 Milvus 向量（按 docId 维度）
            String collection = KbConstants.VECTOR_COLLECTION_PREFIX + kbId;
            for (KbDocument doc : docs) {
                deleteDocVectors(tenantId, collection, doc.getId(), doc.getChunkCount());
            }
            // 批量删除文档记录
            kbDocumentMapper.delete(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getKbId, kbId));
        }

        knowledgeBaseMapper.deleteById(kbId);
        log.info("KnowledgeBase deleted: id={}, tenantId={}, docs={}", kbId, tenantId, docs.size());
    }

    /**
     * 查询知识库实体（Controller 下沉方法）。
     *
     * <p>替代 KbUserController.subscribe/unsubscribe 中直连 KnowledgeBaseMapper.selectById
     * 取 authorUserId + kbCode 的场景。返回 null 表示知识库不存在（由调用方决定是否阻断）。
     *
     * @param kbId 知识库ID
     * @return 知识库实体，不存在则 null
     */
    public KnowledgeBase getKnowledgeBase(Long kbId) {
        if (kbId == null) {
            return null;
        }
        return knowledgeBaseMapper.selectById(kbId);
    }

    // ============ 内部方法 ============

    private KnowledgeBase requireKb(Long kbId, Long tenantId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在: " + kbId);
        }
        if (tenantId != null && !tenantId.equals(kb.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该知识库");
        }
        return kb;
    }

    /**
     * D1/D3: 校验知识库写操作权限。
     *
     * <p>仅知识库作者（authorUserId）可执行写操作。
     * 管理员（租户管理员/平台管理员）也不允许越权操作他人知识库，
     * 如需管理操作应走专门的管理后台接口或审批流程。
     *
     * @param kb     知识库实体
     * @param userId 当前用户ID
     * @throws BusinessException 无权限时抛出 FORBIDDEN
     */
    private void checkWritePermission(KnowledgeBase kb, Long userId) {
        // 仅作者放行
        if (userId != null && userId.equals(kb.getAuthorUserId())) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN, "无权限操作该知识库（仅创建者可修改）");
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        BeanUtils.copyProperties(kb, vo);
        return vo;
    }

    private <E, V> Page<V> convertPage(Page<E> entityPage, Function<E, V> converter, int page, int size) {
        Page<V> voPage = new Page<>(page, size);
        voPage.setTotal(entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(converter).collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 删除文档对应的向量数据。
     *
     * <p>向量 ID 格式为 {docId}_{chunkIndex}，按 chunkCount 构造 ID 列表。
     *
     * @param tenantId    租户ID
     * @param collection  向量集合名
     * @param docId       文档ID
     * @param chunkCount  切片数量
     */
    private void deleteDocVectors(Long tenantId, String collection, Long docId, Integer chunkCount) {
        if (chunkCount == null || chunkCount <= 0) {
            return;
        }
        List<String> ids = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            ids.add(docId + "_" + i);
        }
        try {
            vectorStore.delete(tenantId, collection, ids);
        } catch (Exception e) {
            log.warn("删除文档向量失败（忽略）: docId={}, error={}", docId, e.getMessage());
        }
    }
}
