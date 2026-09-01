package com.aegis.dal.mapper.sandbox;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 沙箱实例 Mapper。
 *
 * <p>提供 Reconcile 循环所需的扫描查询与状态更新，覆盖回收、预热、缩容、健康检查与分配场景。
 *
 * @author wang.zhen
 */
@Mapper
public interface SandboxInstanceMapper extends BaseMapper<SandboxInstance> {

    // =========================================================================
    // Reconcile 循环：回收扫描
    // =========================================================================

    /**
     * 按池扫描待回收 IDLE 实例（initialized ∈ {0, 2}）且空闲超时。
     *
     * <p>两种待回收场景：
     * <ul>
     *   <li>{@code initialized=0} — 脏（用户残留数据），需重初始化工作区</li>
     *   <li>{@code initialized=2} — 已装载（kb/skill/mcp 产物注入），但 IDLE 无人使用，
     *       需清理装载产物归还为干净 IDLE；防止装载产物泄漏到下次其他会话分配</li>
     * </ul>
     *
     * <p>{@code initialized=1}（干净 IDLE）无需回收，直接由分配器选取。
     *
     * <p>空闲判定：last_recycle_time 早于阈值（回退 recycled_time，再回退 allocated_time）。
     *
     * @param poolId        池 ID
     * @param idleThreshold 空闲超时时间点
     * @return 待回收实例列表
     */
    @Select("SELECT * FROM sbx_instance " +
            "WHERE deleted = 0 AND pool_id = #{poolId} " +
            "AND status = 'IDLE' AND initialized IN (0, 2) " +
            "AND COALESCE(last_recycle_time, recycled_time, allocated_time) < #{idleThreshold}")
    List<SandboxInstance> selectDirtyIdleTimeout(@Param("poolId") Long poolId,
                                                  @Param("idleThreshold") LocalDateTime idleThreshold);

    /**
     * 扫描所有池的待回收 IDLE 实例（initialized ∈ {0, 2}）且空闲超时（跨池回收扫描）。
     *
     * @param idleThreshold 空闲超时时间点
     * @return 待回收实例列表
     */
    @Select("SELECT * FROM sbx_instance " +
            "WHERE deleted = 0 AND status = 'IDLE' AND initialized IN (0, 2) " +
            "AND COALESCE(last_recycle_time, recycled_time, allocated_time) < #{idleThreshold}")
    List<SandboxInstance> selectAllDirtyIdleTimeout(@Param("idleThreshold") LocalDateTime idleThreshold);

    // =========================================================================
    // Reconcile 循环：预热 / 缩容统计
    // =========================================================================

    /**
     * 统计池内干净 IDLE 实例数（initialized=1），用于预热判断。
     *
     * @param poolId 池 ID
     * @return 干净 IDLE 实例数
     */
    @Select("SELECT COUNT(*) FROM sbx_instance " +
            "WHERE deleted = 0 AND pool_id = #{poolId} AND status = 'IDLE' AND initialized = 1")
    int countIdleClean(@Param("poolId") Long poolId);

    /**
     * 统计池内活跃实例数（IDLE + OCCUPIED），用于缩容判断。
     *
     * <p>活跃实例 = 尚未被销毁的实例（不含 DESTROYED）。
     *
     * @param poolId 池 ID
     * @return 活跃实例数
     */
    @Select("SELECT COUNT(*) FROM sbx_instance " +
            "WHERE deleted = 0 AND pool_id = #{poolId} AND status IN ('IDLE', 'OCCUPIED')")
    int countActive(@Param("poolId") Long poolId);

    // =========================================================================
    // Reconcile 循环：缩容扫描
    // =========================================================================

