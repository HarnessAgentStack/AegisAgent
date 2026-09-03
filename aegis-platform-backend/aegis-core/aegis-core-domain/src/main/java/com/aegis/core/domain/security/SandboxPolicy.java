package com.aegis.core.domain.security;

import com.aegis.core.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import lombok.experimental.Accessors;

/**
 * 沙箱命令策略实体。
 * <p>
 * 判定某工具是否强制进沙箱执行。租户隔离，唯一键(tenant_id, tool_code)。
 */
@Getter @Setter @ToString @Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("sec_sandbox_policy")
public class SandboxPolicy extends TenantEntity {

    /** 工具编码，租户内唯一 */
    private String toolCode;

    /** 沙箱执行决策：true 强制进沙箱 / false 明确不进 / null 未配置走默认 */
    private Boolean sandboxExecution;

    /** 策略描述 */
    private String description;

    /** 是否启用 */
    private Boolean enabled;
}
