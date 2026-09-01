package com.aegis.dal.mapper.security;

import com.aegis.core.domain.security.MaskRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 脱敏规则 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface MaskRuleMapper extends BaseMapper<MaskRule> {
}
