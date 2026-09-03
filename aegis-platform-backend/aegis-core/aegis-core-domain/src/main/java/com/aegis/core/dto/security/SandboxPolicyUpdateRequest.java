package com.aegis.core.dto.security;

import lombok.*;
import java.io.Serializable;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SandboxPolicyUpdateRequest implements Serializable {
    private Boolean sandboxExecution;
    private String description;
    private Boolean enabled;
}
