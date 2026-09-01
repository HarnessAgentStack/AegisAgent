package com.aegis.dal.mapper.sandbox;

import com.aegis.core.domain.sandbox.SandboxOperationLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 沙箱操作日志 Mapper。
 *
 * <p>提供沙箱操作审计日志的持久化查询能力，用于操作历史追溯和问题排查。</p>
 *
 * @author wang.zhen
 */
@Mapper
public interface SandboxOperationLogMapper extends BaseMapper<SandboxOperationLog> {

    /**
     * 按实例 ID 查询操作日志（按时间倒序）。
     *
     * @param instanceId 实例 ID
     * @param limit      最大返回条数
     * @return 操作日志列表
     */
    @Select("SELECT * FROM sbx_operation_log " +
            "WHERE deleted = 0 AND instance_id = #{instanceId} " +
            "ORDER BY create_time DESC LIMIT #{limit}")
    List<SandboxOperationLog> selectByInstanceId(@Param("instanceId") String instanceId,
                                                  @Param("limit") int limit);

    /**
     * 按租户查询操作日志（按时间倒序）。
     *
     * @param tenantId 租户 ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 操作日志列表
     */
    @Select("SELECT * FROM sbx_operation_log " +
            "WHERE deleted = 0 AND tenant_id = #{tenantId} " +
            "AND create_time >= #{startTime} AND create_time <= #{endTime} " +
            "ORDER BY create_time DESC")
    List<SandboxOperationLog> selectByTenantAndTimeRange(@Param("tenantId") Long tenantId,
                                                          @Param("startTime") LocalDateTime startTime,
                                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 按操作类型统计数量。
     *
     * @param operationType 操作类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 操作次数
     */
    @Select("SELECT COUNT(*) FROM sbx_operation_log " +
            "WHERE deleted = 0 AND operation_type = #{operationType} " +
            "AND create_time >= #{startTime} AND create_time <= #{endTime}")
    long countByOperationType(@Param("operationType") String operationType,
                               @Param("startTime") LocalDateTime startTime,
                               @Param("endTime") LocalDateTime endTime);
}
