package com.agenticai.core.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-request marker that the current request is a platform admin's read-only "view as user"
 * session ({@link ViewAsFilter}). Set by that filter only, for the duration of one request, and
 * always cleared in its {@code finally}.
 *
 * <p>The filter already rejects every non-GET/HEAD/OPTIONS request, so this is the SECOND line:
 * services with outbound side effects (LLM calls and their spend, email, channel/message sends)
 * call {@link #assertNotImpersonating} so a side-effecting GET that slipped past the path denylist
 * still cannot act on the tenant's behalf. Thread-local like {@link TenantContext}: background work
 * started by a view-as request does not inherit it, which is fine because a read-only request never
 * starts any.
 */
public final class ImpersonationContext {

    /** What a view-as request runs as - never carries the raw grant. */
    public record Session(
            UUID sessionId,
            String adminSubject,
            String adminEmail,
            String tenantSchema,
            String targetSubject) {}

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private ImpersonationContext() {}

    /** {@link ViewAsFilter} only (public for other modules' tests). */
    public static void set(Session session) {
        CURRENT.set(session);
    }

    /** {@link ViewAsFilter} only (public for other modules' tests). */
    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Session> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /**
     * Throws {@link ImpersonationReadOnlyException} when called on a view-as request thread; a
     * no-op everywhere else (one thread-local read).
     *
     * @param action short label for the log/exception, e.g. {@code "llm-call"}.
     */
    public static void assertNotImpersonating(String action) {
        Session session = CURRENT.get();
        if (session != null) {
            throw new ImpersonationReadOnlyException(action, session.sessionId());
        }
    }
}
