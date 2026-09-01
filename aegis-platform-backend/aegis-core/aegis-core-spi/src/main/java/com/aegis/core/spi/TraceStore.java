package com.aegis.core.spi;

import com.aegis.core.common.web.PageRequest;
import com.aegis.core.common.web.PageResult;
import com.aegis.core.dto.observe.ObserveStats;
import com.aegis.core.dto.observe.RoundDetail;
import com.aegis.core.dto.observe.SessionDetailResponse;
import com.aegis.core.dto.observe.SessionSummary;
import com.aegis.core.dto.observe.SpanRecord;
import com.aegis.core.dto.observe.TraceDetail;
import com.aegis.core.dto.observe.TraceQuery;
import com.aegis.core.dto.observe.TraceRecord;
import com.aegis.core.dto.observe.StatsQuery;

import java.util.List;

/**
 * 链路追踪存储协议。
 *
 * <p>抽象可观测模块的链路数据存储统一协议，屏蔽底层实现差异（MySQL / ClickHouse）。
 * 支持链路批量写入、条件查询、详情查看、多维统计与过期清理。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>存储抽象：通过 {@link #storeType()} 标识实现类型，支持按配置切换</li>
 *   <li>批量写入：{@link #saveBatch(List, List)} 支持链路与 Span 批量落库，降低 IO 开销</li>
 *   <li>多维查询：支持按 TraceId / SessionId / UserId / AgentId 等维度检索</li>
 *   <li>统计聚合：支持按用户/智能体/会话维度的用量与成功率统计</li>
 *   <li>生命周期：默认实现过期清理空操作，由具体实现按需覆盖</li>
 * </ul>
 *
 *  @author wang.zhen
 */
public interface TraceStore {

    /**
     * 存储类型标识。
     *
     * @return 存储类型（如 mysql / clickhouse）
     */
    String storeType();

    /**
     * 批量保存链路与 Span 记录。
     *
     * @param traces 链路记录列表
     * @param spans  Span 记录列表
     */
    void saveBatch(List<TraceRecord> traces, List<SpanRecord> spans);

    /**
     * 按条件分页查询链路列表。
     *
     * @param query 查询条件
     * @return 分页链路记录
     */
    PageResult<TraceRecord> queryTraces(TraceQuery query);

    /**
     * 查询链路详情（含完整 Span 树）。
     *
     * @param traceId 链路ID
     * @return 链路详情
     */
    TraceDetail getTraceDetail(String traceId);

    /**
     * 查询会话详情（含所有轮次和步骤）。
     *
     * <p>以 Session 为根节点，聚合所有 Trace 为 Round，每个 Round 包含多个 Step。</p>
     *
     * @param sessionId 会话ID
     * @return 会话详情
     */
    SessionDetailResponse getSessionDetail(String sessionId);

    /**
     * 按会话ID查询所有 Span。
     *
     * <p>用于会话级聚合计算。</p>
     *
     * @param sessionId 会话ID
     * @return Span 列表
     */
    List<SpanRecord> listSpansBySession(String sessionId);

    /**
     * 按会话ID分页查询链路。
     *
     * @param sessionId 会话ID
     * @param page      分页参数
     * @return 分页链路记录
     */
    PageResult<TraceRecord> queryBySession(String sessionId, PageRequest page);

    /**
     * 按用户ID分页查询链路。
     *
     * @param userId 用户ID
     * @param page   分页参数
     * @return 分页链路记录
     */
    PageResult<TraceRecord> queryByUser(Long userId, PageRequest page);

    /**
     * 按智能体ID分页查询链路。
     *
     * @param agentId 智能体ID
     * @param page    分页参数
     * @return 分页链路记录
     */
    PageResult<TraceRecord> queryByAgent(Long agentId, PageRequest page);

    /**
     * 按会话维度分页聚合查询。
     *
     * <p>将同一 sessionId 下的多条 Trace 聚合为一条 {@link SessionSummary}，
     * 展示会话级的成功/失败数、总耗时、总 Token 与总成本等汇总指标。</p>
     *
     * @param page 分页参数
     * @return 会话级聚合分页结果
     */
    PageResult<SessionSummary> querySessions(PageRequest page);

    /**
     * 按条件统计可观测指标。
     *
     * @param query 统计查询条件
     * @return 统计结果
     */
    ObserveStats stats(StatsQuery query);

    /**
     * 清理过期链路数据。
     *
     * <p>默认空实现，由具体存储实现按需覆盖。</p>
     *
     * @param retentionDays 保留天数
     */
    default void cleanExpired(int retentionDays) {
    }
}