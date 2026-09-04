package com.aegis.admin.infrastructure.audit;

import com.aegis.core.common.web.Result;
import com.aegis.core.context.UserContextHolder;
import com.aegis.core.domain.monitor.AuditLog;
import com.aegis.core.domain.org.User;
import com.aegis.core.enums.monitor.AuditResult;
import com.aegis.core.security.UserContext;
import com.aegis.dal.mapper.monitor.AuditLogMapper;
import com.aegis.dal.mapper.org.UserBaseMapper;
import com.aegis.core.common.tenant.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计切面：拦截标注 {@link Auditable} 的方法，落审计日志。
 *
 * <p>从方法参数 {@code @RequestHeader} 提取 tenantId/userId/ip/traceId，
 * 从 {@link UserContextHolder} 取 username，从返回值/参数提取资源名称。
 * 正常返回记 SUCCESS，抛异常记对应结果（BLOCKED/FORBIDDEN 等），异常继续向上抛。
 *
 * <p>写入失败不阻断主流程（审计失败降级，仅记 warn 日志）。
 *
 * @author wang.zhen
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogMapper auditLogMapper;
    private final UserBaseMapper userBaseMapper;

    @Around("@annotation(auditable)")
    public Object aroundAuditable(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        long start = System.currentTimeMillis();
        AuditResult result = AuditResult.SUCCESS;
        String errorMsg = null;
        Object returnValue = null;
        try {
            returnValue = pjp.proceed();
            result = resolveResultFromReturn(returnValue);
            return returnValue;
        } catch (Throwable e) {
            result = resolveResultFromException(e);
            errorMsg = e.getMessage();
            throw e;
        } finally {
            try {
                writeAuditLog(pjp, auditable, result, returnValue, errorMsg, System.currentTimeMillis() - start);
            } catch (Exception ex) {
                log.warn("审计日志写入失败（降级，不阻断主流程）: operation={}", auditable.operation(), ex);
            }
        }
    }

    /** 从返回值解析结果：Result.fail 记 ALERT，Result.success 记 SUCCESS */
    private AuditResult resolveResultFromReturn(Object returnValue) {
        if (returnValue instanceof Result<?> r && !r.isSuccess()) {
            return AuditResult.ALERT;
        }
        return AuditResult.SUCCESS;
    }

    /** 从异常解析结果：权限异常记 BLOCKED，其他记 ALERT */
    private AuditResult resolveResultFromException(Throwable e) {
        if (e instanceof ResponseStatusException rse) {
            HttpStatus status = rse.getStatusCode() instanceof HttpStatus
                    ? (HttpStatus) rse.getStatusCode()
                    : HttpStatus.resolve(rse.getStatusCode().value());
            if (status == HttpStatus.FORBIDDEN) {
                return AuditResult.BLOCKED;
            }
            if (status == HttpStatus.UNAUTHORIZED) {
                return AuditResult.BLOCKED;
            }
        }
        return AuditResult.ALERT;
    }

    /** 组装并写入 AuditLog */
    private void writeAuditLog(ProceedingJoinPoint pjp, Auditable auditable, AuditResult result,
                                Object returnValue, String errorMsg, long elapsedMs) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Map<String, String> headers = extractHeaders(method, pjp.getArgs());
        ServerWebExchange exchange = findExchange(pjp.getArgs());

        Long tenantId = parseLong(headers.get("X-Tenant-Id"));
        Long userId = parseLong(headers.get("X-User-Id"));
        // 审计日志 insert 常发生在 @Auditable finally + WebFlux 线程切换后，
        // 入口 filter 的 ThreadLocal bind 可能已丢失，此处显式补绑，
        // 否则 AuditLogMapper.insert 走租户插件 fail-closed 抛"租户上下文缺失"。
        // 服务间调用（如 MCP Server 自注册）无 X-Tenant-Id 头：归入系统租户 0（平台级审计）
        Long effectiveTenantId = tenantId != null ? tenantId : TenantContextHolder.getTenantId();
        if (effectiveTenantId == null) {
            effectiveTenantId = 0L;
        }
        boolean boundHere = false;
        if (TenantContextHolder.get() == null) {
            TenantContextHolder.bind(effectiveTenantId);
            boundHere = true;
        }
        try {
        String username = resolveUsername(userId, tenantId);
        String ip = resolveIp(headers, exchange);
        String traceId = firstNonBlank(headers.get("X-Trace-Id"),
                exchange != null ? exchange.getRequest().getHeaders().getFirst("X-Trace-Id") : null);
        String resourceName = resolveResourceName(returnValue, pjp.getArgs(), auditable, method);

        String detail = buildDetail(elapsedMs, errorMsg, pjp.getArgs(), auditable);

        AuditLog auditLog = AuditLog.builder()
                .logType(auditable.logType())
                .userId(userId)
                .username(username)
                .operation(auditable.operation())
                .resourceType(auditable.resourceType())
                .resourceName(resourceName)
                .result(result)
                .ip(ip)
                .traceId(traceId)
                .detail(detail)
                .retentionDays(auditable.retentionDays())
                .occurTime(LocalDateTime.now())
                .build();
        auditLog.setTenantId(effectiveTenantId);
        auditLogMapper.insert(auditLog);
        } finally {
            if (boundHere) {
                TenantContextHolder.clear();
            }
        }
    }

    /** 从方法参数 @RequestHeader 注解提取对应头值 */
    private Map<String, String> extractHeaders(Method method, Object[] args) {
        Map<String, String> headers = new HashMap<>();
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length && i < args.length; i++) {
            RequestHeader rh = params[i].getAnnotation(RequestHeader.class);
            if (rh != null && args[i] != null) {
                String name = rh.value().isEmpty() ? rh.name() : rh.value();
                if (name.isEmpty() && rh.value().isEmpty()) {
                    continue;
                }
                headers.put(name.isEmpty() ? rh.value() : name, String.valueOf(args[i]));
            }
        }
        return headers;
    }

    /** 解析用户名：优先 UserContextHolder；WebFlux 下上下文常为空，回退按 userId 查库（realName 优先） */
    private String resolveUsername(Long userId, Long tenantId) {
        try {
            UserContext ctx = UserContextHolder.currentUser();
            if (ctx != null && ctx.getUsername() != null) {
                return ctx.getUsername();
            }
        } catch (Exception ignored) {
            // 上下文不可用时回退查库
        }
        if (userId == null) {
            return null;
        }
        // 租户表查询需绑定租户：已有绑定直接查；未绑定时用头里的 tenantId 临时绑定
        boolean tempBound = false;
        if (TenantContextHolder.get() == null) {
            if (tenantId == null) {
                return null;
            }
            TenantContextHolder.bind(tenantId);
            tempBound = true;
        }
        try {
            User user = userBaseMapper.selectById(userId);
            if (user != null) {
                return user.getRealName() != null && !user.getRealName().isBlank()
                        ? user.getRealName() : user.getUsername();
            }
        } catch (Exception ex) {
            log.debug("审计 username 回退查库失败: userId={}", userId, ex);
        } finally {
            if (tempBound) {
                TenantContextHolder.clear();
            }
        }
        return null;
    }

    /** 扫描方法参数定位 ServerWebExchange（用于读取请求头与远端地址） */
    private ServerWebExchange findExchange(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof ServerWebExchange exchange) {
                return exchange;
            }
        }
        return null;
    }

    /** 解析客户端 IP：代理头优先（X-Forwarded-For 多值取第一个），无代理头回退 TCP 远端地址 */
    private String resolveIp(Map<String, String> headers, ServerWebExchange exchange) {
        String ip = firstNonBlank(headers.get("X-Forwarded-For"),
                headers.get("X-Real-IP"), headers.get("X-Client-IP"));
        if (ip == null && exchange != null) {
            HttpHeaders httpHeaders = exchange.getRequest().getHeaders();
            ip = firstNonBlank(httpHeaders.getFirst("X-Forwarded-For"),
                    httpHeaders.getFirst("X-Real-IP"), httpHeaders.getFirst("X-Client-IP"));
        }
        if (ip != null) {
            return ip.split(",")[0].trim();
        }
        if (exchange != null) {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            if (remote != null && remote.getAddress() != null) {
                return remote.getAddress().getHostAddress();
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 解析资源名称：优先返回值中的 id/ name，回退参数中 resourceIdParam */
    private String resolveResourceName(Object returnValue, Object[] args, Auditable auditable, Method method) {
        if (returnValue instanceof Result<?> r && r.getData() != null) {
            return String.valueOf(r.getData());
        }
        if (!auditable.resourceIdParam().isEmpty()) {
            Parameter[] params = method.getParameters();
            for (int i = 0; i < params.length && i < args.length; i++) {
                if (auditable.resourceIdParam().equals(params[i].getName()) && args[i] != null) {
                    return String.valueOf(args[i]);
                }
            }
        }
        return null;
    }

    /** 构造详情 JSON */
    private String buildDetail(long elapsedMs, String errorMsg, Object[] args, Auditable auditable) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"elapsedMs\":").append(elapsedMs);
        if (errorMsg != null) {
            sb.append(",\"error\":\"").append(escape(errorMsg)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Long parseLong(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
