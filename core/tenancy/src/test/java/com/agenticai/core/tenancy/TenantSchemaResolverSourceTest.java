package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The port raw-JDBC modules without a tenancy dependency (settings) use to name the tenant's tables. */
class TenantSchemaResolverSourceTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
        TenantSchemaResolver.resetRegisteredInstanceForTests();
    }

    @Test
    void namesTheSchemaOfTheTenantBoundToTheCallingThread() {
        TenantContext.set(TenantContext.of("tenant_acme", "u", Set.of()));
        assertEquals("tenant_acme", new TenantSchemaResolverSource().currentSchemaName());
    }

    @Test
    void followsTheThreadsTenantNotTheFirstOneSeen() {
        TenantContext.set(TenantContext.of("tenant_one", "u", Set.of()));
        TenantSchemaResolverSource source = new TenantSchemaResolverSource();
        assertEquals("tenant_one", source.currentSchemaName());
        TenantContext.set(TenantContext.of("tenant_two", "u", Set.of()));
        assertEquals("tenant_two", source.currentSchemaName());
    }
}
