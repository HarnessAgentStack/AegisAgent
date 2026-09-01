package com.aegis.admin.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * JWT 访问拒绝处理器。
 *
 * <p>处理已认证但权限不足的请求，返回统一的 JSON 格式 403 错误。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class JwtAccessDeniedHandler implements ServerAccessDeniedHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange,
                             AccessDeniedException denied) {
        log.warn("访问被拒绝: path={}, error={}",
                exchange.getRequest().getPath().value(), denied.getMessage());

        String body = "{\"code\":403,\"message\":\"权限不足，无法访问此资源\",\"data\":null}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
