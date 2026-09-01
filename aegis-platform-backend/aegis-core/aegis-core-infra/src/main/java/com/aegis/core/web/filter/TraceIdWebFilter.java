package com.aegis.core.web.filter;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 链路追踪 Web 过滤器（核心模块）。
 *
 * <p>为每个请求生成或透传 traceId，写入 MDC 供日志输出，
 * 并存入 exchange attributes 供异常处理器读取。
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>从 X-Trace-Id 请求头读取 traceId（网关已生成）</li>
 *   <li>若不存在，生成 8 位短 UUID 作为 traceId</li>
 *   <li>写入 MDC("traceId") 和 exchange attribute("traceId")</li>
 *   <li>请求结束后清除 MDC</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Order(Ordered.HIGHEST_PRECEDENCE - 50)
public class TraceIdWebFilter implements WebFilter {

    /** traceId 请求/响应头 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    /** exchange attribute key */
    public static final String ATTR_TRACE_ID = "traceId";
    /** MDC key */
    public static final String MDC_TRACE_ID = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = generateShortId();
        }

        // 存入 exchange attributes 供异常处理器读取
        exchange.getAttributes().put(ATTR_TRACE_ID, traceId);

        // 写入 MDC 供日志输出
        MDC.put(MDC_TRACE_ID, traceId);

        // 响应头也带上 traceId，方便运维定位
        exchange.getResponse().getHeaders().add(HEADER_TRACE_ID, traceId);

        return chain.filter(exchange)
                .doFinally(signal -> MDC.remove(MDC_TRACE_ID));
    }

    /**
     * 生成 8 位短 ID（UUID 前 8 位）。
     */
    private String generateShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
