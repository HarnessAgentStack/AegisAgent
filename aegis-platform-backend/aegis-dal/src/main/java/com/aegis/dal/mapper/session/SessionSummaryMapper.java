package com.aegis.dal.mapper.session;

import com.aegis.core.domain.session.SessionSummary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话摘要 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface SessionSummaryMapper extends BaseMapper<SessionSummary> {

    /**
     * 按会话查询所有摘要，按 seq_start 升序。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @return 摘要列表（按时间递增），无数据时返回空列表
     */
    @Select("SELECT * FROM res_session_summary WHERE session_id = #{sessionId} AND tenant_id = #{tenantId} AND deleted = 0 ORDER BY seq_start ASC")
    List<SessionSummary> findBySession(@Param("sessionId") String sessionId, @Param("tenantId") Long tenantId);

    /**
     * 查询最后一个摘要的 seq_end（用于判断是否需要新摘要）。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @return 最后一个摘要的 seq_end；若会话尚无摘要返回 null
     */
    @Select("SELECT MAX(seq_end) FROM res_session_summary WHERE session_id = #{sessionId} AND tenant_id = #{tenantId} AND deleted = 0")
    Integer findMaxSeqEnd(@Param("sessionId") String sessionId, @Param("tenantId") Long tenantId);
}
