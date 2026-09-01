package com.aegis.runtime.service.rag;

import com.aegis.core.domain.attachment.AttParseCache;
import com.aegis.dal.mapper.document.AttParseCacheMapper;
import com.aegis.runtime.infrastructure.document.ParsedContent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 解析缓存服务。
 *
 * <p>缓存文件解析结果，避免重复解析相同文件。
 * 缓存键：fileId + parseVersion + contentHash（文件内容哈希）。
 * 当文件内容变更（contentHash 不同）时自动失效旧缓存。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseCacheService {

    private final AttParseCacheMapper cacheMapper;

    /** 当前解析引擎版本 */
    private static final String PARSE_VERSION = "v1";

    /**
     * 从缓存获取解析结果（带 contentHash 匹配）。
     *
     * <p>命中条件：fileId 匹配 + parseVersion 匹配 + contentHash 匹配。
     * 若 contentHash 为 null 则降级按 fileId + parseVersion 查询（兼容旧数据）。
     *
     * @param fileId      文件 ID
     * @param contentHash 文件内容哈希（SHA-256），可 null（降级兼容）
     * @return 解析结果；缓存未命中时返回 null
     */
    public ParsedContent getCached(String fileId, String contentHash) {
        try {
            LambdaQueryWrapper<AttParseCache> wrapper = new LambdaQueryWrapper<AttParseCache>()
                    .eq(AttParseCache::getFileId, fileId)
                    .eq(AttParseCache::getParseVersion, PARSE_VERSION);

            if (contentHash != null && !contentHash.isEmpty()) {
                wrapper.eq(AttParseCache::getContentHash, contentHash);
            }
            wrapper.last("LIMIT 1");

            AttParseCache cache = cacheMapper.selectOne(wrapper);
            if (cache == null) {
                return null;
            }

            log.debug("解析缓存命中: fileId={}, contentHash={}", fileId, contentHash);
            return ParsedContent.builder()
                    .text(cache.getParsedText())
                    .metadata(ParsedContent.ParseMetadata.builder()
                            .charCount(cache.getCharCount())
                            .estimatedTokens(cache.getTokenEstimate())
                            .build())
                    .build();
        } catch (Exception e) {
            log.warn("查询解析缓存失败: fileId={}", fileId, e);
            return null;
        }
    }

    /**
     * 从缓存获取解析结果（不带 contentHash，兼容旧调用方）。
     *
     * @deprecated 建议使用 {@link #getCached(String, String)} 带 contentHash 的重载版本
     */
    @Deprecated
    public ParsedContent getCached(String fileId) {
        return getCached(fileId, null);
    }

    /**
     * 保存解析结果到缓存（带 contentHash）。
     *
     * @param fileId      文件 ID
     * @param contentHash 文件内容哈希（SHA-256），可 null
     * @param contentType MIME 类型
     * @param parsed      解析结果
     */
    public void cacheParsed(String fileId, String contentHash, String contentType, ParsedContent parsed) {
        try {
            if (parsed == null || parsed.getText() == null) {
                return;
            }

            // 检查是否已存在（同 fileId + parseVersion + contentHash）
            LambdaQueryWrapper<AttParseCache> queryWrapper = new LambdaQueryWrapper<AttParseCache>()
                    .eq(AttParseCache::getFileId, fileId)
                    .eq(AttParseCache::getParseVersion, PARSE_VERSION);
            if (contentHash != null && !contentHash.isEmpty()) {
                queryWrapper.eq(AttParseCache::getContentHash, contentHash);
            }
            queryWrapper.last("LIMIT 1");

            AttParseCache existing = cacheMapper.selectOne(queryWrapper);

            if (existing != null) {
                // 更新
                existing.setParsedText(parsed.getText());
                existing.setCharCount(parsed.getMetadata() != null ? parsed.getMetadata().getCharCount() : 0);
                existing.setTokenEstimate(parsed.getMetadata() != null ? parsed.getMetadata().getEstimatedTokens() : 0);
                cacheMapper.updateById(existing);
                log.debug("解析缓存更新: fileId={}, contentHash={}", fileId, contentHash);
            } else {
                // 新增
                AttParseCache cache = new AttParseCache();
                cache.setFileId(fileId);
                cache.setParseVersion(PARSE_VERSION);
                cache.setContentHash(contentHash);
                cache.setContentType(contentType);
                cache.setParsedText(parsed.getText());
                cache.setCharCount(parsed.getMetadata() != null ? parsed.getMetadata().getCharCount() : 0);
                cache.setTokenEstimate(parsed.getMetadata() != null ? parsed.getMetadata().getEstimatedTokens() : 0);
                cacheMapper.insert(cache);
                log.debug("解析缓存保存: fileId={}, contentHash={}", fileId, contentHash);
            }
        } catch (Exception e) {
            log.warn("保存解析缓存失败: fileId={}", fileId, e);
        }
    }

    /**
     * 保存解析结果到缓存（不带 contentHash，兼容旧调用方）。
     *
     * @deprecated 建议使用 {@link #cacheParsed(String, String, String, ParsedContent)} 带 contentHash 的重载版本
     */
    @Deprecated
    public void cacheParsed(String fileId, String contentType, ParsedContent parsed) {
        cacheParsed(fileId, null, contentType, parsed);
    }

    /**
     * 清除指定文件的解析缓存。
     *
     * @param fileId 文件 ID
     */
    public void evict(String fileId) {
        try {
            cacheMapper.delete(
                    new LambdaQueryWrapper<AttParseCache>()
                            .eq(AttParseCache::getFileId, fileId));
            log.debug("解析缓存清除: fileId={}", fileId);
        } catch (Exception e) {
            log.warn("清除解析缓存失败: fileId={}", fileId, e);
        }
    }
}
