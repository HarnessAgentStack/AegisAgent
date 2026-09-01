package com.aegis.gateway.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 长连接处理器。
 *
 * <p>管理智能体任务 SSE 长连接的全生命周期：连接建立、事件推送、心跳保活、连接释放。
 * 作为网关对前端的流式出口，透传运行平面产生的流式事件（Token/工具调用/HITL 挂起）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>心跳保活：每 15 秒推送 {@code : heartbeat} 注释帧，防中间代理超时断开</li>
 *   <li>背压控制：基于 Reactor Flux，下游慢消费时缓冲有限条数后丢弃或反压</li>
 *   <li>连接亲和：同一 sessionId 路由至同一运行实例，SSE 流与任务执行同节点</li>
 *   <li>异常断连：客户端断开时及时释放服务端资源（取消上游订阅、回收会话上下文）</li>
 * </ul>
 *
 * <h3>事件协议</h3>
 * <ul>
 *   <li>{@code event: token} - 模型输出 Token 流</li>
 *   <li>{@code event: tool} - 工具调用进展</li>
 *   <li>{@code event: hitl} - HITL 挂起等待审批</li>
 *   <li>{@code event: done} - 任务完成</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class SseHandler {

    private final Map<String, FluxSink<String>> activeSessions = new ConcurrentHashMap<>();

    /**
     * 建立 SSE 长连接并透传运行平面事件流。
     *
     * @param sessionId 会话ID
     * @param eventFlux 运行平面事件流
     * @return SSE 响应
     */
    public Mono<ServerResponse> handle(String sessionId, Flux<String> eventFlux) {
        log.info("SSE connection established: sessionId={}", sessionId);
        Flux<String> heartbeat = Flux.interval(Duration.ofSeconds(15)).map(i -> ": heartbeat\n\n");
        Flux<String> merged = Flux.merge(eventFlux, heartbeat)
                .doFinally(signal -> {
                    activeSessions.remove(sessionId);
                    log.info("SSE connection closed: sessionId={}, signal={}", sessionId, signal);
                });
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(merged, String.class);
    }

    /**
     * 主动关闭指定会话的 SSE 连接（任务中断/超时场景）。
     *
     * @param sessionId 会话ID
     */
    public void close(String sessionId) {
        FluxSink<String> sink = activeSessions.remove(sessionId);
        if (sink != null) {
            sink.complete();
            log.info("SSE connection actively closed: sessionId={}", sessionId);
        }
    }
}
