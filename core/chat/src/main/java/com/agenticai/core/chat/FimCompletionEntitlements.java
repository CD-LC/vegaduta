package com.agenticai.core.chat;

/**
 * The seam through which {@link FimCompletionService} charges a code
 * completion against the SAME meters every other platform-paid turn already
 * goes through - {@code FreeTierUsageLimiterService} (messages/day and
 * tokens/day) and {@code PerUserBudgetService} (the per-user dollar window
 * that answers 402). This interface contains no counting logic of its own
 * and must never grow any: it is an adapter port, not a second meter.
 *
 * <p><b>Why it is an interface instead of those two services injected
 * directly.</b> Both live in {@code core/agents}, and {@code core/agents}
 * already depends on {@code core/chat} (see {@code core/agents/pom.xml}'s
 * first dependency). Injecting them here would close a Maven module cycle
 * and fail the build. So the port is declared in the module that needs it
 * and the single implementation - which does nothing but delegate to {@code
 * FreeTierChatClientService}/{@code PerUserBudgetService} - is registered as
 * a bean from {@code core/agents}, the same direction {@code
 * core/agents} already wires everything else in this module.
 *
 * <p><b>Fail-closed.</b> When no implementation is on the context, {@link
 * FimCompletionService} refuses to serve rather than serving unmetered: this
 * endpoint spends the platform's own money on every keystroke-loop request,
 * and an unmetered completion endpoint is a cost incident, not a degraded
 * feature.
 */
public interface FimCompletionEntitlements {

    /**
     * Consumes the request's message unit and reserves up to {@code
     * maxTokens} of token and dollar budget for the caller in the CURRENT
     * {@code TenantContext}, before any provider is touched.
     *
     * @return the handle every path must then either {@link Lease#settle}
     *     or {@link Lease#abandon} exactly once.
     * @throws com.agenticai.core.security.TenantLimitExceededException when
     *     the tenant is out of quota, or - as its {@code
     *     PerUserBudgetExhaustedException} subclass, which {@code
     *     GlobalExceptionHandler} renders as HTTP 402 - when the caller's
     *     own dollar window is exhausted. Both propagate out of the
     *     controller untouched so the existing handler produces the existing
     *     response shape.
     */
    Lease reserve(int maxTokens);

    /**
     * One request's outstanding reservation. Exactly one terminal call per
     * lease.
     *
     * <p><b>An implementation must be callable from another thread, after the
     * originating request has returned.</b> {@link FimCompletionService}
     * answers an editor empty once its wall-clock deadline passes, but the
     * upstream call is still in flight and the provider will still bill what
     * it produces - so on that path the lease is handed to the in-flight call
     * and terminated from the pool worker when the call finally lands, with
     * the real usage. Concretely: capture whatever identifies the caller
     * (tenant, user, modality, reserved amounts) at {@link #reserve} time on
     * the request thread rather than reading it back from request-scoped
     * state, and make the terminal call thread-safe. The service re-applies
     * the originating {@code TenantContext} on that thread before calling
     * either method, so schema-per-tenant work inside them still routes
     * correctly; nothing else about the request survives.
     */
    interface Lease {

        /**
         * The call happened: correct the reservation to what the model
         * really used. Either token count may be null - a provider that
         * reports no usage is priced by the same fallback the rest of the
         * platform uses ({@code PerUserBudgetService#recordSpendForCall}).
         */
        void settle(String model, Long promptTokens, Long completionTokens);

        /**
         * The call did not happen, or produced nothing chargeable (empty
         * pool, no free worker, upstream failure, a response with no output):
         * give back the message unit and release both reservations in full -
         * the same refund path {@code FreeTierChatClientService} takes when a
         * turn fails before any provider answers.
         *
         * <p>Note what is NOT on that list: a missed deadline. A call that
         * was sent and then outran the deadline is billed by the provider
         * whatever the editor was told, so it is settled when it lands, not
         * refunded - see this interface's {@link Lease} note and {@link
         * FimCompletionService}'s class javadoc.
         */
        void abandon();
    }
}
