package com.aegis.runtime.integration.context;

import java.util.Objects;

public record AegisAgentMeta(Long agentId, String agentType) {
    public AegisAgentMeta {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(agentType, "agentType must not be null");
    }
}
