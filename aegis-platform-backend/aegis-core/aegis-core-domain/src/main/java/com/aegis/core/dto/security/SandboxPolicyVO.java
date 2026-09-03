package com.aegis.core.dto.security;

import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SandboxPolicyVO implements Serializable {
    private Long id;
    private Long tenantId;
    private String toolCode;
    private Boolean sandboxExecution;
    private String description;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
