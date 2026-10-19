package com.agenticai.core.agents.memory;

/**
 * SEC-004 turn binding behind {@link SessionMemoryService#bindMemorySessionId}, {@link
 * SessionMemoryService#restoreMemorySessionId} and {@link
 * SessionMemoryService#boundMemorySessionId}: the scoped memory id bound on the calling thread
 * around a turn, read once, synchronously, on entry by the turn path.
 */
final class SessionMemoryBinding {

    private SessionMemoryBinding() {}

    private static final ThreadLocal<String> BOUND_MEMORY_SESSION_ID = new ThreadLocal<>();

    /**
     * Binds {@code scopedId} on this thread; returns the previous binding for {@link
     * #restoreMemorySessionId}.
     */
    static String bindMemorySessionId(String scopedId) {
        String previous = BOUND_MEMORY_SESSION_ID.get();
        BOUND_MEMORY_SESSION_ID.set(scopedId);
        return previous;
    }

    /** Restores the binding returned by {@link #bindMemorySessionId}. */
    static void restoreMemorySessionId(String previous) {
        if (previous == null) {
            BOUND_MEMORY_SESSION_ID.remove();
        } else {
            BOUND_MEMORY_SESSION_ID.set(previous);
        }
    }

    /**
     * The scoped memory id bound on this thread. Fails closed: there is no fallback to a raw,
     * client-chosen session id.
     */
    static String boundMemorySessionId() {
        String id = BOUND_MEMORY_SESSION_ID.get();
        if (id == null || !id.startsWith(SessionMemoryService.SCOPED_ID_PREFIX)) {
            throw new IllegalStateException(
                    "No scoped chat-memory session is bound to this turn (SEC-004)");
        }
        return id;
    }
}
