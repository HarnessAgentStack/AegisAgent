package com.aegis.dal.mapper.sandbox;

import com.aegis.core.domain.sandbox.SandboxPool;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 沙箱池 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface SandboxPoolMapper extends BaseMapper<SandboxPool> {

    /**
     * 查询池的最后 Reconcile 时间。
     *
     * @param poolId 池 ID
     * @return 最后 Reconcile 时间，可能为 null
     */
    @Select("SELECT last_reconcile_time FROM sbx_pool WHERE id = #{poolId}")
    LocalDateTime selectLastReconcileTime(@Param("poolId") Long poolId);

    /**
     * 更新池的最后 Reconcile 时间。
     *
     * @param poolId            池 ID
     * @param lastReconcileTime 最后 Reconcile 时间
     */
    @Update("UPDATE sbx_pool SET last_reconcile_time = #{lastReconcileTime}, update_time = NOW() " +
            "WHERE id = #{poolId}")
    void updateLastReconcileTime(@Param("poolId") Long poolId,
                                  @Param("lastReconcileTime") LocalDateTime lastReconcileTime);

    /** 查询全局最后 Reconcile 时间（所有池中最大的 last_reconcile_time） */
    @Select("SELECT MAX(last_reconcile_time) FROM sbx_pool WHERE deleted = 0")
    LocalDateTime selectGlobalLastReconcileTime();

    /** 更新全局最后 Reconcile 时间（更新所有池的 last_reconcile_time） */
    @Update("UPDATE sbx_pool SET last_reconcile_time = #{time} WHERE deleted = 0")
    void updateGlobalLastReconcileTime(@Param("time") LocalDateTime time);
}
