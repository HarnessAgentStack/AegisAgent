package com.aegis.dal.mapper.workspace;

import com.aegis.core.domain.workspace.AgentWorkspaceMaterial;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能体工作区物化指纹 Mapper。
 *
 * <p>维护 {@link AgentWorkspaceMaterial} 的物化指纹记录，支撑增量物化检测。
 *
 * @author wang.zhen
 */
@Mapper
public interface AgentWorkspaceMaterialMapper extends BaseMapper<AgentWorkspaceMaterial> {
}
