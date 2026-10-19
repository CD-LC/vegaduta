package com.agenticai.core.resilience;

/**
 * Thrown by {@link ResilienceGuard#begin()} when a guarded dependency is being
 * shed: its circuit is open, or its bulkhead is full. It is deliberately a
 * plain {@link RuntimeException} with no stack trace - it is a control-flow
 * signal that every integration catches and turns into that feature's EXISTING
 * "unavailable" result (a {@code SidecarCommunicationException}, a
 * {@code WebSearchException}, a {@code ChannelDeliveryException}, ...). It must
 * never reach {@code GlobalExceptionHandler}'s catch-all (that would be a 500).
 */
public final class DependencyUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the call was shed. */
    public enum Reason { CIRCUIT_OPEN, BULKHEAD_FULL }

    private final String dependency;
    private final Reason reason;

    public DependencyUnavailableException(String dependency, Reason reason) {
        super("'" + dependency + "' is temporarily unavailable (" + (reason == Reason.CIRCUIT_OPEN
            ? "circuit open after repeated failures" : "too many concurrent calls") + ")", null, false, false);
        this.dependency = dependency;
        this.reason = reason;
    }

    public String dependency() {
        return dependency;
    }

    public Reason reason() {
        return reason;
    }
}
