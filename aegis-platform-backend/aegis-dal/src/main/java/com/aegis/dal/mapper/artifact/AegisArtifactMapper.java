package com.aegis.dal.mapper.artifact;

import com.aegis.core.domain.session.AegisArtifact;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话产物 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface AegisArtifactMapper extends BaseMapper<AegisArtifact> {
}