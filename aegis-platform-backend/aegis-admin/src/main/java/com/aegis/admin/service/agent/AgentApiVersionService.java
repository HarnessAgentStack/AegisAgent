package com.aegis.admin.service.agent;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentApi;
import com.aegis.core.dto.agent.AgentApiVersionInfo;
import com.aegis.dal.mapper.agent.AgentApiMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApiVersionService {

    private final AgentApiMapper agentApiMapper;

    private final Map<String, AgentApiVersionInfo> versionSnapshotCache = new ConcurrentHashMap<>();

    public AgentApiVersionInfo getCurrentVersion(Long apiId) {
        AgentApi api = agentApiMapper.selectById(apiId);
        if (api == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "API配置不存在: " + apiId);
        }
        return toVersionInfo(api);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentApiVersionInfo bumpVersion(Long apiId) {
        AgentApi api = agentApiMapper.selectById(apiId);
        if (api == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "API配置不存在: " + apiId);
        }

        String currentVersion = api.getVersion() != null ? api.getVersion() : "1.0.0";
        String newVersion = incrementVersion(currentVersion);
        api.setVersion(newVersion);
        agentApiMapper.updateById(api);

        log.info("API version bumped: apiId={}, {} -> {}", apiId, currentVersion, newVersion);

        snapshotVersion(apiId);

        return toVersionInfo(api);
    }

    @Transactional(rollbackFor = Exception.class)
    public void snapshotVersion(Long apiId) {
        AgentApi api = agentApiMapper.selectById(apiId);
        if (api == null) {
            log.warn("Cannot snapshot version, API not found: {}", apiId);
            return;
        }
        AgentApiVersionInfo snapshot = toVersionInfo(api);
        versionSnapshotCache.put(apiId + ":" + api.getVersion(), snapshot);
        log.info("Version snapshot saved: apiId={}, version={}", apiId, api.getVersion());
    }

    public List<AgentApiVersionInfo> listVersions(Long agentId) {
        List<AgentApi> apis = agentApiMapper.selectList(new LambdaQueryWrapper<AgentApi>()
                .eq(AgentApi::getAgentId, agentId)
                .orderByDesc(AgentApi::getCreateTime));
        return apis.stream()
                .map(this::toVersionInfo)
                .toList();
    }

    public AgentApiVersionInfo getVersion(Long apiId, String version) {
        AgentApi api = agentApiMapper.selectById(apiId);
        if (api == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "API配置不存在: " + apiId);
        }
        if (version != null && version.equals(api.getVersion())) {
            return toVersionInfo(api);
        }
        AgentApiVersionInfo cached = versionSnapshotCache.get(apiId + ":" + version);
        if (cached != null) {
            return cached;
        }
        throw new BusinessException(ResultCode.NOT_FOUND, "版本不存在: " + version);
    }

    private AgentApiVersionInfo toVersionInfo(AgentApi api) {
        return AgentApiVersionInfo.builder()
                .version(api.getVersion() != null ? api.getVersion() : "1.0.0")
                .apiName(api.getApiName())
                .apiPath(api.getApiPath())
                .status(api.getStatus() != null ? api.getStatus().name() : null)
                .lastTestedAt(api.getLastTestedAt())
                .concurrentLimit(api.getConcurrentLimit())
                .rateLimit(api.getRateLimit())
                .build();
    }

    private String incrementVersion(String currentVersion) {
        try {
            String[] parts = currentVersion.split("\\.");
            if (parts.length >= 3) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major + "." + (minor + 1) + ".0";
            } else if (parts.length == 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major + "." + (minor + 1) + ".0";
            }
        } catch (Exception e) {
            log.warn("Invalid version format: {}, fallback to 1.0.0", currentVersion);
        }
        return "1.0.0";
    }
}