package com.aegis.dal.mapper.sandbox;

import com.aegis.core.domain.sandbox.SandboxLease;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SandboxLeaseMapper extends BaseMapper<SandboxLease> {

    @Select("SELECT * FROM sbx_lease WHERE lease_id = #{leaseId}")
    SandboxLease selectByLeaseId(@Param("leaseId") String leaseId);

    @Select("SELECT * FROM sbx_lease WHERE instance_id = #{instanceId} AND status = 'ACTIVE'")
    List<SandboxLease> selectActiveByInstanceId(@Param("instanceId") String instanceId);

    @Select("SELECT * FROM sbx_lease WHERE slot_key = #{slotKey} AND status = 'ACTIVE'")
    List<SandboxLease> selectActiveBySlotKey(@Param("slotKey") String slotKey);

    @Select("SELECT * FROM sbx_lease WHERE status = 'ACTIVE' AND expire_at < #{now}")
    List<SandboxLease> selectExpiredLeases(@Param("now") LocalDateTime now);

    @Update("UPDATE sbx_lease SET expire_at = #{newExpireAt}, updated_at = NOW() WHERE lease_id = #{leaseId} AND status = 'ACTIVE'")
    int renewLease(@Param("leaseId") String leaseId, @Param("newExpireAt") LocalDateTime newExpireAt);

    @Update("UPDATE sbx_lease SET status = 'RELEASED', expire_at = #{expireAt}, updated_at = NOW() WHERE lease_id = #{leaseId} AND status = 'ACTIVE'")
    int releaseLease(@Param("leaseId") String leaseId, @Param("expireAt") LocalDateTime expireAt);

    @Update("UPDATE sbx_lease SET status = 'EXPIRED', updated_at = NOW() WHERE lease_id = #{leaseId} AND status = 'ACTIVE' AND expire_at < #{now}")
    int markExpired(@Param("leaseId") String leaseId, @Param("now") LocalDateTime now);
}