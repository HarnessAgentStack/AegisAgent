package com.aegis.runtime.service.policy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HITL 流程服务：管理 HITL 审批的全链路状态。
 *
 * <p>使用 Redis 存储 HITL 请求与审批结果，实现跨请求的状态传递：
 * <ul>
 *   <li>{@link #saveHitlRequest} - 保存 HITL 请求（含 pending toolCalls 和 replyId）</li>
 *   <li>{@link #getPendingConfirmResults} - 获取审批通过后的 ConfirmResult 列表</li>
 *   <li>{@link #clearHitlState} - 清理 HITL 状态</li>
 * </ul>
 *
 * <p>Key 结构：
 * <ul>
 *   <li>{@code aegis:hitl:req:{sessionId}} - HITL 请求数据（JSON）</li>
 *   <li>{@code aegis:hitl:approved:{sessionId}} - 审批结果标记</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlFlowService {

    private static final String REQUEST_KEY_PREFIX = "aegis:hitl:req:";
    private static final String APPROVED_KEY_PREFIX = "aegis:hitl:approved:";
    private static final long REQUEST_TTL_SECONDS = 172800;  // 48h
    private static final long APPROVED_TTL_SECONDS = 3600;   // 1h

    private final StringRedisTemplate redisTemplate;

    /**
     * 保存 HITL 请求数据（在收到 hitl.request 事件时调用）。
     */
    public void saveHitlRequest(String sessionId, String replyId, List<Map<String, Object>> toolCalls) {
        JSONObject req = new JSONObject();
        req.put("replyId", replyId);
        req.put("toolCalls", toolCalls);
        req.put("timestamp", System.currentTimeMillis());

        String key = REQUEST_KEY_PREFIX + sessionId;
        redisTemplate.opsForValue().set(key, req.toJSONString(), REQUEST_TTL_SECONDS, TimeUnit.SECONDS);
        log.info("HITL request saved: sessionId={}, replyId={}, toolCallCount={}",
                sessionId, replyId, toolCalls != null ? toolCalls.size() : 0);
    }

    /**
     * 标记 HITL 为已审批通过，并构造 ConfirmResult 列表。
     */
    public List<ConfirmResult> markApproved(String sessionId) {
        String reqKey = REQUEST_KEY_PREFIX + sessionId;
        String reqJson = redisTemplate.opsForValue().get(reqKey);
        if (reqJson == null) {
            log.warn("No HITL request found for session: {}", sessionId);
            return Collections.emptyList();
        }

        JSONObject req = JSON.parseObject(reqJson);
        String replyId = req.getString("replyId");
        JSONArray toolCalls = req.getJSONArray("toolCalls");

        List<ConfirmResult> results = new ArrayList<>();
        if (toolCalls != null) {
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String toolName = tc.getString("name");
                String toolCallId = tc.getString("id");
                JSONObject input = tc.getJSONObject("input");

                if (toolCallId == null || toolCallId.isBlank()) {
                    continue;
                }

                Map<String, Object> inputMap = input != null ? new HashMap<>(input) : Collections.emptyMap();
                ToolUseBlock toolUseBlock = new ToolUseBlock(toolCallId, toolName, inputMap);

                ConfirmResult cr = new ConfirmResult(true, toolUseBlock);
                results.add(cr);
            }
        }

        // 标记为已审批
        String approvedKey = APPROVED_KEY_PREFIX + sessionId;
        redisTemplate.opsForValue().set(approvedKey, replyId != null ? replyId : "approved",
                APPROVED_TTL_SECONDS, TimeUnit.SECONDS);

        log.info("HITL approved: sessionId={}, replyId={}, confirmResultCount={}",
                sessionId, replyId, results.size());
        return results;
    }

    /**
     * 构造包含 ConfirmResult 元数据的 Msg 列表（用于 HITL 恢复场景）。
     */
    public List<Msg> buildResumeMessages(String sessionId) {
        List<ConfirmResult> confirmResults = loadConfirmResults(sessionId);
        if (confirmResults.isEmpty()) {
            log.info("HITL resume: no pending confirm results, sending empty msg list: sessionId={}", sessionId);
            return List.of();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
        String replyId = loadReplyId(sessionId);
        if (replyId != null) {
            metadata.put(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, replyId);
        }

        Msg resumeMsg = Msg.builder()
                .role(io.agentscope.core.message.MsgRole.TOOL)
                .metadata(metadata)
                .build();

        log.info("HITL resume: injecting {} confirm results: sessionId={}",
                confirmResults.size(), sessionId);
        return List.of(resumeMsg);
    }

    private List<ConfirmResult> loadConfirmResults(String sessionId) {
        String approvedKey = APPROVED_KEY_PREFIX + sessionId;
        String approved = redisTemplate.opsForValue().get(approvedKey);
        if (approved == null) {
            return Collections.emptyList();
        }

        String reqKey = REQUEST_KEY_PREFIX + sessionId;
        String reqJson = redisTemplate.opsForValue().get(reqKey);
        if (reqJson == null) {
            return Collections.emptyList();
        }

        JSONObject req = JSON.parseObject(reqJson);
        JSONArray toolCalls = req.getJSONArray("toolCalls");
        List<ConfirmResult> results = new ArrayList<>();
        if (toolCalls != null) {
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String toolCallId = tc.getString("id");
                if (toolCallId == null || toolCallId.isBlank()) continue;

                JSONObject input = tc.getJSONObject("input");
                Map<String, Object> inputMap = input != null ? new HashMap<>(input) : Collections.emptyMap();

                ToolUseBlock block = new ToolUseBlock(
                        toolCallId,
                        tc.getString("name"),
                        inputMap);
                results.add(new ConfirmResult(true, block));
            }
        }
        return results;
    }

    private String loadReplyId(String sessionId) {
        String reqKey = REQUEST_KEY_PREFIX + sessionId;
        String reqJson = redisTemplate.opsForValue().get(reqKey);
        if (reqJson == null) return null;
        return JSON.parseObject(reqJson).getString("replyId");
    }

    /**
     * 获取已审批通过的工具名列表。
     *
     * <p>供 {@code TaskExecutionService} 在 HITL 恢复(resume)路径标记
     * {@code AegisTaskContext.approvedTools}，使第二轮 {@code onActing} 中间件
     * 通过 {@code isToolApproved} 直接放行，避免对通配符 HITL 规则重复触发 ASK
     * （AS ConfirmResult 规则学习只覆盖明确 toolName 的 ASK 规则，不覆盖通配符规则）。
     *
     * @param sessionId 会话ID
     * @return 已审批工具名列表（无则返回空列表）
     */
    public List<String> listApprovedToolNames(String sessionId) {
        String approvedKey = APPROVED_KEY_PREFIX + sessionId;
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(approvedKey))) {
            return Collections.emptyList();
        }
        String reqKey = REQUEST_KEY_PREFIX + sessionId;
        String reqJson = redisTemplate.opsForValue().get(reqKey);
        if (reqJson == null) {
            return Collections.emptyList();
        }
        JSONObject req = JSON.parseObject(reqJson);
        JSONArray toolCalls = req.getJSONArray("toolCalls");
        List<String> names = new ArrayList<>();
        if (toolCalls != null) {
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String name = tc.getString("name");
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * 清理 HITL 相关状态。
     */
    public void clearHitlState(String sessionId) {
        redisTemplate.delete(REQUEST_KEY_PREFIX + sessionId);
        redisTemplate.delete(APPROVED_KEY_PREFIX + sessionId);
        log.info("HITL state cleared: sessionId={}", sessionId);
    }

    /**
     * 检查是否已审批通过。
     */
    public boolean isApproved(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(APPROVED_KEY_PREFIX + sessionId));
    }

    /**
     * 检查是否存在未审批的 HITL 请求（请求已保存但尚未审批）。
     *
     * <p>用于审批接口的容错场景：当会话状态因竞态变为 ENDED 时，
     * 只要 Redis 中仍存在待审批请求，允许用户恢复审批。
     */
    public boolean hasPendingRequest(String sessionId) {
        String reqKey = REQUEST_KEY_PREFIX + sessionId;
        Boolean hasKey = redisTemplate.hasKey(reqKey);
        return Boolean.TRUE.equals(hasKey);
    }
}
