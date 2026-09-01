package com.aegis.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 审计入口过滤器。
 *
 * <p>网关层入口审计采集器，为每条请求生成 traceId 并贯穿全链路。
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>前置：从 X-Trace-Id 读取或生成 8 位短 UUID 作为 traceId</li>
 *   <li>将 traceId 注入下游请求头 X-Trace-Id，供 admin/runtime 读取</li>
 *   <li>将 traceId 写入 MDC，供网关日志输出</li>
 *   <li>后置：记录请求耗时与状态码，清除 MDC</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Component
public class AuditEntryFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuditEntryFilter.class);

    private static final String ATTR_START_TIME = "auditStartTime";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String rawTraceId = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        final String traceId = (rawTraceId == null || rawTraceId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                : rawTraceId;

        var mutatedRequest = exchange.getRequest().mutate()
                .header(HEADER_TRACE_ID, traceId)
                .build();
        var mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        mutatedExchange.getResponse().getHeaders().add(HEADER_TRACE_ID, traceId);
        mutatedExchange.getAttributes().put(ATTR_START_TIME, System.currentTimeMillis());

        MDC.put(MDC_TRACE_ID, traceId);

        String path = mutatedExchange.getRequest().getPath().value();
        String method = mutatedExchange.getRequest().getMethod().name();

        return chain.filter(mutatedExchange)
                .doFinally(signal -> {
                    Object startAttr = mutatedExchange.getAttribute(ATTR_START_TIME);
                    if (startAttr instanceof Long startTime) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        int statusCode = mutatedExchange.getResponse().getStatusCode() != null
                                ? mutatedExchange.getResponse().getStatusCode().value() : 0;
                        MDC.put(MDC_TRACE_ID, traceId);
                        log.info("Audit: traceId={}, method={}, path={}, status={}, elapsed={}ms",
                                traceId, method, path, statusCode, elapsed);
                        MDC.remove(MDC_TRACE_ID);
                    }
                    MDC.remove(MDC_TRACE_ID);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE - 100;
    }
}
