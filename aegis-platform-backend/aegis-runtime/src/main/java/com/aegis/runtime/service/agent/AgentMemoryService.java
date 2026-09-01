package com.aegis.runtime.service.agent;

import com.aegis.core.domain.agent.AgentMemory;
import com.aegis.core.enums.agent.MemoryType;
import com.aegis.dal.mapper.workspace.AgentMemoryMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 智能体记忆服务。
 *
 * <p>管理跨会话持久化的用户上下文记忆，支持用户画像、任务摘要与关键事实三分类。
 * 在会话中自动提取并持久化，跨会话检索复用以提升交互个性化与上下文连续性。
 *
 * @author wang.zhen
 * @see AgentMemory
 * @see MemoryType
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    private final AgentMemoryMapper agentMemoryMapper;

    /**
     * 存储记忆条目（Upsert 语义，P1-13 修复）。
     *
     * <p>使用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 原子操作，
     * 按 (tenantId, agentId, userId, memoryType, memoryKey) 唯一键判断：
     * 存在则更新 memoryValue，不存在则插入新记录。
     * 彻底解决并发场景下 selectOne + insert 的竞态条件导致的重复插入报错。
     *
     * @param agentId     智能体ID
     * @param userId      用户ID
     * @param memoryType  记忆类型
     * @param memoryKey   记忆键
     * @param memoryValue 记忆值
     * @param tenantId    租户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void storeMemory(Long agentId, Long userId, MemoryType memoryType,
                            String memoryKey, String memoryValue, Long tenantId) {
        // memory_value 列为 MySQL JSON 类型，需确保存储的是合法 JSON
        String jsonValue = toJsonValue(memoryValue);
        Long id = IdWorker.getId();

        int rows = agentMemoryMapper.insertOrUpdate(
                id, tenantId, agentId, userId,
                memoryType.name(), memoryKey, jsonValue,
                true, "auto", userId);

        if (rows > 0) {
            log.debug("Memory upserted: agentId={}, userId={}, type={}, key={}",
                    agentId, userId, memoryType, memoryKey);
        }
    }

    /**
     * 将任意文本包装为合法的 JSON 字符串值，适配 MySQL JSON 类型列。
     *
     * @param rawValue 原始文本
     * @return JSON 字符串字面量（如 {@code "\"hello\""}）
     */
    private String toJsonValue(String rawValue) {
        if (rawValue == null) {
            return JSON.toJSONString("");
        }
        return JSON.toJSONString(rawValue);
    }

    /**
     * 检索相关记忆。
     *
     * <p>按 agentId + userId 查询，按更新时间倒序，限制 topK 条。
     *
     * @param agentId  智能体ID
     * @param userId   用户ID
     * @param query    查询文本（当前未用于语义匹配，预留扩展）
     * @param topK     返回条数上限
     * @param tenantId 租户ID
     * @return 记忆列表
     */
    public List<AgentMemory> retrieveMemories(Long agentId, Long userId, String query,
                                               int topK, Long tenantId) {
        // P0 SEC-02 修复：fail-closed，tenantId 缺失时返回空列表
        if (tenantId == null || tenantId <= 0) {
            log.warn("retrieveMemories 拒绝（缺 tenantId）: agentId={}, userId={}", agentId, userId);
            return java.util.Collections.emptyList();
        }
        // P0 SEC-02 修复：增加 tenantId 过滤条件，防止跨租户记忆泄露
        // P1 PER-07 修复：query 非空时增加对 memoryValue 的 LIKE 关键词匹配（降级方案，无向量检索）
        return agentMemoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getTenantId, tenantId)
                .eq(AgentMemory::getAgentId, agentId)
                .eq(AgentMemory::getUserId, userId)
                .like(query != null && !query.isEmpty(), AgentMemory::getMemoryValue, query)
                .orderByDesc(AgentMemory::getUpdateTime)
                .last("LIMIT " + Math.max(topK, 1)));
    }

    /**
     * 从对话轮次中提取并存储关键事实。
     *
     * <p>简化实现：从用户消息和助手回复中提取关键信息作为 KEY_FACT 存储。
     * 完整实现应调用 LLM 做结构化提取。
     *
     * @param agentId          智能体ID
     * @param userId           用户ID
     * @param userMessage      用户消息
     * @param assistantMessage 助手回复
     * @param tenantId         租户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void extractAndStore(Long agentId, Long userId, String userMessage,
                                String assistantMessage, Long tenantId) {
        // 简化提取策略：存储最近一轮对话摘要
        String summaryKey = "fact.last_turn";
        String summaryValue = truncate(userMessage, 200) + " → " + truncate(assistantMessage, 200);
        storeMemory(agentId, userId, MemoryType.KEY_FACT, summaryKey, summaryValue, tenantId);

        // 若用户消息较短且包含偏好关键词，尝试提取为用户画像
        if (userMessage != null && userMessage.length() < 100) {
            String lowerMsg = userMessage.toLowerCase();
            if (lowerMsg.contains("我喜欢") || lowerMsg.contains("我偏好") || lowerMsg.contains("我希望")) {
                storeMemory(agentId, userId, MemoryType.USER_PROFILE, "profile.preference",
                        truncate(userMessage, 500), tenantId);
            }
        }

        log.debug("Memory extracted: agentId={}, userId={}", agentId, userId);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
