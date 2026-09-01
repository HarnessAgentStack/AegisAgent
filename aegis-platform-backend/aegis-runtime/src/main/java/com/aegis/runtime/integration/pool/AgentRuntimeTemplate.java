package com.aegis.runtime.integration.pool;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 智能体运行时模板（Layer 1 池化对象）。
 *
 * <p>由 {@code AgentPoolManager} 缓存复用，{@code TaskExecutionService} 从本模板派生 Layer 2
 * 会话级执行上下文。模板不可变，多会话共享，初始化后只读。
 *
 * <h3>两级池化定位</h3>
 * <ul>
 *   <li>Layer 1（本类）：运行时模板，跨会话复用，Caffeine 缓存管理</li>
 *   <li>Layer 2：会话级执行上下文（AegisTaskContext），从模板派生，绑定 sessionId/沙箱</li>
 * </ul>
 *
 * <h3>缓存键</h3>
 * <p>缓存 Key = (agentId, version, tenantId)，版本变更触发新模板加载。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuntimeTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体ID */
    private Long agentId;

    /** 智能体版本号 */
    private String version;

    /** 租户ID */
    private Long tenantId;

    /** 智能体定义 */
    private AgentDef agentDef;

    /** 智能体配置 */
    private AgentConfig agentConfig;

    /** 资源绑定列表 */
    private List<AgentBinding> bindings;

    /** 工具注册表（toolId -> 工具元数据），第二阶段为简化占位 */
    private Map<String, Object> toolRegistry;

    /** 技能引用列表（skillId 列表） */
    private List<Long> skillRefs;

    /** 模型路由配置（modelTier -> 模型ID/Endpoint） */
    private Map<String, String> modelRoutes;

    /** 模板创建时间戳 */
    private long createdAt;

    /** 最近访问时间戳（用于空闲回收判定） */
    private final AtomicLong lastAccessedAt = new AtomicLong();

    /**
     * 更新最近访问时间。
     */
    public void touch() {
        lastAccessedAt.set(System.currentTimeMillis());
    }

    /**
     * 获取最近访问时间。
     */
    public long getLastAccessedAt() {
        return lastAccessedAt.get();
    }

    /**
     * 缓存键。
     */
    public String cacheKey() {
        return agentId + ":" + version + ":" + tenantId;
    }
}
