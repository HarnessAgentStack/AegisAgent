package com.aegis.core.dto.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.io.Serializable;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SandboxPolicyCreateRequest implements Serializable {
    @NotBlank(message = "工具编码不能为空")
    private String toolCode;
    @NotNull(message = "沙箱执行决策不能为空")
    private Boolean sandboxExecution;
    private String description;
    private Boolean enabled;
}