    /**
     * 按池查询 IDLE 实例（按 last_recycle_time 升序），用于缩容时优先销毁最旧的。
     *
     * <p>增加最小空闲时长校验，确保实例空闲超过阈值后才能被缩容销毁。
     *
     * @param poolId          池 ID
     * @param limit           最多返回的实例数
     * @param minIdleMinutes  最小空闲时长（分钟），实例的 last_recycle_time 须早于该阈值
     * @return IDLE 实例列表（按回收时间升序）
     */
    @Select("SELECT * FROM sbx_instance " +
            "WHERE deleted = 0 AND pool_id = #{poolId} AND status = 'IDLE' " +
            "AND COALESCE(last_recycle_time, recycled_time, allocated_time) <= " +
            "DATE_SUB(NOW(), INTERVAL #{minIdleMinutes} MINUTE) " +
            "ORDER BY COALESCE(last_recycle_time, recycled_time, allocated_time) ASC " +
            "LIMIT #{limit}")
    List<SandboxInstance> selectIdleForScaleDown(@Param("poolId") Long poolId,
                                                  @Param("limit") int limit,
                                                  @Param("minIdleMinutes") int minIdleMinutes);

    // =========================================================================
    // Reconcile 循环：健康检查扫描
    // =========================================================================

    /**
     * 按池+状态查询实例（用于健康检查：扫描 IDLE + OCCUPIED 实例）。
     *
     * @param poolId  池 ID
     * @param statuses 状态列表
     * @return 匹配的实例列表
     */
    @Select("<script>" +
            "SELECT * FROM sbx_instance " +
            "WHERE deleted = 0 AND pool_id = #{poolId} " +
            "AND status IN " +
            "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>" +
            "</script>")
    List<SandboxInstance> selectByPoolAndStatuses(@Param("poolId") Long poolId,
                                                   @Param("statuses") List<String> statuses);

    /**
     * 扫描指定状态的实例。
     *
     * @param status 实例状态
     * @return 匹配的实例列表
     */
    @Select("SELECT * FROM sbx_instance WHERE deleted = 0 AND status = #{status}")
    List<SandboxInstance> selectByStatus(@Param("status") String status);

    // =========================================================================
    // 状态更新
    // =========================================================================

    /**
     * 回收完成：标记实例为 IDLE(initialized=1)，更新 last_recycle_time。
     *
     * <p>用于软回收（工作区重初始化）完成后，将实例从"脏 IDLE"恢复为"干净 IDLE"。
     * 同步清空 resource_fingerprint（工作区已重初始化，装载产物不复存在，
     * 保留旧指纹会导致下次分配误判"指纹一致跳过装载"）。
     *
     * @param instanceId 实例 ID
     * @param recycleTime 回收（重初始化）时间
     * @return 影响行数
     */
    @Update("UPDATE sbx_instance SET status = 'IDLE', initialized = 1, " +
            "last_recycle_time = #{recycleTime}, slot_key = NULL, " +
            "user_id = NULL, agent_id = NULL, session_id = NULL, " +
            "resource_fingerprint = NULL, " +
            "update_time = NOW() WHERE instance_id = #{instanceId}")
    int updateRecycleComplete(@Param("instanceId") String instanceId,
                              @Param("recycleTime") LocalDateTime recycleTime);

    /**
     * 硬回收完成：标记实例为 IDLE(initialized=1)，更新 pod_name 和 last_recycle_time。
     *
     * <p>用于硬回收（销毁旧 Pod + 从镜像重建新 Pod）完成后，
     * 将实例从"脏 IDLE"恢复为"干净 IDLE"，同时记录新 Pod 名称。
     * 同步清空 resource_fingerprint（新 Pod 工作区为空，装载产物不复存在）。
     *
     * @param instanceId 实例 ID
     * @param podName    新 Pod 名称
     * @param recycleTime 回收（重建）时间
     * @return 影响行数
     */
    @Update("UPDATE sbx_instance SET status = 'IDLE', initialized = 1, " +
            "pod_name = #{podName}, last_recycle_time = #{recycleTime}, " +
            "slot_key = NULL, user_id = NULL, agent_id = NULL, session_id = NULL, " +
            "resource_fingerprint = NULL, " +
            "update_time = NOW() WHERE instance_id = #{instanceId}")
    int updateRecycleCompleteWithPod(@Param("instanceId") String instanceId,
                                      @Param("podName") String podName,
                                      @Param("recycleTime") LocalDateTime recycleTime);

    /**
     * 更新实例状态。
     *
     * @param instanceId 实例 ID
     * @param status     新状态
     * @return 影响行数
     */
    @Update("UPDATE sbx_instance SET status = #{status}, update_time = NOW() WHERE instance_id = #{instanceId}")
    int updateStatus(@Param("instanceId") String instanceId,
                     @Param("status") String status);

