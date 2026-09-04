package com.aegis.admin.service.agent;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentApiKey;
import com.aegis.core.enums.common.ValidityType;
import com.aegis.core.util.HashUtils;
import com.aegis.dal.mapper.agent.AgentApiKeyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApiKeyService {

    private final AgentApiKeyMapper agentApiKeyMapper;

    @Value("${aegis.api.key-prefix:aegis_}")
    private String keyPrefix;

    private static final int KEY_HEX_LENGTH = 32;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateKey(Long apiId, Long agentId, Long tenantId, String label, String validityType) {
        String rawKey = generateRawKey();
        String hash = HashUtils.sha256(rawKey);

        AgentApiKey entity = AgentApiKey.builder()
                .tenantId(tenantId)
                .agentId(agentId)
                .apiId(apiId)
                .apiKeyHash(hash)
                .keyLabel(label)
                .status(STATUS_ACTIVE)
                .expiresAt(calculateExpiry(validityType))
                .build();
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        agentApiKeyMapper.insert(entity);

        log.info("API key generated: apiId={}, agentId={}, tenantId={}, keyId={}",
                apiId, agentId, tenantId, entity.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("key", rawKey);
        result.put("entity", entity);
        return result;
    }

    public List<AgentApiKey> listByApiId(Long apiId) {
        return agentApiKeyMapper.listByApiId(apiId);
    }

    public List<AgentApiKey> listByAgentId(Long agentId) {
        return agentApiKeyMapper.selectList(new LambdaQueryWrapper<AgentApiKey>()
                .eq(AgentApiKey::getAgentId, agentId)
                .orderByDesc(AgentApiKey::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeKey(Long keyId) {
        AgentApiKey key = agentApiKeyMapper.selectById(keyId);
        if (key == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "API Key 不存在: " + keyId);
        }
        if (STATUS_REVOKED.equals(key.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "API Key 已被吊销: " + keyId);
        }
        agentApiKeyMapper.update(null, new LambdaUpdateWrapper<AgentApiKey>()
                .eq(AgentApiKey::getId, keyId)
                .set(AgentApiKey::getStatus, STATUS_REVOKED)
                .set(AgentApiKey::getUpdateTime, LocalDateTime.now()));
        log.info("API key revoked: keyId={}", keyId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rotateKey(Long apiId, Long oldKeyId, Long agentId, Long tenantId) {
        AgentApiKey oldKey = agentApiKeyMapper.selectById(oldKeyId);
        if (oldKey == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "旧 API Key 不存在: " + oldKeyId);
        }
        if (!STATUS_ACTIVE.equals(oldKey.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "旧 API Key 状态非 ACTIVE, 无法轮换: " + oldKey.getStatus());
        }

        String rawKey = generateRawKey();
        String hash = HashUtils.sha256(rawKey);

        AgentApiKey newKey = AgentApiKey.builder()
                .tenantId(tenantId)
                .agentId(agentId)
                .apiId(apiId)
                .apiKeyHash(hash)
                .keyLabel(oldKey.getKeyLabel())
                .status(STATUS_ACTIVE)
                .expiresAt(oldKey.getExpiresAt())
                .rotateFrom(oldKeyId)
                .build();
        newKey.setCreateTime(LocalDateTime.now());
        newKey.setUpdateTime(LocalDateTime.now());
        agentApiKeyMapper.insert(newKey);

        agentApiKeyMapper.update(null, new LambdaUpdateWrapper<AgentApiKey>()
                .eq(AgentApiKey::getId, oldKeyId)
                .set(AgentApiKey::getStatus, STATUS_REVOKED)
                .set(AgentApiKey::getUpdateTime, LocalDateTime.now()));

        log.info("API key rotated: oldKeyId={}, newKeyId={}, apiId={}", oldKeyId, newKey.getId(), apiId);

        Map<String, Object> result = new HashMap<>();
        result.put("key", rawKey);
        result.put("newKey", newKey);
        return result;
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void expireOldKeys() {
        LocalDateTime now = LocalDateTime.now();
        List<AgentApiKey> expiredKeys = agentApiKeyMapper.selectList(new LambdaQueryWrapper<AgentApiKey>()
                .eq(AgentApiKey::getStatus, STATUS_ACTIVE)
                .isNotNull(AgentApiKey::getExpiresAt)
                .lt(AgentApiKey::getExpiresAt, now));
        for (AgentApiKey key : expiredKeys) {
            agentApiKeyMapper.update(null, new LambdaUpdateWrapper<AgentApiKey>()
                    .eq(AgentApiKey::getId, key.getId())
                    .set(AgentApiKey::getStatus, STATUS_EXPIRED)
                    .set(AgentApiKey::getUpdateTime, now));
            log.info("API key expired: keyId={}, apiId={}", key.getId(), key.getApiId());
        }
        if (!expiredKeys.isEmpty()) {
            log.info("Expired {} API keys via scheduled task", expiredKeys.size());
        }
    }

    private String generateRawKey() {
        StringBuilder sb = new StringBuilder(keyPrefix);
        byte[] bytes = new byte[KEY_HEX_LENGTH / 2];
        secureRandom.nextBytes(bytes);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private LocalDateTime calculateExpiry(String validityType) {
        if (validityType == null || validityType.isEmpty()) {
            return null;
        }
        try {
            ValidityType type = ValidityType.valueOf(validityType);
            return switch (type) {
                case PERMANENT, CUSTOM -> null;
                case DAYS_7 -> LocalDateTime.now().plusDays(7);
                case DAYS_30 -> LocalDateTime.now().plusDays(30);
            };
        } catch (IllegalArgumentException e) {
            log.warn("Invalid validity type: {}, defaulting to PERMANENT", validityType);
            return null;
        }
    }
}