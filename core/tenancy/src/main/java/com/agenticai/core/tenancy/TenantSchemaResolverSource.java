package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantSchemaSource;
import org.springframework.stereotype.Component;

/**
 * {@link TenantSchemaSource} over {@link TenantSchemaResolver#currentSchemaName()} (instrumented,
 * fail-closed).
 */
@Component
public class TenantSchemaResolverSource implements TenantSchemaSource {

    @Override
    public String currentSchemaName() {
        return TenantSchemaResolver.currentSchemaName();
    }
}
