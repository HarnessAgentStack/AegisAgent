package com.aegis.dal.mapper.resource;

import com.aegis.core.domain.resource.Skill;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 技能 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface SkillMapper extends BaseMapper<Skill> {

    /**
     * 查询已发布的全局系统技能（跨租户可见，显式忽略租户插件）。
     *
     * <p>该方法用 {@link InterceptorIgnore} 显式跳过 MyBatis-Plus 多租户插件，
     * 调用方无需临时清除租户上下文，避免 Web 层 ThreadLocal 篡改。
     *
     * @param scope      作用域（通常 GLOBAL）
     * @param lifeStatus 生命周期状态（通常 PUBLISHED）
     * @param keyword    模糊关键词（匹配 skill_name / skill_code / description，可为 null）
     * @return 全局系统技能列表
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>" +
            "SELECT * FROM res_skill WHERE scope = #{scope} AND life_status = #{lifeStatus} AND deleted = 0" +
            "<if test='keyword != null and keyword != \"\"'>" +
            " AND (skill_name LIKE CONCAT('%',#{keyword},'%')" +
            " OR skill_code LIKE CONCAT('%',#{keyword},'%')" +
            " OR description LIKE CONCAT('%',#{keyword},'%'))" +
            "</if>" +
            "</script>")
    List<Skill> selectGlobalSkillsForTenant(
            @Param("scope") String scope,
            @Param("lifeStatus") String lifeStatus,
            @Param("keyword") String keyword);
}
