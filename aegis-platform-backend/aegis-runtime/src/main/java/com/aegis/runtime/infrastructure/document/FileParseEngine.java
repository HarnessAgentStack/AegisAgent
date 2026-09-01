package com.aegis.runtime.infrastructure.document;

import com.aegis.runtime.service.rag.ParseCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 文件解析引擎（门面类）。
 *
 * <p>根据文件扩展名路由到对应 Parser，统一管理解析流程。
 * 解析失败时返回降级文本，不中断主流程。
 *
 * <h3>缓存优化</h3>
 * 带 fileId 的 parse 方法会先查 {@link ParseCacheService}，
 * 缓存键 = fileId + parseVersion + contentHash（SHA-256），
 * 命中则跳过 MinIO 读取和解析，直接返回缓存结果。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
public class FileParseEngine {

    private final List<FileParser> parsers;
    private final ParseCacheService parseCacheService;

    public FileParseEngine(List<FileParser> parsers, ParseCacheService parseCacheService) {
        this.parsers = parsers;
        this.parseCacheService = parseCacheService;
        log.info("FileParseEngine 已初始化，注册解析器: {}",
                parsers.stream().map(p -> p.getClass().getSimpleName()).toList());
    }

    /**
     * 解析文件内容（带缓存：fileId + contentHash）。
     *
     * <p>优先从 {@link ParseCacheService} 查找已缓存的解析结果，
     * 未命中才执行实际解析并写入缓存。
     *
     * @param content     文件字节数组
     * @param filename    原始文件名
     * @param contentType MIME 类型
     * @param fileId      文件 ID（用于缓存键），可 null（null 时跳过缓存）
     * @return 解析结果；解析失败时返回降级文本
     */
    public ParsedContent parse(byte[] content, String filename, String contentType, String fileId) {
        // 快速返回空内容
        if (content == null || content.length == 0) {
            return emptyResult();
        }

        // 1. 计算 contentHash（SHA-256）
        String contentHash = sha256Hex(content);

        // 2. 尝试从缓存命中
        if (fileId != null) {
            try {
                ParsedContent cached = parseCacheService.getCached(fileId, contentHash);
                if (cached != null) {
                    log.debug("FileParseEngine 解析缓存命中: fileId={}, filename={}", fileId, filename);
                    return cached;
                }
            } catch (Exception e) {
                log.warn("查询解析缓存失败（降级：跳过缓存直接解析）: fileId={}", fileId, e);
            }
        }

        // 3. 执行实际解析
        ParsedContent result = doParse(content, filename, contentType);

        // 4. 写入缓存
        if (fileId != null && result != null && result.getText() != null) {
            try {
                parseCacheService.cacheParsed(fileId, contentHash, contentType, result);
            } catch (Exception e) {
                log.warn("写入解析缓存失败: fileId={}", fileId, e);
            }
        }

        return result;
    }

    /**
     * 解析文件内容（不带缓存，兼容旧调用方）。
     *
     * @deprecated 建议使用 {@link #parse(byte[], String, String, String)} 带缓存的重载版本
     */
    @Deprecated
    public ParsedContent parse(byte[] content, String filename, String contentType) {
        return parse(content, filename, contentType, null);
    }

    /**
     * 实际解析逻辑（路由到对应 Parser）。
     */
    private ParsedContent doParse(byte[] content, String filename, String contentType) {
        String ext = getExtension(filename);

        FileParser parser = parsers.stream()
                .filter(p -> p.supports(ext, contentType))
                .findFirst()
                .orElse(null);

        if (parser == null) {
            log.warn("无匹配的解析器: filename={}, ext={}, contentType={}", filename, ext, contentType);
            String fallback = "[不支持的文件类型: " + ext + "]";
            return fallbackResult(fallback);
        }

        try {
            ParsedContent result = parser.parse(content, filename);
            log.debug("文件解析成功: filename={}, ext={}, charCount={}",
                    filename, ext, result.getMetadata() != null ? result.getMetadata().getCharCount() : 0);
            return result;
        } catch (IOException e) {
            log.error("文件解析失败: filename={}, ext={}", filename, ext, e);
            String fallback = "[文件解析失败: " + e.getMessage() + "]";
            return fallbackResult(fallback);
        }
    }

    private String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    private ParsedContent emptyResult() {
        return ParsedContent.builder()
                .text("")
                .metadata(ParsedContent.ParseMetadata.builder().build())
                .build();
    }

    private ParsedContent fallbackResult(String text) {
        return ParsedContent.builder()
                .text(text)
                .metadata(ParsedContent.ParseMetadata.builder()
                        .charCount(text.length())
                        .estimatedTokens(text.length() / 4)
                        .build())
                .build();
    }

    /**
     * 计算字节数组的 SHA-256 十六进制字符串。
     */
    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.warn("SHA-256 计算失败: {}", e.getMessage());
            // 降级：用 content.length 作为简单 hash（相同大小内容可能冲突，但可接受）
            return "size_" + data.length;
        }
    }
}
