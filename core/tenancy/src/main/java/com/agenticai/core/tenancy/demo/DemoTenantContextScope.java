package com.agenticai.core.tenancy.demo;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tiny helper so the seeding path and {@link DemoSessionService#inDemoTenantContext} share one
 * always-clear-in-finally shape: binds {@link TenantContext} to the shared demo schema with the
 * sandbox's own {@code demo:<sessionId>} subject and no roles.
 */
final class DemoTenantContextScope {

    private DemoTenantContextScope() {}

    static <T> T call(String schema, UUID sessionId, Supplier<T> action) {
        TenantContext.set(
                TenantContext.of(schema, DemoSessionService.DEMO_ACTOR_PREFIX + sessionId, Set.of()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
