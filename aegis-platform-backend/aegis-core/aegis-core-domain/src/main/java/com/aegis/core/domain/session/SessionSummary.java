package com.aegis.core.domain.session;

import com.aegis.core.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 会话渐进式摘要实体。
 *
 * <p>每累计 N 轮对话（默认 10 轮）由应用层异步生成一段摘要文本，
 * 覆盖指定的 seq 范围，形成多条按时间递增的历史摘要序列。
 * LLM 主对话在长上下文场景下会优先加载这些摘要作为早期上下文前缀，
 * 再拼接最近 K 轮原文消息进入模型窗口。</p>
 *
 * <h3>触发与存储</h3>
 * <ul>
 *   <li>触发：由 {@code AegisMemoryMiddleware#doFinally} 异步判断并 fire-and-forget 调用</li>
 *   <li>存储：LIGHT 档 LLM 生成，写入本表；LLM 失败降级为不生成（不阻塞主流程）</li>
 * </ul>
 *
 * @author wang.zhen
 * @see TenantEntity
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("res_session_summary")
public class SessionSummary extends TenantEntity {
    /** 会话ID */
    private String sessionId;
    /** 轮次范围起始 seq（含） */
    private Integer seqStart;
    /** 轮次范围结束 seq（含） */
    private Integer seqEnd;
    /** 摘要文本 */
    private String summaryText;
    /** 摘要 token 数（估算） */
    private Integer tokenCount;
}
