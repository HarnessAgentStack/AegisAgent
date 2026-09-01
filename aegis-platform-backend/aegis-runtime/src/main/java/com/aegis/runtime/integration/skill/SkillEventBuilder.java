package com.aegis.runtime.integration.skill;

import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能事件构建器：构建 SSE 事件载荷。
 *
 * <p>为 skill_creator 的各阶段事件提供统一的事件格式构建。</p>
 *
 * @author wang.zhen
 */
@Component
public class SkillEventBuilder {

    /**
     * 构建 skill.creator.stage 事件（创建阶段进度）。
     */
    public String buildStageEvent(String stage, String description, int progress) {
        return buildStageEvent(stage, description, progress, null);
    }

    /**
     * 构建 skill.creator.stage 事件（创建阶段进度，携带 skillId）。
     */
    public String buildStageEvent(String stage, String description, int progress, Long skillId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "skill.creator.stage");
        payload.put("stage", stage);
        payload.put("description", description);
        payload.put("progress", progress);
        if (skillId != null) {
            payload.put("skillId", String.valueOf(skillId));
        }
        return JSON.toJSONString(payload);
    }

    /**
     * 构建 skill.creator.debug 事件（调试结果）。
     */
    public String buildDebugEvent(String skillCode, boolean success, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "skill.creator.debug");
        payload.put("skillCode", skillCode);
        payload.put("success", success);
        payload.put("message", message);
        return JSON.toJSONString(payload);
    }

    /**
     * 构建 skill.creator.package 事件（打包完成）。
     */
    public String buildPackageEvent(String skillCode, String fileName, long size, boolean success) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "skill.creator.package");
        payload.put("skillCode", skillCode);
        payload.put("fileName", fileName);
        payload.put("size", size);
        payload.put("success", success);
        return JSON.toJSONString(payload);
    }

    /**
     * 构建 skill.activated 事件。
     */
    public String buildActivatedEvent(String... skillCodes) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "skill.activated");
        payload.put("codes", Arrays.asList(skillCodes));
        return JSON.toJSONString(payload);
    }

    /**
     * 构建 skill.rejected 事件。
     */
    public String buildRejectedEvent(String... skillCodes) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "skill.rejected");
        payload.put("codes", Arrays.asList(skillCodes));
        return JSON.toJSONString(payload);
    }
}