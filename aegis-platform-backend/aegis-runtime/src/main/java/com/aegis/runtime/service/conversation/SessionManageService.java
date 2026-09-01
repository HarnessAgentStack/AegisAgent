package com.aegis.runtime.service.conversation;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.session.Session;
import com.aegis.core.domain.session.SessionMessage;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.session.MessageType;
import com.aegis.core.enums.session.SessionStatus;
import com.aegis.dal.mapper.session.SessionMapper;
import com.aegis.dal.mapper.session.SessionMessageMapper;
import com.aegis.runtime.integration.model.LlmClientFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentConfig;

/**
 * 会话管理领域服务。
 *
 * <p>管理会话全生命周期（创建/激活/挂起/恢复/销毁）与会话审计投影，
 * 支撑 SSE 流式对话、消息历史回放、会话恢复。
 *
 * <p>运行时 LLM 历史由 AgentScope {@code RedisStore} 自动加载；本服务的 {@code persist*}
 * 方法采用 fire-and-forget 异步落库，供历史回放与合规审计使用。查询方法保持同步供 Controller 展示。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManageService {

    private final SessionMapper sessionMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final LlmClientFactory llmClientFactory;
    private final SessionSummaryService sessionSummaryService;
    /** P1-6：Redis 原子自增 seq，替代 JVM 锁 + FOR UPDATE + 唯一索引重试三层防护 */
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    // 事务管理器，用于编程式事务包装消息插入 + 会话统计更新
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * 按 userId+agentId 维度的会话创建锁池。
     *
     * <p>同一用户+同一智能体的 getOrCreateSession 请求串行化执行，
     * 防止并发创建两个 STARTED 活跃会话。
     */
    private final ConcurrentHashMap<String, ReentrantLock> sessionCreateLocks = new ConcurrentHashMap<>();

    /**
     * 初始化 TransactionTemplate（编程式事务）。
     *
     * <p>persistMessageInternal 被 fire-and-forget 的 Mono 调用，Spring AOP 声明式事务
     * 在此场景下可能失效，故采用 TransactionTemplate 编程式事务保证 INSERT 消息 + UPDATE
     * 会话统计的原子性。
     */
    @jakarta.annotation.PostConstruct
    public void initTransactionTemplate() {
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * 冻结同一用户+同一智能体的旧活跃会话。
     * 新建会话前调用，将旧的 STARTED/THINKING/OUTPUTTING 会话置为 ENDED。
     *
     * @param userId  用户ID
     * @param agentId 智能体ID
     */
    public void freezeActiveSession(Long userId, Long agentId) {
        sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                .eq(Session::getUserId, userId)
                .eq(Session::getAgentId, agentId)
                .in(Session::getStatus, SessionStatus.STARTED, SessionStatus.THINKING, SessionStatus.OUTPUTTING)
                .set(Session::getStatus, SessionStatus.ENDED));
    }

    /**
     * 创建或获取会话。
     *
     * <p>sessionId 为空时创建新会话，并锁定智能体版本快照。
     * sessionId 非空时校验租户隔离与用户所有权后返回。
     *
     * <p>分层防御：
     * <ol>
     *   <li>租户隔离：tenantId 不匹配 → 抛 SecurityException（403）</li>
     *   <li>横向越权：userId 不匹配 → 抛 SecurityException（403，防跨用户访问他人会话）</li>
     *   <li>fail-closed：tenantId 或 userId 为 null 时不创建新会话，返回 400 错误</li>
     * </ol>
     *
     * @param agentId   智能体ID
     * @param sessionId 会话ID（可空）
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @return 会话实体
     * @throws SecurityException 跨租户或跨用户访问时抛出
     * @throws BusinessException tenantId/userId 缺失时抛出（fail-closed）
     */
    public Session getOrCreateSession(Long agentId, String sessionId, Long tenantId, Long userId) {
        // fail-closed：创建/获取会话必须有合法的 tenantId 与 userId
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "创建/获取会话缺 tenantId");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "创建/获取会话缺 userId");
        }

        if (sessionId != null && !sessionId.isEmpty()) {
            Session existing = sessionMapper.selectOne(
                    new LambdaQueryWrapper<Session>().eq(Session::getSessionId, sessionId));
            if (existing != null) {
                // 校验租户隔离
                if (!tenantId.equals(existing.getTenantId())) {
                    log.warn("跨租户访问会话被拒绝: sessionId={}, requestTenantId={}, ownerTenantId={}",
                            sessionId, tenantId, existing.getTenantId());
                    throw new SecurityException("无权访问该会话");
                }
                // 校验用户所有权（横向越权防护）
                if (existing.getUserId() != null && !userId.equals(existing.getUserId())) {
                    log.warn("跨用户访问会话被拒绝: sessionId={}, requestUserId={}, ownerUserId={}",
                            sessionId, userId, existing.getUserId());
                    throw new SecurityException("无权访问该会话");
                }
                return existing;
            }
        }

        // 按 userId+agentId 加锁，防止并发创建两个 STARTED 活跃会话
        String createLockKey = userId + ":" + agentId;
        ReentrantLock createLock = sessionCreateLocks.computeIfAbsent(createLockKey, k -> new ReentrantLock());
        createLock.lock();
        try {
            // Double-check：持锁后再次检查是否已有活跃会话（可能在等锁期间被其他线程创建）
            Session activeSession = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                    .eq(Session::getUserId, userId)
                    .eq(Session::getAgentId, agentId)
                    .eq(Session::getTenantId, tenantId)
                    .in(Session::getStatus, SessionStatus.STARTED, SessionStatus.THINKING, SessionStatus.OUTPUTTING)
                    .last("LIMIT 1"));
            if (activeSession != null) {
                log.info("复用并发创建的活跃会话: sessionId={}, userId={}, agentId={}",
                        activeSession.getSessionId(), userId, agentId);
                return activeSession;
            }
            // 冻结旧会话 + 创建新会话（在锁内原子执行）
            freezeActiveSession(userId, agentId);
            return createNewSession(agentId, tenantId, userId);
        } finally {
            createLock.unlock();
        }
    }

    /**
     * 创建新会话。
     */
    public Session createNewSession(Long agentId, Long tenantId, Long userId) {
        String newSessionId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();

        Session session = Session.builder()
                .sessionId(newSessionId)
                .agentId(agentId)
                .userId(userId)
                .status(SessionStatus.STARTED)
                .messageCount(0)
                .tokenUsed(0L)
                .lastActiveTime(now)
                .expireTime(now.plusDays(7))
                .build();
        session.setTenantId(tenantId);

        sessionMapper.insert(session);
        log.info("Session created: sessionId={}, agentId={}, tenantId={}, userId={}",
                newSessionId, agentId, tenantId, userId);
        return session;
    }

    /**
     * 异步投影用户消息到 DB（fire-and-forget 审计投影）。
     *
     * <p>运行时上下文由 AS RedisStore 维护，本方法仅做审计落库，不阻塞响应式主线程。
     * 写入失败仅记录日志，不影响主流程。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @param content   消息内容
     */
    public void persistUserMessage(String sessionId, Long tenantId, Long userId, String content) {
        Mono.fromRunnable(() -> {
            // 增加重试（3 次，每次间隔 100ms），避免 DB 写入失败仅 log 后丢失
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    persistMessageInternal(sessionId, tenantId, userId, MessageType.USER, content, null, 0, 0);
                    return;
                } catch (Exception e) {
                    log.error("P1-12: persistUserMessage 投影失败 (attempt {}/3): sessionId={}", attempt, sessionId, e);
                    if (attempt < 3) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 异步投影助手消息到 DB（fire-and-forget 审计投影）。
     *
     * @param sessionId    会话ID
     * @param tenantId     租户ID
     * @param userId       用户ID
     * @param content      助手回复内容
     * @param reasoning    推理过程（可选）
     * @param tokenInput   输入 Token 数
     * @param tokenOutput  输出 Token 数
     * @param toolCalls    工具调用列表（可选，预留扩展）
     * @param kbRefs       知识库引用 JSON（可选）
     */
    public void persistAssistantMessage(String sessionId, Long tenantId, Long userId,
                                         String content, String reasoning,
                                         int tokenInput, int tokenOutput,
                                         List<Map<String, Object>> toolCalls, String kbRefs) {
        Mono.fromRunnable(() -> {
            // 增加重试（3 次，每次间隔 100ms），避免 DB 写入失败仅 log 后丢失
            SessionMessage msg = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    msg = persistMessageInternal(sessionId, tenantId, userId, MessageType.ASSISTANT,
                            content, reasoning, tokenInput, tokenOutput);
                    break;
                } catch (Exception e) {
                    log.error("P1-12: persistAssistantMessage 投影失败 (attempt {}/3): sessionId={}", attempt, sessionId, e);
                    if (attempt < 3) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
            // 更新 kbRefs 字段（best-effort，不参与重试）
            if (kbRefs != null && msg != null) {
                try {
                    sessionMessageMapper.update(null, new LambdaUpdateWrapper<SessionMessage>()
                            .eq(SessionMessage::getId, msg.getId())
                            .set(SessionMessage::getKbRefs, kbRefs));
                } catch (Exception e) {
                    log.error("P1-12: persistAssistantMessage kbRefs 更新失败: sessionId={}", sessionId, e);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 异步投影 TOOL_CALL 消息到 DB（fire-and-forget 审计投影）。
     *
     * @param sessionId   会话ID
     * @param tenantId    租户ID
     * @param userId      用户ID
     * @param toolCallId  工具调用ID
     * @param toolName    工具名称
     * @param toolParams  工具参数 JSON
     */
    public void persistToolCallMessage(String sessionId, Long tenantId, Long userId,
                                        String toolCallId, String toolName, String toolParams) {
        Mono.fromRunnable(() -> {
            // 增加重试（3 次，每次间隔 100ms），避免 DB 写入失败仅 log 后丢失
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    // 复用 persistMessageInternal，保证事务原子性与 seq 重试抛异常语义
                    persistMessageInternal(sessionId, tenantId, userId, MessageType.TOOL_CALL,
                            null, null, 0, 0, toolCallId, toolName, toolParams, null);
                    return;
                } catch (Exception e) {
                    log.error("P1-12: persistToolCallMessage 投影失败 (attempt {}/3): sessionId={}", attempt, sessionId, e);
                    if (attempt < 3) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 异步投影 TOOL_RESULT 消息到 DB（fire-and-forget 审计投影）。
     *
     * @param sessionId   会话ID
     * @param tenantId    租户ID
     * @param userId      用户ID
     * @param toolCallId  对应的工具调用ID
     * @param toolResult  工具执行结果
     */
    public void persistToolResultMessage(String sessionId, Long tenantId, Long userId,
                                          String toolCallId, String toolResult) {
        Mono.fromRunnable(() -> {
            // 增加重试（3 次，每次间隔 100ms），避免 DB 写入失败仅 log 后丢失
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    // 复用 persistMessageInternal，保证事务原子性与 seq 重试抛异常语义
                    persistMessageInternal(sessionId, tenantId, userId, MessageType.TOOL_RESULT,
                            null, null, 0, 0, toolCallId, null, null, toolResult);
                    return;
                } catch (Exception e) {
                    log.error("P1-12: persistToolResultMessage 投影失败 (attempt {}/3): sessionId={}", attempt, sessionId, e);
                    if (attempt < 3) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private SessionMessage persistMessageInternal(String sessionId, Long tenantId, Long userId,
                                                  MessageType messageType, String content, String reasoning,
                                                  int tokenInput, int tokenOutput) {
        return persistMessageInternal(sessionId, tenantId, userId, messageType, content, reasoning,
                tokenInput, tokenOutput, null, null, null, null);
    }

    /**
     * 消息持久化核心方法（事务 + seq 重试 + 串行化）。
     *
     * <p>所有 persist* 方法最终委托至此方法，保证：
     * <ul>
     *   <li>按 sessionId 串行化，消息顺序与 seq 单调递增</li>
     *   <li>TransactionTemplate 编程式事务，INSERT 消息 + UPDATE 统计原子化</li>
     *   <li>seq 冲突重试耗尽后抛异常（不静默丢失，不更新计数）</li>
     *   <li>每次重试后强制刷新 seq，防止 REPEATABLE READ 快照导致 MAX(seq) 脏读</li>
     * </ul>
     *
     * @param toolCallId  工具调用ID（仅 TOOL_CALL/TOOL_RESULT 消息使用，其他为 null）
     * @param toolName    工具名称（仅 TOOL_CALL 使用）
     * @param toolParams  工具参数 JSON（仅 TOOL_CALL 使用）
     * @param toolResult  工具结果（仅 TOOL_RESULT 使用）
     */
    private SessionMessage persistMessageInternal(String sessionId, Long tenantId, Long userId,
                                                  MessageType messageType, String content, String reasoning,
                                                  int tokenInput, int tokenOutput,
                                                  String toolCallId, String toolName, String toolParams, String toolResult) {
        // P1-6：seq 改 Redis INCR 原子自增（跨实例天然单调），删除 JVM 锁 + FOR UPDATE + 唯一索引重试三层防护。
        // Redis 是 runtime 硬依赖（AS2 RedisStore 已用），INCR 语义与前端 beforeSeq 分页连续性兼容。
        final int seq = nextSeqViaRedis(sessionId, tenantId);
        try {
            return transactionTemplate.execute(status -> {
                String safeParams = toJsonSafe(toolParams);
                String safeResult = toJsonSafe(toolResult);
                SessionMessage msg = SessionMessage.builder()
                        .sessionId(sessionId)
                        .messageType(messageType)
                        .content(content)
                        .reasoning(reasoning)
                        .tokenInput(tokenInput)
                        .tokenOutput(tokenOutput)
                        .toolCallId(toolCallId)
                        .toolName(toolName)
                        .toolParams(safeParams)
                        .toolResult(safeResult)
                        .seq(seq)
                        .build();
                msg.setTenantId(tenantId);
                msg.setCreateBy(userId);

                sessionMessageMapper.insert(msg);

                sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                        .eq(Session::getSessionId, sessionId)
                        .setSql("message_count = message_count + 1")
                        .set(Session::getLastActiveTime, LocalDateTime.now())
                        .setSql("token_used = token_used + " + Math.max(0, tokenInput + tokenOutput)));

                // P1-6：标题生成移出事务——首轮 USER 消息 INSERT + 统计 UPDATE 提交后，
                // 另起异步任务单独小事务 UPDATE title（降级截断逻辑已有，LLM 失败无影响）。
                // 原实现将同步 LLM 调用置于事务内，DB 连接+事务被 LLM 往返秒级持有。
                if (seq == 1 && messageType == MessageType.USER) {
                    scheduleAsyncTitleUpdate(sessionId, tenantId, content);
                }
                return msg;
            });
        } catch (DuplicateKeyException e) {
            // 唯一索引作为 DB 兜底安全网保留；Redis INCR 下理论上不触发（同会话串行），
            // 仅在 Redis 故障降级路径或极端并发抖动时兜底，此时回退 DB MAX(seq)+1 重试一次。
            log.warn("seq 唯一索引冲突（Redis INCR 兜底触发），回退 DB 重算: sessionId={}, seq={}", sessionId, seq);
            int fallbackSeq = sessionMessageMapper.selectMaxSeqForUpdate(sessionId, tenantId) + 1;
            SessionMessage msg = SessionMessage.builder()
                    .sessionId(sessionId).messageType(messageType).content(content).reasoning(reasoning)
                    .tokenInput(tokenInput).tokenOutput(tokenOutput)
                    .toolCallId(toolCallId).toolName(toolName)
                    .toolParams(toJsonSafe(toolParams)).toolResult(toJsonSafe(toolResult))
                    .seq(fallbackSeq).build();
            msg.setTenantId(tenantId);
            msg.setCreateBy(userId);
            sessionMessageMapper.insert(msg);
            sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                    .eq(Session::getSessionId, sessionId)
                    .setSql("message_count = message_count + 1")
                    .set(Session::getLastActiveTime, LocalDateTime.now())
                    .setSql("token_used = token_used + " + Math.max(0, tokenInput + tokenOutput)));
            if (fallbackSeq == 1 && messageType == MessageType.USER) {
                scheduleAsyncTitleUpdate(sessionId, tenantId, content);
            }
            return msg;
        }
    }

    /**
     * P1-6：Redis INCR 获取下一 seq，SETNX 兼容存量会话初始化。
     *
     * <p>key = {@code aegis:msg:seq:{sessionId}}。首次用 SETNX 以 DB MAX(seq) 初始化（兼容存量），
     * 之后 INCR 原子自增。Redis 不可用时降级回 DB MAX(seq)+1（保留正确性，放弃跨实例互斥）。
     */
    private int nextSeqViaRedis(String sessionId, Long tenantId) {
        String key = "aegis:msg:seq:" + sessionId;
        try {
            // 兼容存量会话：若 key 不存在，以 DB 当前 MAX(seq) 初始化（SETNX 仅在不存在时写入）
            Boolean initialized = stringRedisTemplate.opsForValue().setIfAbsent(key, "0");
            if (Boolean.TRUE.equals(initialized)) {
                int dbMax = sessionMessageMapper.selectMaxSeqForUpdate(sessionId, tenantId);
                stringRedisTemplate.opsForValue().set(key, String.valueOf(dbMax));
            }
            Long next = stringRedisTemplate.opsForValue().increment(key);
            return next == null ? (sessionMessageMapper.selectMaxSeqForUpdate(sessionId, tenantId) + 1) : next.intValue();
        } catch (Exception e) {
            log.warn("Redis INCR seq 失败，降级 DB MAX(seq)+1（单实例正确，跨实例可能冲突）: sessionId={}", sessionId, e);
            return sessionMessageMapper.selectMaxSeqForUpdate(sessionId, tenantId) + 1;
        }
    }

    /**
     * P1-6：异步生成标题并更新会话（事务外，独立小事务；恢复租户上下文供 LLM 调用）。
     *
     * <p>降级截断逻辑已内置于 {@link #generateTitle}，LLM 失败不影响主流程。
     */
    private void scheduleAsyncTitleUpdate(String sessionId, Long tenantId, String firstUserMessage) {
        Mono.fromRunnable(() -> {
                    try {
                        com.aegis.core.common.tenant.TenantContextHolder.bind(tenantId);
                        String title = generateTitle(firstUserMessage, sessionId, tenantId);
                        transactionTemplate.executeWithoutResult(status ->
                                sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                                        .eq(Session::getSessionId, sessionId)
                                        .set(Session::getTitle, title)));
                        log.info("异步标题生成完成: sessionId={}, title={}", sessionId, title);
                    } catch (Exception e) {
                        log.warn("异步标题生成失败（不影响会话）: sessionId={}, error={}", sessionId, e.getMessage());
                    } finally {
                        com.aegis.core.common.tenant.TenantContextHolder.clear();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * 加载会话历史消息。
     *
     * <p>强制校验 tenantId 与会话归属：
     * <ol>
     *   <li>tenantId 为空 → 返回空列表（fail-closed，防越权）</li>
     *   <li>会话不存在或不属于该租户 → 返回空列表（不暴露存在性）</li>
     *   <li>查询时显式加 tenant_id 条件（双保险）</li>
     * </ol>
     */
    public List<SessionMessage> loadHistory(String sessionId, Long tenantId, int limit, Integer beforeSeq) {
        // fail-closed：tenantId 必须存在
        if (tenantId == null || tenantId <= 0) {
            log.warn("loadHistory 拒绝（缺 tenantId）: sessionId={}", sessionId);
            return java.util.Collections.emptyList();
        }
        // 会话归属校验：会话不存在或不属于该租户时返回空列表（不暴露存在性）
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getSessionId, sessionId)
                .eq(Session::getTenantId, tenantId));
        if (session == null) {
            log.warn("loadHistory 拒绝（会话不存在或跨租户）: sessionId={}, tenantId={}", sessionId, tenantId);
            return java.util.Collections.emptyList();
        }

        LambdaQueryWrapper<SessionMessage> wrapper = new LambdaQueryWrapper<SessionMessage>()
                .eq(SessionMessage::getSessionId, sessionId)
                // 显式加 tenant_id 条件
                .eq(SessionMessage::getTenantId, tenantId)
                .orderByDesc(SessionMessage::getSeq)
                .last("LIMIT " + Math.min(limit, 100));
        if (beforeSeq != null && beforeSeq > 0) {
            wrapper.lt(SessionMessage::getSeq, beforeSeq);
        }
        List<SessionMessage> list = sessionMessageMapper.selectList(wrapper);
        list.sort((a, b) -> Integer.compare(a.getSeq(), b.getSeq()));
        // 聚合 TOOL_CALL + TOOL_RESULT：将 TOOL_RESULT 消息的 toolResult 字段合并到对应 TOOL_CALL 消息，
        // 然后移除独立的 TOOL_RESULT 行，保证一条 tool_call 同时含 params 与 result，status 正确。
        return mergeToolCallResult(list);
    }

    /**
     * 将 TOOL_CALL 和 TOOL_RESULT 消息按 toolCallId 聚合。
     *
     * <p>审计投影写入时是两条独立消息（TOOL_CALL 存参数，TOOL_RESULT 存结果），
     * 历史回放要求一条消息同时含工具名称、参数、结果和状态。此方法在返回前完成合并：
     * <ul>
     *   <li>TOOL_RESULT 的 toolResult 字段合并到同 toolCallId 的 TOOL_CALL 消息</li>
     *   <li>独立的 TOOL_RESULT 行从返回列表中移除（避免重复渲染）</li>
     *   <li>无匹配 TOOL_CALL 的 TOOL_RESULT 也会被吸收（不泄漏到结果列表）</li>
     * </ul>
     */
    private List<SessionMessage> mergeToolCallResult(List<SessionMessage> list) {
        if (list == null || list.isEmpty()) return list;
        // 先收集 toolCallId -> TOOL_RESULT 索引
        java.util.Map<String, SessionMessage> resultByCallId = new java.util.HashMap<>();
        for (SessionMessage m : list) {
            if (MessageType.TOOL_RESULT.equals(m.getMessageType()) && m.getToolCallId() != null) {
                resultByCallId.put(m.getToolCallId(), m);
            }
        }
        if (resultByCallId.isEmpty()) return list;
        // 合并：TOOL_CALL 消息吸收对应 TOOL_RESULT 的 toolResult 字段，并移除 TOOL_RESULT 行
        java.util.List<SessionMessage> merged = new java.util.ArrayList<>(list.size());
        for (SessionMessage m : list) {
            if (MessageType.TOOL_CALL.equals(m.getMessageType()) && m.getToolCallId() != null) {
                SessionMessage resultMsg = resultByCallId.get(m.getToolCallId());
                if (resultMsg != null && resultMsg.getToolResult() != null) {
                    // 合并 toolResult 到 TOOL_CALL 消息
                    m.setToolResult(resultMsg.getToolResult());
                }
                merged.add(m);
            } else if (MessageType.TOOL_RESULT.equals(m.getMessageType())) {
                // 跳过独立 TOOL_RESULT 行，已被上方 TOOL_CALL 吸收
            } else {
                merged.add(m);
            }
        }
        return merged;
    }

    /**
     * 更新会话状态。
     */
    public void updateStatus(String sessionId, SessionStatus status) {
        sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                .eq(Session::getSessionId, sessionId)
                .set(Session::getStatus, status));
    }

    /**
     * 条件更新：仅当会话当前为活跃态时才更新为指定终态。
     *
     * <p>用于外层 doFinally 兜底场景，避免覆盖内层已正确设置的终态
     * （ENDED/EXCEPTION/INTERRUPTED）。仅活跃态（STARTED/THINKING/TOOL_CALLING/OUTPUTTING）
     * 的会话才会被更新，表示该会话是 CANCEL 信号未传播到内层的"僵尸会话"。
     *
     * @param sessionId      会话ID
     * @param terminalStatus 终态状态（通常为 INTERRUPTED）
     * @return true=已更新（说明确实是僵尸会话），false=未更新（已正确终态或不存在）
     */
    public boolean terminateIfActive(String sessionId, SessionStatus terminalStatus) {
        int rows = sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                .eq(Session::getSessionId, sessionId)
                .in(Session::getStatus,
                        SessionStatus.STARTED, SessionStatus.THINKING,
                        SessionStatus.TOOL_CALLING, SessionStatus.OUTPUTTING)
                .set(Session::getStatus, terminalStatus));
        return rows > 0;
    }

    /**
     * 查询会话（跨租户返回 null，不抛异常以避免信息泄露）。
     *
     * <p>供 Controller 调用，跨租户访问时返回 null（视同不存在），
     * 由 Controller 决定返回 404 或 403。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID，null 时直接返回 null（fail-closed）
     * @return 会话实体，跨租户或不存在时返回 null
     */
    public Session getSession(String sessionId, Long tenantId) {
        if (sessionId == null || sessionId.isEmpty() || tenantId == null) {
            return null;
        }
        Session session = sessionMapper.selectOne(
                new LambdaQueryWrapper<Session>().eq(Session::getSessionId, sessionId));
        if (session == null) {
            return null;
        }
        // 跨租户访问返回 null（不抛异常，避免暴露会话存在性）
        if (!tenantId.equals(session.getTenantId())) {
            log.warn("跨租户访问会话拦截: sessionId={}, requestTenantId={}, ownerTenantId={}",
                    sessionId, tenantId, session.getTenantId());
            return null;
        }
        return session;
    }

    /**
     * 用户会话列表（分页）。
     */
    public List<Session> listSessions(Long tenantId, Long userId, int page, int size) {
        return listSessions(tenantId, userId, null, page, size);
    }

    /**
     * 用户会话列表（分页，支持按智能体过滤）。
     */
    public List<Session> listSessions(Long tenantId, Long userId, Long agentId, int page, int size) {
        int offset = Math.max(page - 1, 0) * size;
        return sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(tenantId != null, Session::getTenantId, tenantId)
                .eq(userId != null, Session::getUserId, userId)
                .eq(agentId != null, Session::getAgentId, agentId)
                .orderByDesc(Session::getLastActiveTime)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    /**
     * 会话总数。
     */
    public long countSessions(Long tenantId, Long userId) {
        return countSessions(tenantId, userId, null);
    }

    /**
     * 会话总数（支持按智能体过滤）。
     */
    public long countSessions(Long tenantId, Long userId, Long agentId) {
        return sessionMapper.selectCount(new LambdaQueryWrapper<Session>()
                .eq(tenantId != null, Session::getTenantId, tenantId)
                .eq(userId != null, Session::getUserId, userId)
                .eq(agentId != null, Session::getAgentId, agentId));
    }

    /**
     * 删除会话（逻辑删除），级联删除该会话下的所有消息。
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId, Long tenantId) {
        Session session = getSession(sessionId, tenantId);
        if (session == null) {
            return;
        }
        // 级联删除消息
        sessionMessageMapper.delete(new LambdaQueryWrapper<SessionMessage>()
                .eq(SessionMessage::getSessionId, sessionId));
        sessionMapper.deleteById(session.getId());
        log.info("Session and messages deleted: sessionId={}", sessionId);
    }

    /**
     * 删除会话及其消息（级联物理删除），校验会话所有权。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @param userId    用户ID，非空时校验会话所有者
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId, Long tenantId, Long userId) {
        Session session = getSession(sessionId, tenantId);
        if (session == null) return;
        if (userId != null && !userId.equals(session.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅会话所有者可删除");
        }
        // 级联删除消息
        sessionMessageMapper.delete(new LambdaQueryWrapper<SessionMessage>()
                .eq(SessionMessage::getSessionId, sessionId));
        // 删除会话
        sessionMapper.deleteById(session.getId());
        // P2-9：同步删除 Redis AgentState（原 deleteSession 只删 MySQL，Redis agent_state 永久残留→孤儿堆积）
        // AS2 key 模式：aegis:session:{userId}/{sessionId}:agent_state（userId 可能 null，用 "0" 占位）
        Long stateUserId = session.getUserId() != null ? session.getUserId() : 0L;
        String stateKey = "aegis:session:" + stateUserId + "/" + sessionId + ":agent_state";
        try {
            stringRedisTemplate.delete(stateKey);
            // _keys 索引 key（RedisBaseStore 维护的 keys 索引，删除主 key 须连带清理）
            stringRedisTemplate.delete(stateKey + ":_keys");
        } catch (Exception e) {
            log.warn("deleteSession Redis 清理失败（MySQL 已删，孤儿 key 可由定时任务补清）: sessionId={}", sessionId, e);
        }
        log.info("Session and messages deleted (Redis agent_state cleared): sessionId={}", sessionId);
    }

    /**
     * 删除单轮用户消息及其紧邻的 ASSISTANT 回复。
     *
     * <p>tenantId 校验为 fail-closed（msg.tenantId 为 null 时拒绝）；userId 校验会话所有权（防跨用户删除）。
     *
     * @param sessionId 会话ID
     * @param messageId 消息ID
     * @param tenantId  租户ID（租户隔离校验，fail-closed）
     * @param userId    用户ID（会话所有权校验）
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void deleteMessage(String sessionId, Long messageId, Long tenantId, Long userId) {
        SessionMessage msg = sessionMessageMapper.selectById(messageId);
        if (msg == null || !sessionId.equals(msg.getSessionId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "消息不存在");
        }
        // fail-closed 校验租户归属——tenantId 不匹配或 msg.tenantId 为 null 时拒绝
        if (tenantId == null || !tenantId.equals(msg.getTenantId())) {
            log.warn("跨租户删除消息拦截（fail-closed）: sessionId={}, messageId={}, requestTenantId={}, ownerTenantId={}",
                    sessionId, messageId, tenantId, msg.getTenantId());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权删除该消息");
        }
        // 校验会话所有权，防跨用户删除
        if (userId != null) {
            Session session = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                    .eq(Session::getSessionId, sessionId)
                    .eq(Session::getTenantId, tenantId));
            if (session == null || (session.getUserId() != null && !userId.equals(session.getUserId()))) {
                log.warn("跨用户删除消息拦截: sessionId={}, messageId={}, requestUserId={}, ownerUserId={}",
                        sessionId, messageId, userId, session != null ? session.getUserId() : null);
                throw new BusinessException(ResultCode.FORBIDDEN, "无权删除该消息");
            }
        }
        if (msg.getMessageType() != MessageType.USER) {
            throw new BusinessException(ResultCode.CONFLICT, "仅允许删除用户消息");
        }
        sessionMessageMapper.deleteById(messageId);
        // 删除紧邻的 ASSISTANT 回复，按 seq > 当前消息的第一条 ASSISTANT 消息精确查询
        SessionMessage assistantMsg = sessionMessageMapper.selectOne(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .eq(SessionMessage::getTenantId, tenantId)
                        .gt(SessionMessage::getSeq, msg.getSeq())
                        .eq(SessionMessage::getMessageType, MessageType.ASSISTANT)
                        .orderByAsc(SessionMessage::getSeq)
                        .last("LIMIT 1"));
        int deletedCount = 1;
        long deletedTokens = msg.getTokenInput() != null ? msg.getTokenInput() : 0;
        if (assistantMsg != null) {
            sessionMessageMapper.deleteById(assistantMsg.getId());
            deletedCount++;
            deletedTokens += assistantMsg.getTokenInput() != null ? assistantMsg.getTokenInput() : 0;
            deletedTokens += assistantMsg.getTokenOutput() != null ? assistantMsg.getTokenOutput() : 0;
        }
        // P1-06: 回调 message_count 和 token_used
        sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                .eq(Session::getSessionId, sessionId)
                .setSql("message_count = GREATEST(message_count - " + deletedCount + ", 0)")
                .setSql("token_used = GREATEST(token_used - " + Math.max(0, deletedTokens) + ", 0)"));
    }

    /**
     * 任务 8：级联删除 —— 删除指定消息（含）及该会话中所有 seq 更大的消息。
     *
     * <p>用于重新生成（从 AI 消息或其前一条 USER 消息开始删）和消息编辑（从 USER 消息开始删）。
     * 包含 fail-closed 租户校验 + 会话所有权校验（防 IDOR，满足验收 #7）。
     *
     * @param sessionId 会话ID
     * @param messageId 起始消息ID（含，从该消息的 seq 开始向后级联）
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @return 被删除的消息数量（供单元测试断言级联删除计数，验收 #1）
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int cascadeDeleteFromMessage(String sessionId, Long messageId, Long tenantId, Long userId) {
        SessionMessage target = sessionMessageMapper.selectById(messageId);
        if (target == null || !sessionId.equals(target.getSessionId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "消息不存在");
        }
        if (tenantId == null || !tenantId.equals(target.getTenantId())) {
            log.warn("跨租户级联删除拦截（fail-closed）: sessionId={}, messageId={}, reqTenant={}, ownerTenant={}",
                    sessionId, messageId, tenantId, target.getTenantId());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该消息");
        }
        if (userId != null) {
            Session session = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                    .eq(Session::getSessionId, sessionId)
                    .eq(Session::getTenantId, tenantId));
            if (session == null || (session.getUserId() != null && !userId.equals(session.getUserId()))) {
                log.warn("跨用户级联删除拦截: sessionId={}, messageId={}, reqUser={}, ownerUser={}",
                        sessionId, messageId, userId, session != null ? session.getUserId() : null);
                throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该会话");
            }
        }
        List<SessionMessage> toDelete = sessionMessageMapper.selectList(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .eq(SessionMessage::getTenantId, tenantId)
                        .ge(SessionMessage::getSeq, target.getSeq()));
        int deletedCount = toDelete.size();
        long deletedTokens = toDelete.stream()
                .mapToLong(m -> (m.getTokenInput() != null ? m.getTokenInput() : 0)
                              + (m.getTokenOutput() != null ? m.getTokenOutput() : 0))
                .sum();
        sessionMessageMapper.delete(new LambdaQueryWrapper<SessionMessage>()
                .eq(SessionMessage::getSessionId, sessionId)
                .eq(SessionMessage::getTenantId, tenantId)
                .ge(SessionMessage::getSeq, target.getSeq()));
        if (deletedCount > 0) {
            sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                    .eq(Session::getSessionId, sessionId)
                    .setSql("message_count = GREATEST(message_count - " + deletedCount + ", 0)")
                    .setSql("token_used = GREATEST(token_used - " + Math.max(0, deletedTokens) + ", 0)"));
        }
        log.info("级联删除完成: sessionId={}, fromSeq={}, deletedCount={}, deletedTokens={}",
                sessionId, target.getSeq(), deletedCount, Math.max(0, deletedTokens));
        return deletedCount;
    }

    /**
     * 任务 8：重新生成准备 —— 找到 AI 消息的前一条 USER 消息，级联删除从该 USER 消息开始的所有消息，
     * 返回用户消息文本供重新执行（验收 #1：基于上一条 user 消息重新执行）。
     *
     * @param sessionId   会话ID
     * @param aiMessageId 要重新生成的 AI 消息ID
     * @param tenantId    租户ID
     * @param userId      用户ID
     * @return 前一条 USER 消息的文本内容
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public String prepareRegenerate(String sessionId, Long aiMessageId, Long tenantId, Long userId) {
        SessionMessage aiMsg;
        if (aiMessageId != null) {
            aiMsg = sessionMessageMapper.selectById(aiMessageId);
            if (aiMsg == null || !sessionId.equals(aiMsg.getSessionId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "消息不存在");
            }
            if (tenantId == null || !tenantId.equals(aiMsg.getTenantId())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该消息");
            }
        } else {
            aiMsg = sessionMessageMapper.selectOne(
                    new LambdaQueryWrapper<SessionMessage>()
                            .eq(SessionMessage::getSessionId, sessionId)
                            .eq(SessionMessage::getTenantId, tenantId)
                            .eq(SessionMessage::getMessageType, MessageType.ASSISTANT)
                            .orderByDesc(SessionMessage::getSeq)
                            .last("LIMIT 1"));
            if (aiMsg == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "未找到可重新生成的 AI 消息");
            }
        }
        if (aiMsg.getMessageType() != MessageType.ASSISTANT) {
            throw new BusinessException(ResultCode.CONFLICT, "仅允许重新生成 AI 消息");
        }
        SessionMessage userMsg = sessionMessageMapper.selectOne(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .eq(SessionMessage::getTenantId, tenantId)
                        .eq(SessionMessage::getMessageType, MessageType.USER)
                        .lt(SessionMessage::getSeq, aiMsg.getSeq())
                        .orderByDesc(SessionMessage::getSeq)
                        .last("LIMIT 1"));
        if (userMsg == null) {
            throw new BusinessException(ResultCode.CONFLICT, "无法重新生成：未找到对应的用户提问");
        }
        String userText = userMsg.getContent();
        cascadeDeleteFromMessage(sessionId, userMsg.getId(), tenantId, userId);
        log.info("重新生成准备完成: sessionId={}, deletedFromUserSeq={}, userTextLen={}",
                sessionId, userMsg.getSeq(), userText != null ? userText.length() : 0);
        return userText;
    }

    /**
     * 任务 8：编辑准备 —— 验证目标为 USER 消息，级联删除从该消息开始的所有消息（验收 #2）。
     * 新用户消息的持久化由 {@link com.aegis.runtime.service.agent.AgentAssemblyService#assemble} 负责。
     *
     * @param sessionId    会话ID
     * @param userMessageId 要编辑的用户消息ID（null 时取最后一条 USER 消息）
     * @param tenantId     租户ID
     * @param userId       用户ID
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void prepareEdit(String sessionId, Long userMessageId, Long tenantId, Long userId) {
        SessionMessage userMsg;
        if (userMessageId != null) {
            userMsg = sessionMessageMapper.selectById(userMessageId);
            if (userMsg == null || !sessionId.equals(userMsg.getSessionId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "消息不存在");
            }
            if (tenantId == null || !tenantId.equals(userMsg.getTenantId())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该消息");
            }
        } else {
            userMsg = sessionMessageMapper.selectOne(
                    new LambdaQueryWrapper<SessionMessage>()
                            .eq(SessionMessage::getSessionId, sessionId)
                            .eq(SessionMessage::getTenantId, tenantId)
                            .eq(SessionMessage::getMessageType, MessageType.USER)
                            .orderByDesc(SessionMessage::getSeq)
                            .last("LIMIT 1"));
            if (userMsg == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "未找到可编辑的用户消息");
            }
        }
        if (userMsg.getMessageType() != MessageType.USER) {
            throw new BusinessException(ResultCode.CONFLICT, "仅允许编辑用户消息");
        }
        cascadeDeleteFromMessage(sessionId, userMsg.getId(), tenantId, userId);
        log.info("编辑准备完成: sessionId={}, deletedFromUserSeq={}", sessionId, userMsg.getSeq());
    }

    /**
     * 会话过期归档定时任务（每 30 分钟执行）。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${aegis.runtime.session.archive-interval-ms:1800000}")
    public void archiveExpiredSessions() {
        try {
            int updated = sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                    .eq(Session::getStatus, SessionStatus.ENDED)
                    .lt(Session::getExpireTime, LocalDateTime.now())
                    .set(Session::getStatus, SessionStatus.EXPIRED));
            if (updated > 0) {
                log.info("P1-09: 会话过期归档: count={}", updated);
            }
        } catch (Exception e) {
            log.error("P1-09: 会话过期归档任务失败", e);
        }
    }

    /**
     * 锁定版本快照。
     */
    public void lockVersionSnapshot(String sessionId, Long agentId, String agentVersion,
                                    com.aegis.core.domain.agent.AgentConfig agentConfig,
                                    List<com.aegis.core.domain.agent.AgentBinding> bindings,
                                    com.aegis.core.enums.agent.GovernanceTier governanceTier) {
        JSONObject snapshot = new JSONObject();
        snapshot.put("agentId", agentId);
        snapshot.put("agentVersion", agentVersion);
        if (agentConfig != null) {
            JSONObject cfg = new JSONObject();
            cfg.put("systemPrompt", agentConfig.getSystemPrompt());
            cfg.put("modelTier", agentConfig.getModelTier() != null ? agentConfig.getModelTier().name() : "STANDARD");
            cfg.put("temperature", agentConfig.getTemperature());
            cfg.put("maxTurns", agentConfig.getMaxTurns());
            cfg.put("enabledTools", agentConfig.getEnabledTools());
            cfg.put("governanceTier", governanceTier != null ? governanceTier.name() : "STANDARD");
            snapshot.put("agentConfig", cfg);
        }
        if (bindings != null) {
            snapshot.put("bindings", JSON.parseArray(JSON.toJSONString(bindings)));
        }

        sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                .eq(Session::getSessionId, sessionId)
                .set(Session::getAgentVersion, agentVersion)
                .set(Session::getVersionSnapshot, snapshot.toJSONString()));
    }

    /**
     * 加载增强历史：session_summary 上下文 + 最近 K 轮原文消息。
     *
     * <p>调用方可将 {@code summaryContext} 拼入 system prompt，
     * 将 {@code messages} 作为最近 K 轮原文进入模型窗口，兼顾早期上下文理解与近期事实精度。</p>
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @param limit     最近 K 轮原文上限（实际传给底层 {@link #loadHistory}）
     * @return 增强历史快照
     */
    public EnhancedHistory loadEnhancedHistory(String sessionId, Long tenantId, int limit) {
        List<SessionMessage> messages = loadHistory(sessionId, tenantId, limit, null);
        String summary = sessionSummaryService.loadSummaryContext(sessionId, tenantId);
        return new EnhancedHistory(messages, summary);
    }

    /**
     * 增强历史值对象：原文消息 + 历史摘要上下文。
     *
     * @param messages       最近 K 轮原文消息（已按 seq 升序、TOOL_RESULT 已并入 TOOL_CALL）
     * @param summaryContext session_summary 拼接的早期上下文前缀；无摘要时为空串
     */
    public record EnhancedHistory(List<SessionMessage> messages, String summaryContext) {}

    /**
     * LLM 生成会话标题。
     *
     * <p>优先调用 LIGHT 档模型生成简洁标题（≤20 字）；
     * 若 LLM 不可用或返回空，降级为原截断方案（≤47 字 + "..."）。</p>
     *
     * @param firstUserMessage 首轮用户消息
     * @param sessionId        会话ID（用于日志）
     * @param tenantId         租户ID（用于定位 LIGHT 档模型）
     * @return 标题；空输入时返回 "新会话"
     */
    public String generateTitle(String firstUserMessage, String sessionId, Long tenantId) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) {
            return "新会话";
        }
        try {
            String systemPrompt = "你是一个标题生成助手。根据用户的首轮提问，生成一个简洁的会话标题（不超过20字），概括对话主题。只输出标题文字，不要标点。";
            String result = llmClientFactory.create(tenantId, ModelTier.LIGHT)
                    .chat(systemPrompt, firstUserMessage, 0.1f, 64, 3);
            if (result != null && !result.isBlank()) {
                String trimmed = result.trim().replaceAll("[\\p{Punct}]+$", "");
                return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
            }
        } catch (Exception e) {
            log.warn("LLM 生成标题失败，降级为截断: sessionId={}, error={}", sessionId, e.getMessage());
        }
        // 降级：原截断方案
        return firstUserMessage.length() <= 50
                ? firstUserMessage
                : firstUserMessage.substring(0, 47) + "...";
    }

    /**
     * 定时清理会话创建锁池中无争用的锁，防止长期运行内存泄漏。
     *
     * <p>P1-6：sessionMessageLocks 池已随 seq 改 Redis INCR 一并删除（消息持久化不再持 JVM 锁）；
     * 本任务仅清理 sessionCreateLocks。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${aegis.runtime.lock.cleanup-interval-ms:600000}")
    public void cleanupIdleLocks() {
        try {
            int createRemoved = cleanupLockPool(sessionCreateLocks);
            if (createRemoved > 0) {
                log.info("P1 补丁: 锁池清理完成, sessionCreateLocks -{}, remaining: create={}",
                        createRemoved, sessionCreateLocks.size());
            }
        } catch (Exception e) {
            log.debug("P1 补丁: 锁池清理任务异常", e);
        }
    }

    /**
     * 清理锁池中无争用的锁（未被持有且无等待线程）。
     *
     * @return 清理的锁数量
     */
    private int cleanupLockPool(ConcurrentHashMap<String, ReentrantLock> lockPool) {
        int[] removed = {0};
        lockPool.entrySet().removeIf(entry -> {
            ReentrantLock lock = entry.getValue();
            // 仅清理未被任何线程持有且无等待线程的锁
            try {
                if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                    removed[0]++;
                    return true;
                }
            } catch (Exception e) {
                // 安全起见，异常时保留锁
            }
            return false;
        });
        return removed[0];
    }

    /**
     * 将字符串安全转换为合法 JSON 字符串。
     *
     * <p>DB 字段 tool_params/tool_result 是 MySQL JSON 类型，必须写入合法 JSON。
     * 非法 JSON 字符串（如 "SUCCESS"）会导致 MySQL 报 Invalid JSON text 错误。
     *
     * <ul>
     *   <li>null → null（DB 字段允许 NULL）</li>
     *   <li>空字符串 → null</li>
     *   <li>已是合法 JSON → 原样返回</li>
     *   <li>非 JSON 字符串 → 包装为 JSON 字符串 {@code "value"}</li>
     * </ul>
     */
    private String toJsonSafe(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        // 先尝试解析，合法 JSON 原样返回
        try {
            JSON.parse(value);
            return value;
        } catch (Exception e) {
            // 非 JSON，包装为 JSON 字符串
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", value);
            return wrapper.toJSONString();
        }
    }
}
