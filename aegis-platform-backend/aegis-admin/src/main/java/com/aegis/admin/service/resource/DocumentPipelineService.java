package com.aegis.admin.service.resource;

import com.aegis.dal.mapper.resource.KbDocumentChunkMapper;
import com.aegis.dal.mapper.resource.KbDocumentMapper;
import com.aegis.core.constant.KbConstants;
import com.aegis.dal.mapper.resource.KnowledgeBaseMapper;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.context.TenantContext;
import com.aegis.core.domain.resource.KbDocument;
import com.aegis.core.domain.resource.KbDocumentChunk;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.enums.resource.DocumentStatus;
import com.aegis.core.enums.resource.ProcessStep;
import com.aegis.core.spi.EmbeddingService;
import com.aegis.core.spi.IObjectStorage;
import com.aegis.core.spi.IVectorStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * 知识库文档异步处理流水线。
 *
 * <p>执行文档上传后的完整处理链路：下载 → 安全扫描 → 切片 → 嵌入 → 向量入库。
 * 支持进度追踪（SSE 推送）、切片持久化、智能切片策略自动选择。
 *
 * <h3>处理流程（带进度追踪）</h3>
 * <ol>
 *   <li>下载：从对象存储下载文档流</li>
 *   <li>扫描：检查文件类型白名单 + 敏感词检测</li>
 *   <li>切片：按知识库 chunkStrategy 切分文本（支持智能策略选择）</li>
 *   <li>嵌入：将文本切片转为向量</li>
 *   <li>入库：向量批量写入 Milvus，切片内容持久化到 DB</li>
 *   <li>完成：更新文档状态 + 推送完成事件</li>
 * </ol>
 *
 * <h3>异步设计</h3>
 * <ul>
 *   <li>使用 @Async 异步执行，不阻塞上传回调</li>
 *   <li>异步线程手动设置租户上下文</li>
 *   <li>异常捕获后标记文档 FAILED，不向上抛出</li>
 * </ul>
 *
 * @author wang.zhen  
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPipelineService {

    private final IObjectStorage objectStorage;
    private final IVectorStore vectorStore;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbDocumentMapper kbDocumentMapper;
    private final KbDocumentChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;
    private final DocumentProgressService progressService;

    /**
     * 异步执行文档处理流水线。
     */
    @Async
    public void process(Long tenantId, Long kbId, Long docId) {
        try {
            TenantContextHolder.set(TenantContext.builder().tenantId(tenantId).build());
            doProcess(tenantId, kbId, docId);
        } catch (Exception e) {
            log.error("文档处理流水线失败: tenantId={}, kbId={}, docId={}", tenantId, kbId, docId, e);
            // 先更新文档状态为 FAILED（即使进度表写入失败，文档状态仍然正确）
            markFailed(docId, e.getMessage());
            try {
                progressService.markFailed(docId, kbId, "处理失败: " + e.getMessage());
            } catch (Exception pe) {
                log.warn("进度表写入失败（文档状态已更新）: docId={}, error={}", docId, pe.getMessage());
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void doProcess(Long tenantId, Long kbId, Long docId) {
        KbDocument doc = kbDocumentMapper.selectById(docId);
        if (doc == null) {
            log.warn("文档不存在，跳过处理: docId={}", docId);
            // 文档不在 DB 也尝试标记（可能是租户上下文问题导致查不到），先标状态再写进度
            markFailed(docId, "文档不存在");
            try { progressService.markFailed(docId, kbId, "文档不存在"); } catch (Exception ignored) {}
            return;
        }
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            log.warn("知识库不存在，跳过处理: kbId={}", kbId);
            // 先标状态
            markFailed(docId, "知识库不存在: " + kbId);
            try { progressService.markFailed(docId, kbId, "知识库不存在"); } catch (Exception ignored) {}
            return;
        }

        // 清理旧的向量数据（重新处理时）——必须在删除切片前执行
        // 旧切片的向量 ID 格式为 {docId}_{chunkIndex}，按旧 chunkCount 构造 ID 列表删除
        // 否则重处理后旧向量残留 Milvus，新切片数 < 旧切片数时旧向量污染检索结果
        String collection = KbConstants.VECTOR_COLLECTION_PREFIX + kbId;
        if (doc.getChunkCount() != null && doc.getChunkCount() > 0) {
            deleteOldVectors(tenantId, collection, docId, doc.getChunkCount());
        }

        // 清理旧的切片数据（重新处理时）
        chunkMapper.delete(new LambdaQueryWrapper<KbDocumentChunk>()
                .eq(KbDocumentChunk::getDocId, docId));

        // 初始化进度
        progressService.initProgress(tenantId, kbId, docId);

        // Step 1: 下载
        progressService.startStep(docId, ProcessStep.DOWNLOADING, "下载 " + doc.getFileName());
        String content;
        try (InputStream is = objectStorage.download(tenantId, doc.getOssKey())) {
            content = readText(is, doc.getFileType());
            progressService.completeStep(docId, ProcessStep.DOWNLOADING, "下载完成");
        } catch (Exception e) {
            log.error("文档下载失败: docId={}, error={}", docId, e.getMessage(), e);
            progressService.failStep(docId, ProcessStep.DOWNLOADING, e.getMessage());
            progressService.markFailed(docId, kbId, "下载失败: " + e.getMessage());
            markFailed(docId, "文档下载或解析失败: " + e.getMessage());
            return;
        }

        // Step 2: 扫描
        updateStatus(docId, DocumentStatus.SCANNING, null, null);
        progressService.startStep(docId, ProcessStep.SCANNING, "安全扫描中...");
        String scanResult = scanDocument(doc, content);
        if (scanResult != null && scanResult.contains("\"passed\":false")) {
            progressService.failStep(docId, ProcessStep.SCANNING, "安全扫描未通过");
            progressService.markFailed(docId, kbId, "安全扫描未通过");
            markFailed(docId, "安全扫描未通过: " + scanResult);
            return;
        }
        progressService.completeStep(docId, ProcessStep.SCANNING, "扫描通过");

        // Step 3: 切片
        updateStatus(docId, DocumentStatus.CHUNKING, null, null);
        progressService.startStep(docId, ProcessStep.CHUNKING, "文本切片中...");
        String strategy = resolveChunkStrategy(kb, doc);
        List<String> chunks = chunkText(content, strategy, kb.getChunkSize(), kb.getChunkOverlap());
        if (chunks.isEmpty()) {
            progressService.failStep(docId, ProcessStep.CHUNKING, "切片结果为空");
            progressService.markFailed(docId, kbId, "切片结果为空");
            markFailed(docId, "切片结果为空");
            return;
        }
        progressService.completeStep(docId, ProcessStep.CHUNKING, "切片完成，共 " + chunks.size() + " 段");

        // Step 4: 嵌入
        progressService.startStep(docId, ProcessStep.EMBEDDING, "文本向量化中...");
        int dimension = embeddingService.getDimension();
        boolean vectorStoreAvailable = ensureCollectionSafely(tenantId, collection, dimension);

        float[][] vectors = embeddingService.embedBatch(chunks);
        boolean embeddingAvailable = vectors != null && vectors.length > 0 && vectors[0].length > 0;

        // R-8 诚实状态：向量化失败的三种情形（嵌入服务不可用 / 向量库不可用 / upsert 异常）
        // 一律标记 FAILED + 原因，不再"假成功"（原逻辑会标 CHUNKED+完成，导致"处理完成但检索不到"）。
        // 切片仍落库供关键词检索 + 用户重试时复用。
        boolean vectorOk = false;
        if (!embeddingAvailable) {
            log.warn("嵌入服务不可用，文档将标记 FAILED: docId={}", docId);
            progressService.failStep(docId, ProcessStep.EMBEDDING, "嵌入服务不可用");
        } else {
            progressService.completeStep(docId, ProcessStep.EMBEDDING, "嵌入完成");
            // Step 5: 向量入库
            progressService.startStep(docId, ProcessStep.VECTORING, "向量写入中...");
            List<IVectorStore.VectorRecord> records = buildVectorRecords(docId, doc.getFileName(), kb.getKbName(), chunks, vectors);
            if (!vectorStoreAvailable) {
                log.warn("向量存储不可用，文档将标记 FAILED: docId={}", docId);
                progressService.failStep(docId, ProcessStep.VECTORING, "向量存储不可用");
            } else {
                try {
                    vectorStore.upsert(tenantId, collection, records);
                    progressService.completeStep(docId, ProcessStep.VECTORING, "向量入库完成");
                    vectorOk = true;
                } catch (Exception e) {
                    log.error("向量入库失败，文档将标记 FAILED（可重试）: docId={}, error={}", docId, e.getMessage(), e);
                    progressService.failStep(docId, ProcessStep.VECTORING, e.getMessage());
                }
            }
        }

        // 持久化切片（无论向量化是否成功，切片落库供关键词检索 + 用户重试时复用）
        saveChunks(docId, chunks, strategy, kb);

        if (vectorOk) {
            updateStatus(docId, DocumentStatus.CHUNKED, chunks.size(), scanResult);
            updateKbDocCount(kbId, 1);
            progressService.markCompleted(docId, kbId);
            log.info("文档处理完成: tenantId={}, kbId={}, docId={}, chunks={}",
                    tenantId, kbId, docId, chunks.size());
        } else {
            markFailed(docId, "向量化未完成（嵌入服务或向量库不可用），该文档暂无法被语义检索，请重试");
            progressService.markFailed(docId, kbId, "向量化失败");
            log.warn("文档处理未完成（向量化失败，已诚实标记 FAILED）: tenantId={}, kbId={}, docId={}",
                    tenantId, kbId, docId);
        }
    }

    /**
     * 解析切片策略：知识库配置优先，否则智能选择。
     */
    private String resolveChunkStrategy(KnowledgeBase kb, KbDocument doc) {
        String configured = kb.getChunkStrategy();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        return KbConstants.suggestStrategy(doc.getFileType());
    }

    /**
     * 安全确保向量集合存在。
     */
    private boolean ensureCollectionSafely(Long tenantId, String collection, int dimension) {
        try {
            return vectorStore.ensureCollection(tenantId, collection, dimension);
        } catch (Exception e) {
            log.warn("向量集合创建失败，将跳过向量入库: collection={}, error={}", collection, e.getMessage());
            return false;
        }
    }

    /**
     * 构建向量记录列表。
     */
    private List<IVectorStore.VectorRecord> buildVectorRecords(Long docId, String docName, String kbName, List<String> chunks, float[][] vectors) {
        List<IVectorStore.VectorRecord> records = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            IVectorStore.VectorRecord record = new IVectorStore.VectorRecord();
            record.id = docId + "_" + i;
            record.vector = vectors[i];
            Map<String, Object> metadata = new HashMap<>(5);
            metadata.put("doc_id", docId);
            metadata.put("doc_name", docName);
            metadata.put("kb_name", kbName);
            metadata.put("chunk_index", i);
            metadata.put("content", chunks.get(i));
            record.metadata = metadata;
            records.add(record);
        }
        return records;
    }

    /**
     * 保存切片内容到数据库。
     */
    private void saveChunks(Long docId, List<String> chunks, String strategy, KnowledgeBase kb) {
        List<KbDocumentChunk> chunkEntities = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            KbDocumentChunk entity = KbDocumentChunk.builder()
                    .kbId(kb.getId())
                    .docId(docId)
                    .chunkIndex(i)
                    .content(chunk)
                    .tokenCount(chunk.length() / 4)
                    .charCount(chunk.length())
                    .metadata(buildChunkMetadata(strategy, kb))
                    .build();
            chunkEntities.add(entity);
        }
        for (KbDocumentChunk entity : chunkEntities) {
            chunkMapper.insert(entity);
        }
        log.debug("切片已持久化: docId={}, chunks={}", docId, chunks.size());
    }

    private String buildChunkMetadata(String strategy, KnowledgeBase kb) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"strategy\":\"").append(strategy).append("\"");
        sb.append(",\"chunkSize\":").append(kb.getChunkSize() != null ? kb.getChunkSize() : KbConstants.DEFAULT_CHUNK_SIZE);
        sb.append(",\"chunkOverlap\":").append(kb.getChunkOverlap() != null ? kb.getChunkOverlap() : KbConstants.DEFAULT_CHUNK_OVERLAP);
        sb.append("}");
        return sb.toString();
    }

    // ============ 文本解析 ============

    private String readText(InputStream is, String fileType) throws Exception {
        byte[] bytes = is.readAllBytes();
        if (bytes.length == 0) return "";

        String type = fileType != null ? fileType.toUpperCase() : "";
        return switch (type) {
            case "TXT", "MD", "MARKDOWN", "CSV", "JSON", "XML", "HTML", "HTM", "LRC", "SRT", "VTT" ->
                    new String(bytes, StandardCharsets.UTF_8);
            case "DOCX" -> parseDocx(bytes);
            case "PDF" -> parsePdf(bytes);
            // R-10: 补齐 Office 解析分支，杜绝落 extractPrintableText 生成乱码垃圾向量静默污染知识库
            case "XLSX", "XLS", "PPTX", "PPT", "DOC" -> parseOffice(bytes, type);
            default -> {
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.chars().filter(c -> (c > 31 && c < 127) || c > 160).count() > text.length() * 0.7) {
                    yield text;
                }
                yield extractPrintableText(bytes);
            }
        };
    }

    /**
     * R-10: 统一 Office 文档解析（XLSX/XLS/PPTX/PPT/DOC）。
     *
     * <p>使用 POI ExtractorFactory 自动识别格式并提取文本，
     * 需 poi-ooxml（OOXML）+ poi-scratchpad（老格式）双依赖。
     * 失败时降级 extractPrintableText 并告警，便于运维介入。
     */
    private String parseOffice(byte[] bytes, String type) {
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(bytes)) {
            org.apache.poi.extractor.POITextExtractor extractor =
                    org.apache.poi.extractor.ExtractorFactory.createExtractor(is);
            String text = extractor.getText();
            if (text != null) text = text.trim();
            log.info("Office 文本提取成功: type={}, chars={}", type, text != null ? text.length() : 0);
            return text != null ? text : "";
        } catch (Exception e) {
            log.warn("Office 解析失败({}), 降级可打印字符提取: {}", type, e.getMessage());
            return extractPrintableText(bytes);
        }
    }

    /**
     * 使用 Apache PDFBox 提取 PDF 文本。
     * 支持中文 PDF，自动处理编码问题。
     */
    private String parsePdf(byte[] bytes) {
        try (PDDocument document = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setShouldSeparateByBeads(true);
            String text = stripper.getText(document);
            if (text != null) {
                text = text.trim();
                // PDFBox 可能在无文本层 PDF（纯扫描件）上返回空
                if (text.isEmpty()) {
                    log.warn("PDF 文本提取为空（可能为扫描件，缺乏文本层）");
                    return "";
                }
            }
            log.info("PDF 文本提取成功: {} chars", text != null ? text.length() : 0);
            return text != null ? text : "";
        } catch (Exception e) {
            log.warn("PDFBox 解析失败，降级为可打印字符提取: {}", e.getMessage());
            return extractPrintableText(bytes);
        }
    }

    /**
     * 使用 Apache POI 提取 DOCX 文本。
     * 比手动解析 XML 更健壮，支持表格、页眉页脚等。
     */
    private String parseDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            StringBuilder result = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    // 段落间用空行分隔，保证 PARAGRAPH 切片策略按段切分（题库等一题一段场景）
                    result.append(text).append("\n\n");
                }
            }
            // 提取表格中的文本
            doc.getTables().forEach(table -> {
                table.getRows().forEach(row -> {
                    row.getTableCells().forEach(cell -> {
                        String text = cell.getText();
                        if (text != null && !text.isBlank()) {
                            result.append(text).append(" ");
                        }
                    });
                    result.append("\n");
                });
                // 表格整体作为一段
                result.append("\n");
            });
            String text = result.toString().trim();
            if (text.isEmpty()) {
                log.warn("DOCX 文本提取为空");
            }
            return text;
        } catch (Exception e) {
            log.warn("POI DOCX 解析失败，降级为可打印字符提取: {}", e.getMessage());
            return extractPrintableText(bytes);
        }
    }

    private String extractPrintableText(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 32 && c < 127) || c == '\n' || c == '\r' || c == '\t' || (c >= 0x4E00 && c <= 0x9FFF)) {
                current.append(c);
            } else {
                if (current.length() >= 4) {
                    result.append(current).append(" ");
                }
                current.setLength(0);
            }
        }
        if (current.length() >= 4) {
            result.append(current);
        }
        return result.toString().trim();
    }

    // ============ 安全扫描 ============

    private String scanDocument(KbDocument doc, String content) {
        String fileType = doc.getFileType();
        boolean typePassed = fileType == null || KbConstants.ALLOWED_FILE_TYPES.contains(fileType.toUpperCase());

        List<String> hits = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            String lowerContent = content.toLowerCase();
            for (String word : KbConstants.SENSITIVE_WORDS) {
                String lowerWord = word.toLowerCase();
                // 中文敏感词用子串匹配（中文无词边界概念）
                // 英文敏感词用全词匹配（\b 词边界），避免 "tokenization" 误命中 "token"
                boolean isChinese = word.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
                boolean matched;
                if (isChinese) {
                    matched = lowerContent.contains(lowerWord);
                } else {
                    // \b 在英文两端匹配词边界，防止子串误命中
                    matched = java.util.regex.Pattern.compile(
                            "\\b" + java.util.regex.Pattern.quote(lowerWord) + "\\b",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(lowerContent).find();
                }
                if (matched) {
                    hits.add(word);
                    log.warn("安全扫描命中敏感词: word={}, isChinese={}", word, isChinese);
                }
            }
        }

        boolean passed = typePassed;
        // 敏感词命中仅记录警告，不阻断流水线
        // 知识库为内部文档管理场景，安全管控应通过发布审批/安全等级等机制实现
        if (!hits.isEmpty()) {
            log.warn("文档包含敏感词（仅预警，不阻断切片）: docId={}, hits={}", doc.getId(), hits);
        }
        StringBuilder sb = new StringBuilder("{\"passed\":").append(passed);
        sb.append(",\"fileType\":\"").append(fileType != null ? fileType : "").append("\"");
        sb.append(",\"typePassed\":").append(typePassed);
        if (!hits.isEmpty()) {
            sb.append(",\"sensitiveHits\":").append(hits);
        }
        sb.append("}");
        log.info("安全扫描结果: passed={}, typePassed={}, hits={}", passed, typePassed, hits);
        return sb.toString();
    }

    // ============ 切片 ============

    private List<String> chunkText(String text, String chunkStrategy, Integer chunkSize, Integer chunkOverlap) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int size = chunkSize != null && chunkSize > 0 ? chunkSize : KbConstants.DEFAULT_CHUNK_SIZE;
        int overlap = chunkOverlap != null && chunkOverlap >= 0 ? chunkOverlap : KbConstants.DEFAULT_CHUNK_OVERLAP;
        String strategy = chunkStrategy != null ? chunkStrategy.toUpperCase() : KbConstants.STRATEGY_FIXED;

        List<String> chunks = switch (strategy) {
            case KbConstants.STRATEGY_SENTENCE -> splitBySentence(text, size);
            case KbConstants.STRATEGY_PARAGRAPH -> splitByParagraph(text);
            case KbConstants.STRATEGY_MARKDOWN -> splitByMarkdown(text);
            case KbConstants.STRATEGY_FIXED -> new ArrayList<>();
            default -> new ArrayList<>();
        };

        if (chunks.isEmpty()) {
            chunks = splitFixed(text, size, overlap);
        } else {
            List<String> refined = new ArrayList<>();
            for (String chunk : chunks) {
                if (chunk.length() > size) {
                    refined.addAll(splitFixed(chunk, size, overlap));
                } else {
                    refined.add(chunk);
                }
            }
            chunks = refined;
        }

        chunks.removeIf(String::isEmpty);
        return chunks;
    }

    private List<String> splitFixed(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        int step = size - overlap;
        if (step <= 0) step = size;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            result.add(text.substring(start, end).trim());
            if (end >= text.length()) break;
            start += step;
        }
        return result;
    }

    private List<String> splitBySentence(String text, int size) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '.' || c == '！' || c == '!' || c == '？' || c == '?') {
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private List<String> splitByParagraph(String text) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        for (String p : paragraphs) {
            if (!p.trim().isEmpty()) {
                result.add(p.trim());
            }
        }
        return result;
    }

    private List<String> splitByMarkdown(String text) {
        List<String> result = new ArrayList<>();
        String[] lines = text.split("\\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("## ") || line.startsWith("# ")) {
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current.setLength(0);
                }
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    // ============ 状态更新 ============

    private void updateStatus(Long docId, DocumentStatus status, Integer chunkCount, String scanResult) {
        LambdaUpdateWrapper<KbDocument> wrapper = new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, docId)
                .set(KbDocument::getStatus, status);
        if (chunkCount != null) {
            wrapper.set(KbDocument::getChunkCount, chunkCount);
        }
        if (scanResult != null) {
            wrapper.set(KbDocument::getScanResult, scanResult);
        }
        kbDocumentMapper.update(null, wrapper);
    }

    private void markFailed(Long docId, String reason) {
        try {
            Map<String, Object> scanResultMap = new HashMap<>();
            scanResultMap.put("passed", false);
            scanResultMap.put("reason", reason != null ? reason : "unknown");
            String scanResult = new ObjectMapper().writeValueAsString(scanResultMap);
            kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, docId)
                    .set(KbDocument::getStatus, DocumentStatus.FAILED)
                    .set(KbDocument::getScanResult, scanResult));
        } catch (JsonProcessingException e) {
            log.error("标记文档失败状态异常: docId={}", docId, e);
        }
    }

    /**
     * 删除文档旧向量数据（重新处理前清理）。
     *
     * <p>向量 ID 格式为 {docId}_{chunkIndex}，按旧 chunkCount 构造 ID 列表批量删除。
     * 删除失败仅记录警告，不阻断流水线——残留向量最多导致少量旧内容被检索到，
     * 严重程度远低于阻断整个重处理流程。
     *
     * @param tenantId    租户ID
     * @param collection  向量集合名
     * @param docId       文档ID
     * @param chunkCount  旧切片数量
     */
    private void deleteOldVectors(Long tenantId, String collection, Long docId, int chunkCount) {
        List<String> ids = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            ids.add(docId + "_" + i);
        }
        try {
            vectorStore.delete(tenantId, collection, ids);
            log.info("C2: 旧向量清理完成: docId={}, oldChunks={}, collection={}", docId, chunkCount, collection);
        } catch (Exception e) {
            log.warn("C2: 旧向量清理失败（不阻断重处理）: docId={}, error={}", docId, e.getMessage());
        }
    }

    private void updateKbDocCount(Long kbId, int delta) {
        try {
            KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
            if (kb != null) {
                int current = kb.getDocCount() != null ? kb.getDocCount() : 0;
                knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, kbId)
                        .set(KnowledgeBase::getDocCount, current + delta));
            }
        } catch (Exception e) {
            log.warn("更新知识库文档计数失败: kbId={}", kbId, e);
        }
    }
}
