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
 * HITL（Human-in-the-Loop）审批流程的 Redis 状态管理服务。
 *
 * <p>在工具调用被挂起等待人工审批、以及审批通过后恢复执行的过程中，
 * 通过 Redis 保存中间状态，实现跨请求的状态传递。使用两个 Key：
 * <ul>
 *   <li>{@code aegis:hitl:req:{sessionId}} — 待审批请求数据（replyId + 工具调用列表，JSON）</li>
 *   <li>{@code aegis:hitl:approved:{sessionId}} — 审批通过标记，恢复执行时据此读取结果</li>
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
     * 收到 hitl.request 事件时，保存待审批数据（replyId + 工具调用列表）到 Redis。
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
     * 用户审批通过后，读取待审批请求并构造 ConfirmResult 列表，
     * 同时在 Redis 写入审批通过标记。
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
     * 构造包含 ConfirmResult 的恢复消息，供 AgentScope 恢复被中断的工具调用。
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
     * <p>HITL 恢复时标记已审批工具，使第二轮执行中遇到相同工具直接放行，
     * 避免对通配符审批规则重复触发人工确认。
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
     * 清理已消费的审批状态（请求 + 审批标记）。
     * 在 HITL 恢复流程正常完成后调用。
     */
    public void clearHitlState(String sessionId) {
        redisTemplate.delete(REQUEST_KEY_PREFIX + sessionId);
        redisTemplate.delete(APPROVED_KEY_PREFIX + sessionId);
        log.info("HITL state cleared: sessionId={}", sessionId);
    }

    /**
     * 是否已审批通过（Redis 中存在审批标记）。
     */
    public boolean isApproved(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(APPROVED_KEY_PREFIX + sessionId));
    }

    /**
     * 是否存在待审批请求（已保存但尚未审批）。
     * 用于审批接口容错：会话状态因竞态变为 ENDED 时，
     * 只要 Redis 中仍有待审批请求，允许用户继续审批。
     */
    public boolean hasPendingRequest(String sessionId) {
        String reqKey = REQUEST_KEY_PREFIX + sessionId;
        Boolean hasKey = redisTemplate.hasKey(reqKey);
        return Boolean.TRUE.equals(hasKey);
    }
}
