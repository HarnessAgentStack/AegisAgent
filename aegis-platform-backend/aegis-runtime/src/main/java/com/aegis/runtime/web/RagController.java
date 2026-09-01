package com.aegis.runtime.web;

import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextScope;
import com.aegis.runtime.service.rag.RagRetrieveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.aegis.core.domain.tenant.Tenant;

/**
 * RAG 检索控制器。
 *
 * <p>运行平面对外 RAG 检索接口入口，提供知识库语义检索能力。
 * 接收网关透传的租户身份，调度 {@link RagRetrieveService} 执行检索。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>阻塞式：返回 {@link Result} 同步响应，适合轻量检索场景</li>
 *   <li>身份来源：信任网关 X-Tenant-Id 头，不重复鉴权</li>
 *   <li>异常透传：业务异常经 {@link GlobalExceptionHandler} 转标准失败响应</li>
 * </ul>
 *
 * @author wang.zhen
 * @see RagRetrieveService
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagRetrieveService ragRetrieveService;

    /**
     * 知识库语义检索。
     *
     * @param tenantId 租户ID（网关注入）
     * @param kbId     知识库ID
     * @param query    用户查询文本
     * @param topK     返回条数（默认 5）
     * @return 检索结果列表，每条含 docId、content、score、chunkIndex
     */
    @GetMapping("/retrieve")
    public Result<List<Map<String, Object>>> retrieve(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam Long kbId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        // 边界式租户作用域（P1-1）：WebFlux 阻塞式 controller 运行在 boundedElastic 线程，
        // 网关过滤器的绑定不跨线程传递，需在执行线程上显式绑定；线程归池前必须清空。
        try (var ignore = TenantContextScope.bound(tenantId)) {
            return Result.success(ragRetrieveService.retrieve(tenantId, kbId, query, topK));
        }
    }
}
