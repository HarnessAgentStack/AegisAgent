package com.aegis.admin.web.resource;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.resource.SkillManageService;
import com.aegis.core.common.web.Result;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.security.ResourceOwner;
import com.aegis.core.security.ResourcePermission;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 技能后台管理控制器：scope 修改等管理员操作。
 *
 * <p>仅技术管理员可修改技能的 scope 字段。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/skill")
@RequiredArgsConstructor
public class SkillAdminController {

    private final SkillManageService skillManageService;

    /**
     * 修改技能 scope（仅技术管理员可用）。
     */
    @PutMapping("/{id}/scope")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPDATE_SKILL_SCOPE", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> updateScope(
            @PathVariable Long id,
            @RequestBody ScopeUpdateRequest req,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        skillManageService.updateScope(id, SkillScope.valueOf(req.getScope()));
        return Result.success();
    }

    @Data
    public static class ScopeUpdateRequest {
        private String scope;
    }
}
