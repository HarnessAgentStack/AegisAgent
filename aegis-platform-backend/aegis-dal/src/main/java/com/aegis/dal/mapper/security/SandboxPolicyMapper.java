package com.aegis.dal.mapper.security;

import com.aegis.core.domain.security.SandboxPolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SandboxPolicyMapper extends BaseMapper<SandboxPolicy> {
}
