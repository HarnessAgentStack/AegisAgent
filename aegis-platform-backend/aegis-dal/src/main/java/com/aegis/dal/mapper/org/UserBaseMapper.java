package com.aegis.dal.mapper.org;

import com.aegis.core.domain.org.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户基础 Mapper（只读用途）。
 *
 * <p>仅用于读取用户显示名（realName / username）等非敏感字段。
 *
 * @author wang.zhen
 */
@Mapper
public interface UserBaseMapper extends BaseMapper<User> {
}
