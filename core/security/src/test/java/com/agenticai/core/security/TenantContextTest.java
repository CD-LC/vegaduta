package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The raw {@link ThreadLocal} contract every other mechanism ({@link TenantScope},
 * {@code TenantContextFilter}, the tenant-aware executors) builds on: set/current/clear, the
 * {@code externalTriggerId} overload, and {@link TenantContext#hasRole}.
 */
class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void currentIsEmptyBeforeAnythingIsSet() {
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void setThenCurrentReturnsTheSameInstance() {
        TenantContext context = TenantContext.of("tenant_acme", "user-1", Set.of("tenant-admin"));

        TenantContext.set(context);

        assertThat(TenantContext.current()).containsSame(context);
        assertThat(context.getTenantId()).isEqualTo("tenant_acme");
        assertThat(context.getSubject()).isEqualTo("user-1");
        assertThat(context.getRoles()).containsExactly("tenant-admin");
    }

    @Test
    void hasRoleChecksMembershipExactly() {
        TenantContext context = TenantContext.of("tenant_acme", "user-1", Set.of("tenant-admin", "user"));

        assertThat(context.hasRole("tenant-admin")).isTrue();
        assertThat(context.hasRole("user")).isTrue();
        // A caller must not be able to grant itself roles by prefix/suffix confusion.
        assertThat(context.hasRole("tenant")).isFalse();
        assertThat(context.hasRole("platform-admin")).isFalse();
        assertThat(context.hasRole("ROLE_tenant-admin")).isFalse();
    }

    @Test
    void theThreeArgFactoryLeavesExternalTriggerIdNull() {
        TenantContext context = TenantContext.of("tenant_acme", "user-1", Set.of());

        assertThat(context.getExternalTriggerId()).isNull();
    }

    @Test
    void theFourArgFactoryCarriesTheExternalTriggerId() {
        TenantContext context =
                TenantContext.of("tenant_acme", "mcp-client", Set.of(), "grant-42");

        assertThat(context.getExternalTriggerId()).isEqualTo("grant-42");
    }

    @Test
    void clearEmptiesTheThreadAgain() {
        TenantContext.set(TenantContext.of("tenant_acme", "user-1", Set.of()));

        TenantContext.clear();

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void theContextDoesNotLeakAcrossThreads() throws Exception {
        TenantContext.set(TenantContext.of("tenant_acme", "user-1", Set.of()));

        AtomicReference<Object> seen = new AtomicReference<>("unset");
        Thread other = new Thread(() -> seen.set(TenantContext.current().orElse(null)));
        other.start();
        other.join();

        assertThat(seen.get()).isNull();
        // The owning thread keeps its own context regardless.
        assertThat(TenantContext.current()).isPresent();
    }
}
