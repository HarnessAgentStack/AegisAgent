package com.aegis.runtime.integration.tool;

import com.aegis.core.dto.agent.AttachmentRef;
import com.aegis.core.domain.session.AegisArtifact;
import com.aegis.runtime.service.artifact.AegisArtifactService;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.document.FileStorageService;
import com.aegis.runtime.integration.agent.ToolResultCache;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * generate_file 工具（AgentScope 2.0 ToolBase 子类模式）。
 *
 * <p>从 {@link AegisBuiltinTools} 的 @Tool 注解模式迁移为 ToolBase 子类模式，
 * 解决以下问题：
 * <ul>
 *   <li><b>tenantId 丢失</b>：@Tool 注解方法无法获取 RuntimeContext，导致
 *       TenantContextHolder 在线程切换后为 null。ToolBase 的 callAsync
 *       通过 {@link ToolCallParam#getRuntimeContext()} 直接获取租户上下文。</li>
 *   <li><b>ToolResultCache 未填充</b>：@Tool 方法无法获取 toolCallId，
 *       导致 tool_result SSE 事件只含 "SUCCESS" 而无实际结果。ToolBase
 *       的 callAsync 可通过 {@link ToolCallParam#getToolUseBlock()} 获取
 *       toolCallId 并填充缓存。</li>
 * </ul>
 *
 * <h3>功能</h3>
 * <p>生成 .docx/.md/.txt 文件并返回下载链接。当 contentType 为 .docx 时，
 * 使用 Apache POI 生成真正的 Word 文档（含样式）。
 *
 * @author wang.zhen
 * @see AegisBuiltinTools
 * @see FileStorageService
 * @see ToolResultCache
 */
@Slf4j
@Component
public class AegisGenerateFileTool extends ToolBase {

    private final FileStorageService fileStorageService;
    private final ToolResultCache toolResultCache;
    /** 会话产物服务：生成文件后写入 sess_artifact（修复原 0 行 bug） */
    private final AegisArtifactService artifactService;

    /** 下载 URL 前缀 */
    private static final String DOWNLOAD_URL_PREFIX = "/api/runtime/task/download/";

    /** generate_file 的 inputSchema（JSON Schema） */
    private static final Map<String, Object> INPUT_SCHEMA = JSON.parseObject(
            "{\"type\":\"object\",\"properties\":{"
                    + "\"filename\":{\"type\":\"string\",\"description\":\"文件名（含扩展名，如 report.docx）\"},"
                    + "\"content\":{\"type\":\"string\",\"description\":\"文件内容（支持 Markdown 语法）\"},"
                    + "\"contentType\":{\"type\":\"string\",\"description\":\"MIME 类型（可选，根据扩展名推断）\"}"
                    + "},\"required\":[\"filename\",\"content\"],\"additionalProperties\":false}");

    /**
     * 构造函数：注入 Spring Bean 并描述工具元数据。
     *
     * @param fileStorageService 文件存储服务
     * @param toolResultCache    工具结果缓存（用于填充 tool_result SSE 事件）
     */
    public AegisGenerateFileTool(FileStorageService fileStorageService,
                                  ToolResultCache toolResultCache,
                                  AegisArtifactService artifactService) {
        super(ToolBase.builder()
                .name("generate_file")
                .description("【生成文件 - 文档产出】\n"
                        + "触发场景: 用户要求生成报告/文档/总结等可下载文件时。\n"
                        + "调用规则:\n"
                        + "- filename 必须含扩展名（.docx/.md/.txt/.json/.csv/.html）。\n"
                        + "- content 支持 Markdown 语法，docx 时会解析 #/##/### 标题、- 列表、**粗体**。\n"
                        + "- contentType 可选，留空时根据文件名扩展名自动推断。\n"
                        + "- 工具返回的 JSON 中包含 downloadUrl 字段，请在回复中直接使用该 URL 值构建下载链接。\n"
                        + "- 重要：downloadUrl 必须逐字符原样输出，禁止添加任何协议前缀（如 sandbox:/ http:/ file: 等），否则前端清洗将导致链接失效。\n"
                        + "返回: {fileId, filename, downloadUrl, size}。")
                .inputSchema(INPUT_SCHEMA));
        this.fileStorageService = fileStorageService;
        this.toolResultCache = toolResultCache;
        this.artifactService = artifactService;
    }

    /**
     * 覆盖权限检查：generate_file工具为低风险工具（用户主动请求的文档产出），工具自检返回 ALLOW。
     *
     * <p>注：AS PermissionEngine 评估序为 deny → ask → 工具自检 → allow，
     * ask/deny 规则先于工具自检评估——此处 ALLOW 不"跳过"审批，仅表示工具自身不触发 ASK。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(PermissionDecision.builder()
                .behavior(PermissionBehavior.ALLOW)
                .message("Generate file tool allowed by Aegis framework")
                .decisionReason("generate_file tool - low risk, user-requested document generation")
                .build());
    }

    /**
     * 异步执行文件生成。
     *
     * <p>从 {@link ToolCallParam} 获取：
     * <ul>
     *   <li>工具入参（filename, content, contentType）</li>
     *   <li>toolCallId（用于填充 ToolResultCache）</li>
     *   <li>RuntimeContext → AegisTaskContext → tenantId（解决线程切换丢失问题）</li>
     * </ul>
     *
     * @param param 工具调用参数
     * @return 文件生成结果块
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String toolCallId = extractToolCallId(param);

        // 从 RuntimeContext 获取 tenantId（解决 @Tool 模式下 TenantContextHolder 线程切换丢失问题）
        Long tenantId = resolveTenantId(param);
        Long userId = resolveUserId(param);
        Long agentId = resolveAgentId(param);
        String sessionId = resolveSessionId(param);

        String filename = input != null ? getString(input, "filename") : null;
        String content = input != null ? getString(input, "content") : null;
        String contentType = input != null ? getString(input, "contentType") : null;

        // 参数校验
        if (filename == null || filename.isEmpty()) {
            String errorResult = "{\"error\": \"Parameter 'filename' is required\"}";
            toolResultCache.put(toolCallId, errorResult);
            return Mono.just(errorResult(toolCallId, "Parameter 'filename' is required"));
        }
        if (content == null) {
            content = "";
        }
        if (contentType == null || contentType.isEmpty()) {
            contentType = inferContentType(filename);
        }

        final String fn = filename;
        final String ct = contentType;
        final String body = content;
        final Long tid = tenantId;
        final Long uid = userId;
        final Long aid = agentId;
        final String sid = sessionId;

        return Mono.fromCallable(() -> {
            byte[] bytes;
            if (isDocx(ct, fn)) {
                bytes = generateDocx(body);
            } else {
                bytes = body.getBytes(StandardCharsets.UTF_8);
            }

            // userId 设为 null（系统生成的文件归属为租户级，同租户所有用户可下载）
            AttachmentRef ref = fileStorageService.store(bytes, fn, ct, tid, null);

            Map<String, Object> result = new HashMap<>(4);
            result.put("fileId", ref.getFileId());
            result.put("filename", fn);
            // 下载 URL 附加 tenantId query param，浏览器直访问也能通过 filter 校验
            String downloadUrl = DOWNLOAD_URL_PREFIX + ref.getFileId();
            if (tid != null) {
                downloadUrl += "?X-Tenant-Id=" + tid;
            }
            result.put("downloadUrl", downloadUrl);
            result.put("size", bytes.length);
            String json = JSON.toJSONString(result);

            // 填充 ToolResultCache，使 tool_result SSE 事件携带实际结果（含 downloadUrl）
            toolResultCache.put(toolCallId, json);
            // 写入 sess_artifact 会话产物记录（修复原 0 行 bug：产物只在 att_file_meta/MinIO 落库，
            // sess_artifact 从未写入，导致会话产物面板与 artifact_ids 引用无数据）
            persistArtifact(ref, fn, ct, tid, uid, aid, sid, bytes.length);
            log.info("generate_file 完成: fileId={}, filename={}, tenantId={}, toolCallId={}",
                    ref.getFileId(), fn, tid, toolCallId);
            return successResult(toolCallId, json);
        }).onErrorResume(e -> {
            log.error("generate_file 执行失败: filename={}", fn, e);
            String errorJson = "{\"error\": \"File generation failed: " +
                    (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "unknown") + "\"}";
            toolResultCache.put(toolCallId, errorJson);
            return Mono.just(errorResult(toolCallId,
                    "File generation failed. Please inform the user."));
        });
    }

    // ============ 租户上下文解析 ============

    /**
     * 从 ToolCallParam 的 RuntimeContext 解析 tenantId。
     *
     * <p>优先从 RuntimeContext 获取 AegisTaskContext（含 tenantId），
     * 解决 @Tool 注解模式下 TenantContextHolder 在线程切换后为 null 的问题。
     *
     * @param param 工具调用参数
     * @return 租户ID，未获取到时返回 null
     */
    private Long resolveTenantId(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) {
                log.debug("generate_file: RuntimeContext 为空，tenantId 将为 null");
                return null;
            }
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            if (taskCtx == null) {
                log.debug("generate_file: AegisTaskContext 未注入 RuntimeContext");
                return null;
            }
            return taskCtx.getTenantId();
        } catch (Exception e) {
            log.warn("generate_file: resolve tenantId failed: {}", e.getMessage());
            return null;
        }
    }

    /** 从 RuntimeContext 解析 userId（供 sess_artifact 归属） */
    private Long resolveUserId(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) return null;
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            return taskCtx == null ? null : taskCtx.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 RuntimeContext 解析 agentId（供 sess_artifact 归属） */
    private Long resolveAgentId(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) return null;
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            return taskCtx == null ? null : taskCtx.getAgentId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 RuntimeContext 解析 sessionId（供 sess_artifact 归属） */
    private String resolveSessionId(ToolCallParam param) {
        try {
            RuntimeContext rc = param.getRuntimeContext();
            if (rc == null) return null;
            AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
            return taskCtx == null ? null : taskCtx.getSessionId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 持久化会话产物到 sess_artifact（修复原 0 行 bug）。
     *
     * <p>artifactId 复用 MinIO fileId（全局唯一），type 从文件名推断，storageRef 用 MinIO objectKey。
     * 写入失败不阻塞工具返回（fire-and-forget，仅 log），与 att_file_meta/MinIO 落库解耦。
     */
    private void persistArtifact(AttachmentRef ref, String filename, String contentType,
                                 Long tenantId, Long userId, Long agentId,
                                 String sessionId, long size) {
        try {
            if (ref == null || tenantId == null || sessionId == null || agentId == null) {
                log.debug("persistArtifact skip (missing context): fileId={}, tid={}, sid={}, aid={}",
                        ref != null ? ref.getFileId() : null, tenantId, sessionId, agentId);
                return;
            }
            String type = inferArtifactType(filename);
            AegisArtifact artifact = AegisArtifact.builder()
                    .artifactId(ref.getFileId())
                    .sessionId(sessionId)
                    .agentId(agentId)
                    .tenantId(tenantId)
                    .userId(userId)
                    .name(filename)
                    .type(type)
                    .storageRef(DOWNLOAD_URL_PREFIX + ref.getFileId())
                    .size(size)
                    .version(1)
                    .archived(false)
                    .createdAt(java.time.LocalDateTime.now())
                    .build();
            artifactService.createArtifact(artifact);
        } catch (Exception e) {
            log.warn("persistArtifact failed (not blocking tool): fileId={}, filename={}",
                    ref != null ? ref.getFileId() : null, filename, e);
        }
    }

    /** 从文件名推断产物类型（与 sess_artifact.type 对齐） */
    private String inferArtifactType(String filename) {
        if (filename == null) return "other";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".docx") || lower.endsWith(".pdf") || lower.endsWith(".txt")
                || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".html")) {
            return "doc";
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "excel";
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) return "ppt";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp")) return "image";
        if (lower.endsWith(".py") || lower.endsWith(".java") || lower.endsWith(".sql")
                || lower.endsWith(".js") || lower.endsWith(".json")) return "code";
        return "other";
    }

    // ============ 辅助方法 ============

    /**
     * 从 ToolCallParam 中提取工具调用 ID。
     */
    private String extractToolCallId(ToolCallParam param) {
        if (param == null || param.getToolUseBlock() == null) {
            return null;
        }
        return param.getToolUseBlock().getId();
    }

    /**
     * 从 Map 中安全提取 String 值。
     */
    private String getString(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * 构造成功结果块。
     */
    private ToolResultBlock successResult(String toolCallId, String text) {
        return new ToolResultBlock(
                toolCallId,
                "generate_file",
                List.of(TextBlock.builder().text(text != null ? text : "").build()),
                Map.of(),
                ToolResultState.SUCCESS);
    }

    /**
     * 构造错误结果块。
     */
    private ToolResultBlock errorResult(String toolCallId, String errorMessage) {
        return new ToolResultBlock(
                toolCallId,
                "generate_file",
                List.of(TextBlock.builder().text("Error: " + errorMessage).build()),
                Map.of(),
                ToolResultState.ERROR);
    }

    // ============ 文件生成辅助（从 AegisBuiltinTools 迁移） ============

    /**
     * 使用 Apache POI 生成真正的 .docx 文件。
     */
    private byte[] generateDocx(String content) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            String[] lines = content.split("\n");
            for (String line : lines) {
                XWPFParagraph para = doc.createParagraph();
                if (line.startsWith("# ")) {
                    para.setStyle("Heading1");
                    appendRun(para, line.substring(2), true, 16);
                } else if (line.startsWith("## ")) {
                    para.setStyle("Heading2");
                    appendRun(para, line.substring(3), true, 14);
                } else if (line.startsWith("### ")) {
                    para.setStyle("Heading3");
                    appendRun(para, line.substring(4), true, 13);
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    appendRun(para, "• " + line.substring(2), false, 11);
                } else if (line.matches("^\\d+\\.\\s.*")) {
                    appendRun(para, line, false, 11);
                } else if (line.trim().isEmpty()) {
                    appendRun(para, "", false, 11);
                } else {
                    appendRun(para, line, false, 11);
                }
            }

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("生成 docx 失败，回退为纯文本: {}", e.getMessage());
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 为段落添加一个 Run，支持粗体和指定字号。
     */
    private void appendRun(XWPFParagraph para, String text, boolean bold, int fontSize) {
        if (text == null || text.isEmpty()) {
            para.createRun();
            return;
        }
        String[] parts = text.split("\\*\\*");
        for (int i = 0; i < parts.length; i++) {
            XWPFRun run = para.createRun();
            run.setText(parts[i]);
            run.setFontSize(fontSize);
            run.setFontFamily("微软雅黑");
            run.setBold(bold || (i % 2 == 1));
        }
    }

    /**
     * 判断是否为 docx 类型。
     */
    private boolean isDocx(String contentType, String filename) {
        if (contentType != null && contentType.contains("openxmlformats-officedocument.wordprocessingml")) {
            return true;
        }
        if (filename != null && filename.toLowerCase().endsWith(".docx")) {
            return true;
        }
        return false;
    }

    /**
     * 根据文件名推断 Content-Type。
     */
    private String inferContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }
}
