package com.aegis.runtime.integration.context;

import java.util.Objects;

public record AegisGovernance(String governanceTier, String modelTier) {
    public AegisGovernance {
        Objects.requireNonNull(governanceTier, "governanceTier must not be null");
    }
}
