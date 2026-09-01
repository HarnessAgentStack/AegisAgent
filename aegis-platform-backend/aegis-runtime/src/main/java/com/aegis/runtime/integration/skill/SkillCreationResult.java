package com.aegis.runtime.integration.skill;

import com.aegis.core.dto.agent.AgentEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 技能创建结果（封装执行结果和事件列表）。
 *
 * <p>用于在 Mono 链路中传递编排器的执行结果和 SSE 事件列表，
 * 避免通过 ThreadLocal 或中间缓存传递数据。
 *
 * @param data   执行结果数据（包含 skillId、skillName 等字段）
 * @param events SSE 事件列表（skill.creator.stage、skill.draft.created 等）
 */
public record SkillCreationResult(
    Map<String, Object> data,
    List<AgentEvent> events
) {

    /**
     * 创建成功结果。
     */
    public static SkillCreationResult success(Map<String, Object> data, List<AgentEvent> events) {
        return new SkillCreationResult(data, events != null ? events : Collections.emptyList());
    }

    /**
     * 创建失败结果。
     */
    public static SkillCreationResult error(String errorMessage, List<AgentEvent> events) {
        Map<String, Object> errorData = Map.of(
            "success", false,
            "message", errorMessage,
            "error", errorMessage
        );
        return new SkillCreationResult(errorData, events != null ? events : Collections.emptyList());
    }

    /**
     * 判断是否成功。
     */
    public boolean isSuccess() {
        return data != null && Boolean.TRUE.equals(data.get("success"));
    }

    /**
     * 获取 skillId。
     */
    public Long getSkillId() {
        if (data == null) return null;
        Object sid = data.get("skillId");
        if (sid instanceof Number n) return n.longValue();
        if (sid instanceof String s) {
            try { return Long.parseLong(s); } catch (Exception e) { return null; }
        }
        return null;
    }
}