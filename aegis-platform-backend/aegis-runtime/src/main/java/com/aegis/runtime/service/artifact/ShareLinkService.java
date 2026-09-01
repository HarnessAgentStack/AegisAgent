package com.aegis.runtime.service.artifact;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分享链接服务。
 *
 * <p>管理产物的分享链接生成、验证与吊销。
 *
 * <p><b>⚠️ 内存实现限制（开源前已知技术债）</b>：当前分享令牌存储于 {@link ConcurrentHashMap}，
 * 仅适用于单实例 dev/演示。存在两个已知限制：
 * <ol>
 *   <li>服务重启后所有分享链接立即失效（令牌不持久化）</li>
 *   <li>多副本部署下各副本令牌独立，跨副本无法验证</li>
 * </ol>
 * 生产环境上线前，必须替换为基于 Redis 的持久化方案（key={@code aegis:share:{token}}，
 * TTL=过期时间，{@code RedisTemplate} 操作），并改用 HMAC-SHA256 签名令牌替代 UUID，
 * secret 通过 {@code aegis.share.secret} 配置注入。接口契约（generateToken/verifyToken/revokeShareLink）
 * 保持不变，替换时无需改动调用方。</p>
 *
 * <h3>令牌格式</h3>
 * <p>分享令牌当前使用 UUID v4 格式，通过 {@link #verifyToken(String)} 验证有效性、
 * 过期时间与归属关系。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
public class ShareLinkService {

    private static final int DEFAULT_EXPIRE_MINUTES = 7 * 24 * 60;
    private static final int MAX_EXPIRE_MINUTES = 30 * 24 * 60;

    private final Map<String, ShareEntry> shareStore = new ConcurrentHashMap<>();

    /**
     * 分享元数据（用于令牌验证）。
     */
    public record ShareMeta(
            String artifactId,
            Long userId,
            LocalDateTime expireAt,
            String note
    ) {
    }

    /**
     * 分享结果（用于创建/列出分享链接）。
     */
    public record ShareResult(
            String token,
            String shareUrl,
            LocalDateTime expireAt,
            String note,
            int accessCount
    ) {
    }

    private static class ShareEntry {
        final String artifactId;
        final Long userId;
        final String token;
        final LocalDateTime expireAt;
        final String note;
        int accessCount;

        ShareEntry(String artifactId, Long userId, String token,
                   LocalDateTime expireAt, String note) {
            this.artifactId = artifactId;
            this.userId = userId;
            this.token = token;
            this.expireAt = expireAt;
            this.note = note;
            this.accessCount = 0;
        }
    }

    /**
     * 创建分享链接。
     *
     * @param artifactId     产物业务ID
     * @param userId         用户ID
     * @param expireMinutes  有效期（分钟），为空使用默认值，最大 30 天
     * @param note           分享备注
     * @return 分享结果
     */
    public ShareResult createShareLink(String artifactId, Long userId,
                                       Integer expireMinutes, String note) {
        if (!StringUtils.hasText(artifactId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产物ID不能为空");
        }

        int minutes = (expireMinutes != null && expireMinutes > 0)
                ? Math.min(expireMinutes, MAX_EXPIRE_MINUTES)
                : DEFAULT_EXPIRE_MINUTES;

        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expireAt = LocalDateTime.now().plusMinutes(minutes);

        ShareEntry entry = new ShareEntry(artifactId, userId, token, expireAt, note);
        shareStore.put(token, entry);

        String shareUrl = "/share/" + artifactId + "?token=" + token;

        log.info("创建分享链接: artifactId={}, userId={}, expireAt={}",
                artifactId, userId, expireAt);

        return new ShareResult(token, shareUrl, expireAt, note, 0);
    }

    /**
     * 验证分享令牌并返回元数据。
     *
     * @param token 分享令牌
     * @return 分享元数据，令牌无效或已过期返回 null
     */
    public ShareMeta verifyToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        ShareEntry entry = shareStore.get(token);
        if (entry == null) {
            log.warn("分享令牌不存在: token={}", token);
            return null;
        }
        if (entry.expireAt.isBefore(LocalDateTime.now())) {
            log.warn("分享令牌已过期: token={}, expireAt={}", token, entry.expireAt);
            shareStore.remove(token);
            return null;
        }
        entry.accessCount++;
        return new ShareMeta(entry.artifactId, entry.userId, entry.expireAt, entry.note);
    }

    /**
     * 列出指定产物的所有分享链接。
     *
     * @param artifactId 产物业务ID
     * @param userId     用户ID
     * @return 分享结果列表
     */
    public List<ShareResult> listShares(String artifactId, Long userId) {
        if (!StringUtils.hasText(artifactId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产物ID不能为空");
        }
        List<ShareResult> results = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (ShareEntry entry : shareStore.values()) {
            if (entry.artifactId.equals(artifactId)) {
                if (entry.expireAt.isBefore(now)) {
                    continue;
                }
                String shareUrl = "/share/" + entry.artifactId + "?token=" + entry.token;
                results.add(new ShareResult(
                        entry.token, shareUrl, entry.expireAt, entry.note, entry.accessCount));
            }
        }
        return results;
    }

    /**
     * 吊销分享链接。
     *
     * @param artifactId 产物业务ID
     * @param userId     用户ID
     * @param token      分享令牌
     */
    public void revokeShareLink(String artifactId, Long userId, String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "分享令牌不能为空");
        }
        ShareEntry removed = shareStore.remove(token);
        if (removed != null) {
            log.info("分享链接已吊销: artifactId={}, token={}", artifactId, token);
        }
    }
}