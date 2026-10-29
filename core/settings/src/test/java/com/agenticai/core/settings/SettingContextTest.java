package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SettingContext}: the factory helpers and the {@code effectiveTenant} isolation rule - a
 * context naming another tenant, or a thread with no tenant at all, resolves nothing from the
 * store (platform value only); one tenant's override must never reach another.
 */
class SettingContextTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void as(String tenantId) {
        TenantContext.set(TenantContext.of(tenantId, "alice", Set.of()));
    }

    @Test
    void factoriesCarryExactlyWhatTheyAreGiven() {
        assertThat(SettingContext.tenant().tenantId()).isNull();
        assertThat(SettingContext.tenant().agentId()).isNull();

        assertThat(SettingContext.forAgent(AGENT).agentId()).isEqualTo(AGENT);
        assertThat(SettingContext.forAgent(null).agentId()).isNull();

        SettingContext explicit = SettingContext.of("tenant-b", AGENT);
        assertThat(explicit.tenantId()).isEqualTo("tenant-b");
        assertThat(explicit.agentId()).isEqualTo(AGENT);
    }

    @Test
    void effectiveTenantIsTheThreadTenantWhenTheContextNamesNone() {
        as("tenant-a");

        assertThat(SettingContext.tenant().effectiveTenant()).isEqualTo("tenant-a");
        assertThat(SettingContext.forAgent(AGENT).effectiveTenant()).isEqualTo("tenant-a");
    }

    @Test
    void effectiveTenantIsNullWhenTheContextNamesAnotherTenant() {
        as("tenant-a");

        assertThat(SettingContext.of("tenant-b", null).effectiveTenant()).isNull();
        assertThat(SettingContext.of("tenant-a", null).effectiveTenant()).isEqualTo("tenant-a");
    }

    @Test
    void effectiveTenantIsNullWithNoThreadContextAtAll() {
        TenantContext.clear();

        assertThat(SettingContext.tenant().effectiveTenant()).isNull();
        assertThat(SettingContext.of("tenant-a", AGENT).effectiveTenant()).isNull();
    }
}
