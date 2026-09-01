package com.aegis.admin.web.resource;

import com.aegis.admin.service.resource.ToolService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.resource.Tool;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.aegis.core.domain.tenant.Tenant;

/**
 * ToolController。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tool")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;

/**
     * 分页查询工具列表。
     *
     * <p>工具为平台级表（res_tool），不按租户隔离，全租户共享。
     *
     * @param keyword 关键词（匹配工具名/编码/描述）
     * @param page    页码
     * @param size    每页条数
     */
    @GetMapping("/page")
    public Result<Page<Tool>> page(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(toolService.page(keyword, page, size));
    }
}
