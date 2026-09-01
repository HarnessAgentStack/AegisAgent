package com.aegis.dal.mapper.resource;

import com.aegis.core.domain.resource.KbSubscription;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 知识库订阅关系 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface KbSubscriptionMapper extends BaseMapper<KbSubscription> {

    /**
     * 查询指定订阅关系（忽略逻辑删除，用于"取消后重新订阅"场景恢复记录）。
     *
     * <p>注意：自定义 @Select 不受 MP 逻辑删除自动注入影响，但会被租户插件自动追加 tenant_id 条件。</p>
     */
    @Select("SELECT id, tenant_id, kb_id, kb_code, subscriber_type, subscriber_id, " +
            "create_by, create_time, deleted " +
            "FROM res_kb_subscription " +
            "WHERE kb_id = #{kbId} AND subscriber_type = #{subscriberType} " +
            "AND subscriber_id = #{subscriberId} " +
            "LIMIT 1")
    KbSubscription selectOneIgnoreLogicDelete(@Param("kbId") Long kbId,
                                              @Param("subscriberType") String subscriberType,
                                              @Param("subscriberId") Long subscriberId);

    /**
     * 恢复软删除的订阅记录（把 deleted 从 1 改回 0）。
     *
     * <p>自定义 @Update 不受 MP 逻辑删除自动注入影响，租户插件会自动追加 tenant_id 条件。</p>
     */
    @Update("UPDATE res_kb_subscription SET deleted = 0, create_by = #{createBy}, create_time = #{createTime} " +
            "WHERE kb_id = #{kbId} AND subscriber_type = #{subscriberType} " +
            "AND subscriber_id = #{subscriberId} AND deleted = 1")
    int restoreLogicDeleted(@Param("kbId") Long kbId,
                            @Param("subscriberType") String subscriberType,
                            @Param("subscriberId") Long subscriberId,
                            @Param("createBy") Long createBy,
                            @Param("createTime") java.time.LocalDateTime createTime);
}
