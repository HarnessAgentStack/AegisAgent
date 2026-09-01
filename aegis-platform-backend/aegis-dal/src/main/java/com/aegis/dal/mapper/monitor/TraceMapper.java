package com.aegis.dal.mapper.monitor;

import com.aegis.core.domain.monitor.TraceEntity;
import com.aegis.core.dto.observe.SessionSummary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TraceMapper extends BaseMapper<TraceEntity> {

    @Select("""
        SELECT session_id AS sessionId,
               MAX(agent_id) AS agentId,
               MAX(agent_name) AS agentName,
               MAX(user_id) AS userId,
               MAX(user_name) AS userName,
               COUNT(*) AS traceCount,
               SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount,
               SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failCount,
               SUM(duration_ms) AS totalDurationMs,
               SUM(token_input + token_output) AS totalTokens,
               MAX(start_time) AS lastActiveTime
        FROM mon_trace
        WHERE tenant_id = #{tenantId}
        GROUP BY session_id
        HAVING session_id IS NOT NULL AND session_id != ''
        ORDER BY lastActiveTime DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<SessionSummary> selectSessionSummary(@Param("tenantId") Long tenantId,
                                              @Param("offset") long offset,
                                              @Param("limit") int limit);

    @Select("""
        SELECT COUNT(DISTINCT session_id)
        FROM mon_trace
        WHERE tenant_id = #{tenantId} AND session_id IS NOT NULL AND session_id != ''
    """)
    long countSessions(@Param("tenantId") Long tenantId);
}