package com.aegis.dal.mapper.monitor;

import com.aegis.core.domain.monitor.AuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
