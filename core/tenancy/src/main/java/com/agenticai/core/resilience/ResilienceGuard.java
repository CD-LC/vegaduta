package com.agenticai.core.resilience;

/**
 * What an integration holds: an optional {@link CircuitBreaker} plus an optional
 * {@link Bulkhead} for one dependency. {@link #NOOP} (no breaker, no bulkhead) is what every
 * integration gets while {@code app.resilience.circuit-breaker.enabled} is false, so call sites
 * have one code path and the default behaviour is byte-for-byte today's.
 *
 * <pre>{@code
 * try (ResilienceGuard.Call call = guard.begin()) {      // may throw DependencyUnavailableException
 *     Result r = doTheHttpCall();
 *     call.success();                                       // or call.failure(e) / call.ignore()
 *     return r;
 * } catch (DependencyUnavailableException shed) {
 *     return theFeaturesExistingUnavailableResult();
 * }
 * }</pre>
 * Closing without settling counts as {@link Call#ignore()}.
 */
public final class ResilienceGuard {

    /** No breaker, no bulkhead: {@link #begin()} always succeeds and records nothing. */
    public static final ResilienceGuard NOOP = new ResilienceGuard(null, null);

    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;

    public ResilienceGuard(CircuitBreaker breaker, Bulkhead bulkhead) {
        this.breaker = breaker;
        this.bulkhead = bulkhead;
    }

    /** True when this guard can actually shed calls. */
    public boolean active() {
        return breaker != null || bulkhead != null;
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    /**
     * Admits one call.
     *
     * @throws DependencyUnavailableException when the circuit is open or the bulkhead is full -
     *     the dependency was NOT called.
     */
    public Call begin() {
        if (breaker == null && bulkhead == null) {
            return Call.NOOP;
        }
        CircuitBreaker.Permit permit = CircuitBreaker.Permit.untracked();
        if (breaker != null) {
            permit = breaker.tryAcquire();
            if (!permit.granted()) {
                throw new DependencyUnavailableException(
                    breaker.name(), DependencyUnavailableException.Reason.CIRCUIT_OPEN);
            }
        }
        Bulkhead.Ticket ticket = null;
        if (bulkhead != null) {
            ticket = bulkhead.tryAcquire();
            if (ticket == null) {
                permit.ignore(); // frees a half-open probe slot; says nothing about the dependency
                throw new DependencyUnavailableException(
                    bulkhead.name(), DependencyUnavailableException.Reason.BULKHEAD_FULL);
            }
        }
        return new Call(permit, ticket);
    }

    /** One admitted call. Try-with-resources friendly; never throws. */
    public static final class Call implements AutoCloseable {

        static final Call NOOP = new Call(CircuitBreaker.Permit.untracked(), null);

        private final CircuitBreaker.Permit permit;
        private final Bulkhead.Ticket ticket;

        private Call(CircuitBreaker.Permit permit, Bulkhead.Ticket ticket) {
            this.permit = permit;
            this.ticket = ticket;
        }

        /** The dependency answered properly. */
        public void success() {
            permit.success();
        }

        /** The dependency failed (the breaker's own predicate may still decide it is the caller's fault). */
        public void failure(Throwable cause) {
            permit.failure(cause);
        }

        /** Neutral outcome (cancel, caller's own bad input). */
        public void ignore() {
            permit.ignore();
        }

        @Override
        public void close() {
            permit.ignore(); // no-op when already settled
            if (ticket != null) {
                ticket.close();
            }
        }
    }
}
