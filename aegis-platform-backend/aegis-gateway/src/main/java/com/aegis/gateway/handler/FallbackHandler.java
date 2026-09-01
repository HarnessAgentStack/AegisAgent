package com.aegis.gateway.handler;

import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 降级处理器。
 *
 * <p>当后端服务（aegis-runtime / aegis-admin）不可用时，网关统一返回友好降级响应，
 * 避免裸露 502/503 错误页。区分服务降级（503）与限流降级（429），向用户给出可读提示。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>降级触发：路由无法找到可用实例 / 下游连接超时 / 熔断器开启</li>
 *   <li>响应一致性：降级响应仍为标准 {@link Result} 结构，前端可统一处理</li>
 *   <li>部分降级：仅故障路径降级，其他路径正常路由，缩小爆炸半径</li>
 *   <li>缓存兜底：热点查询可返回缓存旧值</li>
 * </ul>
 *
 * @author wang.zhen
 * @see Result
 */
@Slf4j
@Component
public class FallbackHandler {

    /**
     * 服务不可用降级响应。
     *
     * @param serviceName 故障服务名
     * @return 降级响应（503）
     */
    public Mono<ServerResponse> serviceUnavailable(String serviceName) {
        log.warn("Service unavailable fallback triggered: {}", serviceName);
        return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Result.fail(ResultCode.SERVICE_UNAVAILABLE, "服务暂不可用: " + serviceName + "，请稍后重试"));
    }

    /**
     * 限流降级响应。
     *
     * @param retryAfterSeconds 建议重试秒数
     * @return 降级响应（429）
     */
    public Mono<ServerResponse> rateLimited(long retryAfterSeconds) {
        log.warn("Rate limit fallback triggered, retryAfter: {}s", retryAfterSeconds);
        return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Result.fail(ResultCode.QUOTA_EXCEEDED, "请求过于频繁，请 " + retryAfterSeconds + " 秒后重试"));
    }
}