    /**
     * 递增复用次数。
     */
    @Update("UPDATE sbx_instance SET reuse_count = COALESCE(reuse_count, 0) + 1, update_time = NOW() " +
            "WHERE instance_id = #{instanceId}")
    int incrementReuseCount(@Param("instanceId") String instanceId);

    /**
     * 更新实例状态与快照信息。
     */
    @Update("UPDATE sbx_instance SET status = #{status}, snapshot_oss_key = #{snapshotKey}, " +
            "snapshot_time = NOW(), update_time = NOW() WHERE instance_id = #{instanceId}")
    int updateStatusAndSnapshot(@Param("instanceId") String instanceId,
                                @Param("status") String status,
                                @Param("snapshotKey") String snapshotKey);

    /**
     * 标记实例为 DESTROYED（缩容/故障驱逐时调用）。
     *
     * @param instanceId 实例 ID
     * @return 影响行数
     */
    @Update("UPDATE sbx_instance SET status = 'DESTROYED', update_time = NOW() " +
            "WHERE instance_id = #{instanceId}")
    int markDestroyed(@Param("instanceId") String instanceId);

    // =========================================================================
    // 乐观并发控制（OCC）更新
    // =========================================================================

    /**
     * 基于版本号的状态更新（乐观锁）。
     *
     * <p>仅当 version 匹配时才更新，防止并发状态变更导致的脏写。
     * 用于 Coordinator 分配/释放场景，确保状态机转换的原子性。</p>
     *
     * @param instanceId 实例 ID
     * @param newStatus  新状态
     * @param oldVersion 当前版本号
     * @return 影响行数（0 表示版本不匹配，更新失败）
     */
    @Update("UPDATE sbx_instance SET status = #{newStatus}, version = version + 1, " +
            "update_time = NOW() " +
            "WHERE instance_id = #{instanceId} AND version = #{oldVersion}")
    int updateStatusWithVersion(@Param("instanceId") String instanceId,
                                 @Param("newStatus") String newStatus,
                                 @Param("oldVersion") int oldVersion);

    /**
     * 基于版本号的分配更新（IDLE → OCCUPIED）。
     *
     * <p>原子性地更新状态、占用信息和版本号，确保同一实例不会被并发分配给多个会话。</p>
     *
     * @param instanceId 实例 ID
     * @param userId     用户 ID
     * @param agentId    Agent ID
     * @param sessionId  会话 ID
     * @param slotKey    槽位键
     * @param oldVersion 当前版本号
     * @return 影响行数（0 表示版本不匹配或状态非 IDLE）
     */
    @Update("UPDATE sbx_instance SET status = 'OCCUPIED', user_id = #{userId}, " +
            "agent_id = #{agentId}, session_id = #{sessionId}, slot_key = #{slotKey}, " +
            "allocated_time = NOW(), last_heartbeat_time = NOW(), initialized = 1, version = version + 1, " +
            "update_time = NOW() " +
            "WHERE instance_id = #{instanceId} AND version = #{oldVersion} AND status = 'IDLE'")
    int updateAllocateWithVersion(@Param("instanceId") String instanceId,
                                   @Param("userId") Long userId,
                                   @Param("agentId") Long agentId,
                                   @Param("sessionId") String sessionId,
                                   @Param("slotKey") String slotKey,
                                   @Param("oldVersion") int oldVersion);

    /**
     * 更新最后心跳时间。
     *
     * @param instanceId 实例 ID
     * @param heartbeatTime 心跳时间
     * @return 影响行数
     */
    @Update("UPDATE sbx_instance SET last_heartbeat_time = #{heartbeatTime}, update_time = NOW() " +
            "WHERE instance_id = #{instanceId}")
    int updateHeartbeat(@Param("instanceId") String instanceId,
                        @Param("heartbeatTime") LocalDateTime heartbeatTime);

    /**
     * 清理实例占用信息（slot_key, user_id, agent_id, session_id）。
     *
     * <p>用于 ABNORMAL 实例重建前清理遗留的 OCCUPIED 占用数据，
     * 防止旧占用信息干扰新 Pod 的分配。</p>
     *
     * @param instanceId 实例 ID
     * @return 影响行数
     */
    @Update("UPDATE sbx_instance SET slot_key = NULL, user_id = NULL, " +
            "agent_id = NULL, session_id = NULL, update_time = NOW() " +
            "WHERE instance_id = #{instanceId}")
    int clearOccupancy(@Param("instanceId") String instanceId);

