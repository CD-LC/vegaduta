package com.agenticai.core.tenancy;

import java.util.function.Supplier;

/**
 * SEC-017 - the explicit "this code path is platform-scoped by design" marker
 * that {@link TenantSchemaResolver} honours when there is no
 * {@link com.agenticai.core.security.TenantContext}.
 *
 * <p>With {@code app.tenancy.fail-on-missing-context=true} (the default), a
 * thread without a tenant context no longer resolves to {@code public} (which
 * holds a full copy of every tenant table); it resolves to an empty sentinel
 * schema so any tenant-table access fails. Code that genuinely must run
 * against the legacy {@code public} schema without a tenant - there should be
 * very little of it; control-plane entities are all
 * {@code schema = "control_plane"}-qualified and do not need this - wraps that
 * work here:
 *
 * <pre>{@code
 * PlatformScope.run(() -> legacyPublicSchemaJob());
 * }</pre>
 *
 * <p>Nested calls are fine (depth-counted); the marker is always removed in
 * {@code finally}, so a pooled thread never leaks platform scope. It has no
 * effect while a tenant context is set - a real tenant always wins.
 */
public final class PlatformScope {

    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private PlatformScope() {
        // utility class
    }

    public static boolean isActive() {
        Integer depth = DEPTH.get();
        return depth != null && depth > 0;
    }

    public static void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    public static <T> T call(Supplier<T> work) {
        Integer previous = DEPTH.get();
        DEPTH.set(previous == null ? 1 : previous + 1);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                DEPTH.remove();
            } else {
                DEPTH.set(previous);
            }
        }
    }
}
