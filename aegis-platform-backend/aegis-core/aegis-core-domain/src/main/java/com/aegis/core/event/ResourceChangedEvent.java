package com.aegis.core.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.aegis.core.enums.resource.ResourceType;

/**
 * 资源变更事件。
 *
 * <p>当智能体定义、技能、知识库、MCP 服务、工具等池化资源发生变更时由管理平面发布，
 * 运行平面订阅后统一失效本地池化对象（Layer 1 智能体运行时模板、缓存等），
 * 实现配置的动态热更新而无需重启实例。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>变更类型（changeType）：CREATE / UPDATE / DELETE，DELETE 时仅需 objectId + category</li>
 *   <li>版本号（version）用于乐观失效：仅当本地版本低于事件版本时才执行失效</li>
 *   <li>租户隔离：tenantId 为 null 表示平台级全局变更（如平台模型供应商）</li>
 *   <li>事件通过消息队列广播，消费方需保证幂等</li>
 * </ul>
 *
 * <h3>典型场景</h3>
 * <ul>
 *   <li>管理后台发布新版本智能体 → 运行平面淘汰旧版运行时模板</li>
 *   <li>知识库文档更新 → 运行平面失效对应向量索引缓存</li>
 * </ul>
 *
 * @author wang.zhen
 * @see com.aegis.core.enums.resource.ResourceType
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceChangedEvent {

    /** 事件产生时间戳（毫秒），用于事件排序与过期清理 */
    private long timestamp;

    /** 变更对象主键ID，对应各资源实体的雪花ID */
    private Long objectId;

    /** 对象版本号，单调递增，用于乐观失效判断 */
    private Long version;

    /** 租户ID，null 表示平台级全局变更 */
    private Long tenantId;

    /** 资源类别，对应 {@link com.aegis.core.enums.resource.ResourceType}（AGENT/SKILL/KB/MCP/TOOL 等） */
    private String category;

    /** 变更类型：CREATE / UPDATE / DELETE */
    private String changeType;
}
