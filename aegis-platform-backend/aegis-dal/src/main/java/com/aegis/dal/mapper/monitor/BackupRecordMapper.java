package com.aegis.dal.mapper.monitor;

import com.aegis.core.domain.monitor.BackupRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 备份记录 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface BackupRecordMapper extends BaseMapper<BackupRecord> {
}
