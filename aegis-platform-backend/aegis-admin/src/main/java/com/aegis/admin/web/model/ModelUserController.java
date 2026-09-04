package com.aegis.admin.web.model;

import com.aegis.admin.service.model.ModelManageService;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.dto.model.ModelDefVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户侧模型只读 Controller。
 *
 * <p>与 {@link ModelAdminController}（管理员 CRUD）相对，本端点面向所有已认证用户，
 * 仅暴露启用中的模型枚举信息（如知识库创建的嵌入模型下拉），不暴露管理操作与密钥。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/model-user")
@RequiredArgsConstructor
public class ModelUserController {

    private final ModelManageService modelManageService;

    /**
     * 启用中的嵌入模型列表（知识库创建等场景）。
     */
    @GetMapping("/defs")
    public Result<List<ModelDefVO>> listEnabledEmbeddingModels(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(modelManageService.listEnabledEmbeddingModels());
    }
}
