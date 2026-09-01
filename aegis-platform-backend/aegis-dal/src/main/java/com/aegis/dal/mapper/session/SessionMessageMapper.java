package com.aegis.dal.mapper.session;

import com.aegis.core.domain.session.SessionMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 会话消息 Mapper。
 *
 * @author wang.zhen
 * 
 */
@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessage> {

    /**
     * 悲观锁获取指定会话的最大 seq 值。
     *
     * <p>使用 {@code FOR UPDATE} 锁定该会话最新一条消息的行，
     * 防止并发事务在 REPEATABLE READ 隔离级别下读到相同的 MAX(seq) 值，
     * 导致唯一索引 uk_session_msg_seq 冲突。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @return 当前最大 seq 值；若会话尚无消息返回 0
     */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM sess_message WHERE session_id = #{sessionId} AND tenant_id = #{tenantId} AND deleted = 0 FOR UPDATE")
    int selectMaxSeqForUpdate(@Param("sessionId") String sessionId, @Param("tenantId") Long tenantId);
}
