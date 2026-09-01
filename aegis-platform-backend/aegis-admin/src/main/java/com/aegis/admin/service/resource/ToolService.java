package com.aegis.admin.service.resource;

import com.aegis.dal.mapper.resource.ToolMapper;
import com.aegis.core.domain.resource.Tool;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 工具管理领域服务。
 *
 * <p>提供工具列表分页查询能力。
 * 工具（Tool）为平台级资源，由 MCP 服务/客户端激活时自动注册，或由管理员内置。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final ToolMapper toolMapper;

    /**
     * 分页查询工具列表。
     *
     * <p>工具为平台级表（res_tool），不按租户隔离，全租户共享。
     *
     * @param keyword 关键词（匹配工具名/编码/描述）
     * @param page    页码
     * @param size    每页条数
     * @return 工具分页结果
     */
    public Page<Tool> page(String keyword, int page, int size) {
        Page<Tool> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<Tool> wrapper = new LambdaQueryWrapper<Tool>()
                .like(keyword != null && !keyword.isEmpty(), Tool::getToolName, keyword)
                .orderByDesc(Tool::getCreateTime);
        return toolMapper.selectPage(pageObj, wrapper);
    }
}
