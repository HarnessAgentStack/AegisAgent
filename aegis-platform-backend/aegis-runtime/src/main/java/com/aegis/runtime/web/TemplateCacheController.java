package com.aegis.runtime.web;

import com.aegis.core.common.web.Result;
import com.aegis.runtime.integration.pool.AgentPoolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板缓存管理控制器。
 *
 * <p>供 Admin 服务在智能体配置/绑定变更后通知 Runtime 失效模板缓存。
 * 由网关内部路由，不对外暴露。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/internal/template-cache")
@RequiredArgsConstructor
public class TemplateCacheController {

    private final AgentPoolManager agentPoolManager;

    /**
     * 失效指定智能体的模板缓存。
     */
    @DeleteMapping("/{agentId}")
    public Result<Void> invalidate(@PathVariable Long agentId,
                                    @RequestParam(required = false) String version,
                                    @RequestParam Long tenantId) {
        agentPoolManager.invalidateTemplate(agentId, version, tenantId);
        log.info("Template cache invalidated via API: agentId={}, version={}, tenantId={}", agentId, version, tenantId);
        return Result.success(null);
    }
}