    /**
     * 按 instance_id 列表批量查询实例（租约过期对账场景使用）。
     *
     * @param instanceIds 实例ID列表
     * @return 实例列表
     */
    @Select("<script>SELECT * FROM sbx_instance WHERE instance_id IN " +
            "<foreach collection='instanceIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}</foreach></script>")
    List<SandboxInstance> selectByInstanceIds(@Param("instanceIds") List<String> instanceIds);

    /**
     * 按 instance_id 标记实例为脏 IDLE（租约过期回收场景使用）。
     *
     * @param instanceId 实例 ID
     * @return 影响行数
     */
    @Update("UPDATE sbx_instance SET status = 'IDLE', initialized = 0, " +
            "recycled_time = NOW(), last_recycle_time = NOW(), " +
            // 清理残留占用，防止脏 IDLE 携带旧 session/user/agent 数据
            "slot_key = NULL, user_id = NULL, agent_id = NULL, session_id = NULL, " +
            "resource_fingerprint = NULL, update_time = NOW() " +
            "WHERE instance_id = #{instanceId} AND status = 'OCCUPIED'")
    int markIdleDirtyByInstanceId(@Param("instanceId") String instanceId);

    /**
     * 带乐观锁的强制释放（OCCUPIED → 脏 IDLE）。
     *
     * <p>用于 OCCUPIED 超时回收场景，原子性地完成状态变更 + 脏标记 + 占用清理，
     * 确保强制回收的实例不会被误判为干净 IDLE（initialized=1）而被直接分配。
     *
     * @param instanceId 实例 ID
     * @param oldVersion 当前版本号（乐观锁）
     * @return 影响行数（0 表示版本不匹配或状态非 OCCUPIED）
     */
    @Update("UPDATE sbx_instance SET status = 'IDLE', initialized = 0, " +
            "recycled_time = NOW(), last_recycle_time = NOW(), " +
            "slot_key = NULL, user_id = NULL, agent_id = NULL, session_id = NULL, " +
            "resource_fingerprint = NULL, version = version + 1, update_time = NOW() " +
            "WHERE instance_id = #{instanceId} AND version = #{oldVersion} AND status = 'OCCUPIED'")
    int forceReleaseOccupied(@Param("instanceId") String instanceId,
                             @Param("oldVersion") int oldVersion);

    /**
     * 查询泄漏的 OCCUPIED 实例（无活跃租约的 OCCUPIED 实例）。
     *
     * <p>安全网扫描：分配过程中若在创建租约前异常，实例会永久停在 OCCUPIED
     * 且无租约记录，租约过期对账无法覆盖此类实例。本查询检出后由 Reconcile
     * 强制回收为脏 IDLE，防止资源永久泄漏。
     *
     * <p>P0-6 双重护栏：① sbx_lease 已加入 TENANT_IGNORE_TABLES，子查询跨全租户扫描，
     * 不再因调度线程缺租户上下文（tenant_id=0）而漏检活跃租约；② 增加 last_heartbeat_time
     * 新鲜度二次确认——5 分钟内有心跳的 OCCUPIED 实例即使暂无活跃租约（如租约刚创建未提交的竞态）
     * 也不回收，杜绝误杀活跃实例。阈值(5min) > 心跳周期(30s) + 对账周期(120s)。
     *
     * @return 泄漏的 OCCUPIED 实例列表
     */
    @Select("SELECT si.* FROM sbx_instance si " +
            "WHERE si.deleted = 0 AND si.status = 'OCCUPIED' " +
            "AND NOT EXISTS (SELECT 1 FROM sbx_lease sl " +
            "WHERE sl.instance_id = si.instance_id AND sl.status = 'ACTIVE') " +
            "AND (si.last_heartbeat_time IS NULL " +
            "     OR si.last_heartbeat_time < DATE_SUB(NOW(), INTERVAL 5 MINUTE))")
    List<SandboxInstance> selectOrphanedOccupied();
}
