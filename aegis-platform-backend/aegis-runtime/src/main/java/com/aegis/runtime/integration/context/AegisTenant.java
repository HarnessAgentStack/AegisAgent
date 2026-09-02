package com.aegis.runtime.integration.context;

import java.util.Objects;

public record AegisTenant(Long tenantId) {
    public AegisTenant {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }
}
