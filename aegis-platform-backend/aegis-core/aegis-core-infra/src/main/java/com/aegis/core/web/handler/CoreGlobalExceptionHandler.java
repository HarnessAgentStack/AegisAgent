package com.aegis.core.web.handler;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.error.sandbox.SandboxErrorCode;
import com.aegis.core.common.error.sandbox.SandboxException;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.web.filter.TraceIdWebFilter;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 全局异常处理（核心模块）。
 *
 * <p>统一捕获异常，转换为标准 {@link Result} 响应。
 * 区分业务异常（4xx）、参数校验异常（400）、WebFlux 框架异常与系统异常（5xx），
 * 写入 traceId 便于全链路定位。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>业务异常 {@link BusinessException}：按 {@link ResultCode} 映射 HTTP 状态码</li>
 *   <li>参数校验异常：聚合字段校验错误信息，返回 400 + 详述</li>
 *   <li>权限不足：RBAC 拒绝返回 403，不暴露内部权限模型细节</li>
 *   <li>traceId 透传：响应携带 traceId，串联全链路审计</li>
 * </ul>
 *
 * @author wang.zhen
 * @see Result
 * @see BusinessException
 */
@Slf4j
@Order(-2)
public class CoreGlobalExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status;
        Result<Void> body;
        String traceId = resolveTraceId(exchange);

        if (ex instanceof BusinessException be) {
            // 业务异常：按 ResultCode 映射
            status = mapHttpStatus(be.getResultCode().getCode());
            body = Result.fail(be.getResultCode(), be.getMessage());
            body.setTraceId(traceId);
            log.warn("Business exception: path={}, traceId={}, code={}, msg={}",
                    exchange.getRequest().getPath().value(), traceId, be.getResultCode().getCode(), be.getMessage());
        } else if (ex instanceof SandboxException se) {
            // 沙箱领域异常：按 SandboxErrorCode 映射 HTTP 状态码，保留沙箱错误码语义
            // SandboxException extends RuntimeException（非 BusinessException 子类），
            // 若无此分支将落入 catch(Exception) → 500 兜底，丢失配额超限(429)/状态冲突(409)等语义
            status = mapSandboxHttpStatus(se.getErrorCode());
            ResultCode rc = mapSandboxResultCode(se.getErrorCode());
            body = Result.fail(rc, formatSandboxMessage(se));
            body.setTraceId(traceId);
            log.warn("Sandbox exception: path={}, traceId={}, code={}, operation={}, msg={}",
                    exchange.getRequest().getPath().value(), traceId,
                    se.getErrorCode(), se.getOperation(), se.getMessage());
        } else if (ex instanceof WebExchangeBindException || ex instanceof MethodArgumentNotValidException) {
            // 参数校验异常：聚合字段错误
            status = HttpStatus.BAD_REQUEST;
            String detail = collectBindingErrors(ex);
            body = Result.fail(ResultCode.PARAM_ERROR, "参数校验失败: " + detail);
            body.setTraceId(traceId);
            log.warn("Bind exception: path={}, traceId={}, msg={}",
                    exchange.getRequest().getPath().value(), traceId, detail);
        } else if (ex instanceof ResponseStatusException rse) {
            // Spring 框架的 ResponseStatusException，透传状态码与 reason
            status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
            String reason = rse.getReason() != null ? rse.getReason() : status.getReasonPhrase();
            body = Result.fail(mapToResultCode(status.value()), reason);
            body.setTraceId(traceId);
            if (rse.getCause() != null) {
                log.warn("ResponseStatus exception: path={}, traceId={}, status={}, reason={}, cause={}",
                        exchange.getRequest().getPath().value(), traceId, status.value(), reason, rse.getCause().toString());
                log.debug("ResponseStatus exception cause stack:", rse.getCause());
            } else {
                log.warn("ResponseStatus exception: path={}, traceId={}, status={}, reason={}",
                        exchange.getRequest().getPath().value(), traceId, status.value(), reason);
            }
        } else if (ex instanceof NoResourceFoundException) {
            // 404
            status = HttpStatus.NOT_FOUND;
            body = Result.fail(ResultCode.NOT_FOUND, "资源不存在");
            body.setTraceId(traceId);
            log.warn("Not found: path={}, traceId={}",
                    exchange.getRequest().getPath().value(), traceId);
        } else if (ex instanceof MethodNotAllowedException mae) {
            // 405
            status = HttpStatus.METHOD_NOT_ALLOWED;
            body = Result.fail(ResultCode.METHOD_NOT_ALLOWED, "方法不允许: " + mae.getHttpMethod());
            body.setTraceId(traceId);
            log.warn("Method not allowed: path={}, traceId={}, method={}",
                    exchange.getRequest().getPath().value(), traceId, mae.getHttpMethod());
        } else if (ex instanceof UnsupportedMediaTypeStatusException umse) {
            // 415
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            body = Result.fail(ResultCode.UNSUPPORTED_MEDIA_TYPE, "不支持的媒体类型");
            body.setTraceId(traceId);
            log.warn("Unsupported media type: path={}, traceId={}",
                    exchange.getRequest().getPath().value(), traceId);
        } else if (ex instanceof IllegalArgumentException) {
            // 非法参数
            status = HttpStatus.BAD_REQUEST;
            body = Result.fail(ResultCode.PARAM_ERROR, ex.getMessage());
            body.setTraceId(traceId);
            log.warn("Illegal argument: path={}, traceId={}, msg={}",
                    exchange.getRequest().getPath().value(), traceId, ex.getMessage());
        } else {
            // 兜底：500
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            body = Result.fail(ResultCode.INTERNAL_ERROR, "系统内部错误，请联系管理员");
            body.setTraceId(traceId);
            log.error("Internal error: path={}, traceId={}",
                    exchange.getRequest().getPath().value(), traceId, ex);
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        DataBuffer buffer = response.bufferFactory().wrap(toJsonBytes(body));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 聚合参数校验错误信息。
     */
    private String collectBindingErrors(Throwable ex) {
        if (ex instanceof WebExchangeBindException be) {
            return be.getFieldErrors().stream()
                    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        } else if (ex instanceof MethodArgumentNotValidException mane) {
            return mane.getFieldErrors().stream()
                    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        }
        return ex.getMessage() != null ? ex.getMessage() : "未知校验错误";
    }

    /**
     * 按 HTTP 状态码映射 ResultCode。
     */
    private ResultCode mapToResultCode(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> ResultCode.PARAM_ERROR;
            case 401 -> ResultCode.UNAUTHORIZED;
            case 403 -> ResultCode.FORBIDDEN;
            case 404 -> ResultCode.NOT_FOUND;
            case 409 -> ResultCode.CONFLICT;
            case 422 -> ResultCode.UNPROCESSABLE_ENTITY;
            case 429 -> ResultCode.TOO_MANY_REQUESTS;
            case 503 -> ResultCode.SERVICE_UNAVAILABLE;
            case 504 -> ResultCode.GATEWAY_TIMEOUT;
            default -> ResultCode.INTERNAL_ERROR;
        };
    }

    /**
     * 按 ResultCode 映射 HTTP 状态码。
     */
    private HttpStatus mapHttpStatus(int code) {
        return switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 405 -> HttpStatus.METHOD_NOT_ALLOWED;
            case 409 -> HttpStatus.CONFLICT;
            case 415 -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * 序列化为 JSON 字节。
     */
    private byte[] toJsonBytes(Object obj) {
        return JSON.toJSONBytes(obj);
    }

    /**
     * 按 SandboxErrorCode 映射 HTTP 状态码。
     *
     * <p>沙箱错误码无内嵌 httpStatus()，在此显式映射，保留领域语义：
     * <ul>
     *   <li>配额超限（SBX_QUOTA_*）→ 429 Too Many Requests</li>
     *   <li>状态冲突（SBX_ILLEGAL_STATE_TRANSITION / SBX_ALLOCATION_CONFLICT /
     *       SBX_INSTANCE_ALREADY_OCCUPIED / SBX_RELEASE_NOT_OCCUPIED /
     *       SBX_TERMINAL_STATE_IMMUTABLE / SBX_STATE_STALE）→ 409 Conflict</li>
     *   <li>无可用实例/池不可用/后端未配置（SBX_NO_AVAILABLE_INSTANCE / SBX_POOL_* /
     *       SBX_BACKEND_NOT_CONFIGURED / SBX_SCOPE_NOT_SUPPORTED）→ 503 Service Unavailable</li>
     *   <li>其余（生命周期/配置/通用运行时）→ 500</li>
     * </ul>
     */
    private HttpStatus mapSandboxHttpStatus(SandboxErrorCode code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case SBX_QUOTA_EXCEEDED, SBX_QUOTA_NOT_CONFIGURED -> HttpStatus.TOO_MANY_REQUESTS;
            case SBX_ILLEGAL_STATE_TRANSITION, SBX_ALLOCATION_CONFLICT,
                 SBX_INSTANCE_ALREADY_OCCUPIED, SBX_RELEASE_NOT_OCCUPIED,
                 SBX_TERMINAL_STATE_IMMUTABLE, SBX_STATE_STALE -> HttpStatus.CONFLICT;
            case SBX_NO_AVAILABLE_INSTANCE, SBX_POOL_UNAVAILABLE,
                 SBX_POOL_WARMUP_FAILED, SBX_POOL_SCALEDOWN_FAILED,
                 SBX_BACKEND_NOT_CONFIGURED, SBX_SCOPE_NOT_SUPPORTED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * 按 SandboxErrorCode 映射 ResultCode（用于响应体 code 字段）。
     *
     * <p>与 {@link #mapSandboxHttpStatus} 语义对齐，使响应 code 与 HTTP 状态一致。
     */
    private ResultCode mapSandboxResultCode(SandboxErrorCode code) {
        if (code == null) {
            return ResultCode.INTERNAL_ERROR;
        }
        return switch (code) {
            case SBX_QUOTA_EXCEEDED, SBX_QUOTA_NOT_CONFIGURED -> ResultCode.QUOTA_EXCEEDED;
            case SBX_ILLEGAL_STATE_TRANSITION, SBX_ALLOCATION_CONFLICT,
                 SBX_INSTANCE_ALREADY_OCCUPIED, SBX_RELEASE_NOT_OCCUPIED,
                 SBX_TERMINAL_STATE_IMMUTABLE, SBX_STATE_STALE -> ResultCode.CONFLICT;
            case SBX_NO_AVAILABLE_INSTANCE, SBX_POOL_UNAVAILABLE,
                 SBX_POOL_WARMUP_FAILED, SBX_POOL_SCALEDOWN_FAILED,
                 SBX_BACKEND_NOT_CONFIGURED, SBX_SCOPE_NOT_SUPPORTED -> ResultCode.SERVICE_UNAVAILABLE;
            default -> ResultCode.INTERNAL_ERROR;
        };
    }

    /**
     * 格式化沙箱异常消息（含操作标识与错误码）。
     */
    private String formatSandboxMessage(SandboxException se) {
        String op = se.getOperation();
        return (op != null ? "[" + op + "] " : "") + se.getMessage();
    }

    /**
     * 从 exchange attributes 或请求头中提取 traceId。
     */
    private String resolveTraceId(ServerWebExchange exchange) {
        Object attrTraceId = exchange.getAttribute(TraceIdWebFilter.ATTR_TRACE_ID);
        if (attrTraceId != null) {
            return attrTraceId.toString();
        }
        String headerTraceId = exchange.getRequest().getHeaders().getFirst(TraceIdWebFilter.HEADER_TRACE_ID);
        return headerTraceId != null ? headerTraceId : "";
    }
}
