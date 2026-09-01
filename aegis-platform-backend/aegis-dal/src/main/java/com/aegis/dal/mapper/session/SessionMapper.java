package com.aegis.dal.mapper.session;

import com.aegis.core.domain.session.Session;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {
}
