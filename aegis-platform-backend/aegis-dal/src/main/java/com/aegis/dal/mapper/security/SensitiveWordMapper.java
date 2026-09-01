package com.aegis.dal.mapper.security;

import com.aegis.core.domain.security.SensitiveWord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {
}
