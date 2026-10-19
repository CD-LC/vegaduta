package com.agenticai.core.agents.budget;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one-shot guards for a hosted turn's TWO up-front reservations - the
 * per-tenant token reservation ({@code
 * FreeTierUsageLimiterService#checkAndReserveTokenBudget}) and the per-user
 * dollar reservation ({@code PerUserBudgetService#checkAndReserveBudget}) -
 * created once per turn by {@code
 * FreeTierChatClientService#buildChainForFreeTenant} and shared by EVERY code
 * path that can release/reconcile either reservation:
 *
 * <ul>
 *   <li>the turn-shared {@code FreeTierTokenTrackingAdvisor} instance,</li>
 *   <li>every sibling per-link {@link PerUserBudgetTrackingAdvisor} instance
 *       (fresh instance per {@code HostedChainLink}, but ONE reservation per
 *       turn - see that class's javadoc),</li>
 *   <li>{@code AgentChatService}'s outer {@code HostedPoolExhaustedException}
 *       release ({@code FreeTierChatClientService#releaseReservations}),</li>
 *   <li>and the advisors of a link rebuilt mid-turn by {@code
 *       FreeTierChatClientService#rebuildLinkAfterSelfHeal}.</li>
 * </ul>
 *
 * <p><b>Why instance-scoped guards were not enough (2026-08-12 fix):</b> the
 * previous one-shot guards lived on each advisor instance, which only
 * deduplicated the extra same-turn calls routed through that SAME instance
 * (judge/guardrail/etc. - the 2026-08-11 fix). They could not deduplicate
 * across the OTHER holders of the same reservation, and the mid-stream idle
 * timeout ({@code Flux.timeout} in {@code AgentChatService#streamFast})
 * exposed exactly that: the timeout CANCELS the current link's stream (firing
 * the advisors' {@code doOnCancel}, which releases both reservations in
 * full), then surfaces as a retryable 408 - so the turn is NOT over. If the
 * chain then exhausted, the outer {@code releaseReservations} released the
 * SAME reservations a second time (driving the token windows negative via
 * the unfloored delta INCRBY); if a later link instead succeeded, that link's
 * own {@code PerUserBudgetTrackingAdvisor} instance - guard untouched -
 * reconciled the same {@code reservedMicroCents} again. Turn-scoped guards
 * shared across all of the above make "the reservation is touched at most
 * once per turn" true by construction for every interleaving: whichever
 * holder reaches a terminal signal first consumes the guard, and everyone
 * later degrades to the documented {@code <= 0} additive/no-op branch of the
 * respective reconcile method.
 *
 * <p>CAS rather than plain booleans for the same thread-hop reason documented
 * on the advisors: complete/cancel land on Reactor threads while the outer
 * release and follow-up {@code adviseCall}s may run on different ones.
 * Deliberately consumed even if the release call that follows then throws -
 * same fail-open trade the 2026-08-11 fix documents on {@link
 * FreeTierTokenTrackingAdvisor#reconcile}.
 */
public final class TurnReservationGuards {

    private final AtomicBoolean tokenReservationConsumed = new AtomicBoolean(false);
    private final AtomicBoolean spendReservationConsumed = new AtomicBoolean(false);

    /** True exactly once - the caller that wins may touch the turn's token reservation. */
    public boolean consumeTokenReservation() {
        return tokenReservationConsumed.compareAndSet(false, true);
    }

    /** True exactly once - the caller that wins may touch the turn's per-user dollar reservation. */
    public boolean consumeSpendReservation() {
        return spendReservationConsumed.compareAndSet(false, true);
    }
}
