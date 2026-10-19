package com.agenticai.core.agents.budget;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.security.TenantPlan;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 - the post-call other half of
 * {@link PerUserBudgetService#checkAndReserveBudget} (the pre-call gate).
 * Layered onto the platform-hosted {@link org.springframework.ai.chat.client.ChatClient} a
 * metered turn actually uses (see {@link
 * FreeTierChatClientService#buildClientForFreeTenant}), exactly like {@link
 * FreeTierTokenTrackingAdvisor} sits next to {@code
 * FreeTierUsageLimiterService#reconcileTokenReservation} - this reads the
 * call's real usage metadata and feeds it to {@link
 * PerUserBudgetService#reconcileSpendReservationForUsage} - the {@link
 * org.springframework.ai.chat.metadata.Usage}-carrying overload, since
 * 2026-10-05, so the billable cache buckets and out-of-completion reasoning
 * tokens reach the budget with the same arithmetic {@code llm_call_log}
 * prices - which turns tokens into dollars, corrects the reservation {@link
 * PerUserBudgetService#checkAndReserveBudget} made to the real cost, and
 * decrements/increments the (tenant, user, modality) window by the delta.
 *
 * <p>{@code tenantId}/{@code userKey}/{@code modality}/{@code model} are all
 * captured once at construction (per-call client, never shared across
 * tenants/users), so this carries none of {@code LlmMetricsAdvisor}'s
 * thread-hop/{@code TenantContext}-recapture complexity - same simplification
 * {@code FreeTierTokenTrackingAdvisor} documents.
 *
 * <p><b>Only ever reconciles on SUCCESS, deliberately - see {@link
 * FreeTierTokenTrackingAdvisor}'s class javadoc for the full reasoning.</b>
 * Unlike that advisor this one is NOT shared across chain links (a fresh
 * instance per {@code HostedChainLink}, since it needs each link's own
 * {@code model} to price correctly on success), but the SAME
 * {@code reservedMicroCents} value - and, since 2026-08-12, the SAME
 * turn-scoped {@link TurnReservationGuards} - is threaded into every one of
 * them for a given turn, so the ONE reservation is reconciled at most once
 * regardless of how many sibling instances reach a terminal signal (see the
 * guard-scope paragraph below for why "at most one ever does" was NOT a safe
 * assumption). The turn's other possible outcome (every link fails) is
 * released at the outer level by {@code AgentChatService}'s existing {@code
 * HostedPoolExhaustedException} handling - through the same shared guard.
 *
 * <p>2026-08-09 leak fix: same {@code doOnCancel} addition as {@link
 * FreeTierTokenTrackingAdvisor} - a client-disconnect cancel is not a
 * retryable link-level error, it ends the whole turn, so releasing the
 * reservation here can never double- or under-count.
 *
 * <p><b>2026-08-11 double-reconciliation fix - same one-shot guard as {@link
 * FreeTierTokenTrackingAdvisor} (see its class javadoc for the full write-up),
 * here for the dollar reservation.</b> The "at most one link's stream ever
 * completes" reasoning above covers the FIRST terminal signal only - the
 * winning link's ChatClient (this advisor still attached as a default) is
 * then reused for EXTRA same-turn calls (quality judge, consistency checker,
 * reflexion, task planning, toxicity guardrail, entity extraction), each of
 * which re-entered {@link #adviseCall}/{@link #adviseStream} on THIS instance
 * and re-reconciled the SAME {@code reservedMicroCents}, additively releasing
 * the one dollar reservation once per extra call. Now only the turn's
 * first terminal reconciliation sees the reservation; every later one passes
 * {@code reservedMicroCents=0}, which {@link
 * PerUserBudgetService#reconcileSpendReservationForUsage} documents as "fall
 * back to plain additive {@code recordSpendForUsage}" - the extra calls' real
 * cost still lands, the reservation is never re-touched.
 *
 * <p><b>2026-08-12 guard-scope fix - the guard is TURN-scoped and shared
 * across all sibling link instances ({@link TurnReservationGuards}), not
 * per-instance.</b> The original per-instance guard leaned on "at most one
 * link ever reaches a terminal success/cancel", which the mid-stream idle
 * timeout falsifies: {@code Flux.timeout} in {@code
 * AgentChatService#streamFast} CANCELS the current link's stream (firing
 * this advisor's {@code doOnCancel}, which released the full {@code
 * reservedMicroCents}) and then surfaces as a retryable 408, so a LATER
 * sibling link can still reach its own terminal success - and its own
 * instance's unconsumed guard let it reconcile the same reservation again,
 * crediting the user the full reservation per timeout-then-success event.
 * The shared {@code FreeTierTokenTrackingAdvisor} (one instance across the
 * whole chain) already survived that sequence correctly, which is exactly
 * the shape the shared guard restores here: one reservation, one turn-wide
 * guard, whichever sibling (or the outer pool-exhausted release) terminates
 * first consumes it.
 */
public class PerUserBudgetTrackingAdvisor implements CallAdvisor, StreamAdvisor {

    private final PerUserBudgetService budgetService;
    private final String tenantId;
    private final String userKey;
    private final Modality modality;
    private final String model;
    /** See {@link PerUserBudgetService#reconcileSpendReservationForUsage}'s
     * {@code reservedMicroCents} parameter - {@code <= 0} means nothing was
     * reserved for this turn (falls back to a plain additive record). */
    private final long reservedMicroCents;
    private final TenantPlan knownPlan;
    /** 2026-08-11 double-reconciliation fix, re-scoped to the turn on
     * 2026-08-12 (see class javadoc): the SAME instance is shared by every
     * sibling link's advisor and the outer pool-exhausted release, so only
     * the turn's first terminal reconciliation anywhere sees the
     * reservation; every later one behaves as if nothing was reserved (pure
     * additive record). */
    private final TurnReservationGuards guards;

    /** V484 - see the nine-argument constructor. */
    private final boolean zeroCost;

    /** Convenience for tests/simple callers - a private, per-instance guard
     * (correct only when this is the turn's sole reservation holder). */
    public PerUserBudgetTrackingAdvisor(
        PerUserBudgetService budgetService, String tenantId, String userKey, Modality modality, String model,
        long reservedMicroCents, TenantPlan knownPlan
    ) {
        this(budgetService, tenantId, userKey, modality, model, reservedMicroCents, knownPlan, new TurnReservationGuards());
    }

    public PerUserBudgetTrackingAdvisor(
        PerUserBudgetService budgetService, String tenantId, String userKey, Modality modality, String model,
        long reservedMicroCents, TenantPlan knownPlan, TurnReservationGuards guards
    ) {
        this(budgetService, tenantId, userKey, modality, model, reservedMicroCents, knownPlan, guards, false);
    }

    /**
     * V484 - {@code zeroCost} marks a turn answered by a hosted-pool row the operator runs
     * themselves: at the end of the turn the up-front reservation (made before the chain knew which
     * row would answer) is released in full and nothing is recorded as spend.
     */
    public PerUserBudgetTrackingAdvisor(
        PerUserBudgetService budgetService, String tenantId, String userKey, Modality modality, String model,
        long reservedMicroCents, TenantPlan knownPlan, TurnReservationGuards guards, boolean zeroCost
    ) {
        this.zeroCost = zeroCost;
        this.budgetService = budgetService;
        this.tenantId = tenantId;
        this.userKey = userKey;
        this.modality = modality;
        this.model = model;
        this.reservedMicroCents = reservedMicroCents;
        this.knownPlan = knownPlan;
        this.guards = guards;
    }

    @Override
    public String getName() {
        return "PerUserBudgetTrackingAdvisor";
    }

    @Override
    public int getOrder() {
        // Reads the response independently and mutates nothing, so ordering
        // relative to the other advisors on this client doesn't matter.
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
        reconcile(usageOf(response == null ? null : response.chatResponse()));
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // 2026-10-05 cost-tracing audit fix: usage is MERGED across chunks,
        // not read off the last one - the same StreamUsageAccumulator
        // LlmMetricsAdvisor feeds the durable llm_call_log row and the
        // llm.cost.usd counter from. Reading only the final chunk undercounted
        // every provider that splits usage across events (Anthropic reports
        // input tokens on message_start and output on message_delta - the last
        // chunk carried output only, so a streamed hosted call debited the
        // dollar budget for completion tokens alone) and dropped the merged
        // chunk's native usage that carries billable cache/reasoning counts.
        LlmMetricsAdvisor.StreamUsageAccumulator usage = new LlmMetricsAdvisor.StreamUsageAccumulator();
        return streamAdvisorChain.nextStream(chatClientRequest)
            .doOnNext(usage::accept)
            .doOnComplete(() -> reconcile(usage.merged()))
            .doOnCancel(() -> reconcile(usage.merged()));
        // Still no doOnError hook - deliberately (see class javadoc).
    }

    private static Usage usageOf(ChatResponse chatResponse) {
        return (chatResponse == null || chatResponse.getMetadata() == null) ? null : chatResponse.getMetadata().getUsage();
    }

    private void reconcile(Usage usage) {
        // A null usage (no usage metadata at all - a partial/failed response
        // typically carries none) flows straight through to
        // reconcileSpendReservationForUsage exactly like a real call with 0
        // tokens would: no price to derive, so it releases the full
        // reservation (or no-ops if nothing was reserved) - no separate
        // branch needed here.
        //
        // 2026-08-08 edge-case audit: a provider that bills for a partial
        // completion before erroring mid-stream is NOT reconciled against
        // its real partial usage here - Spring AI's ChatResponse is only
        // ever produced on a successful onNext; an error/cancel signal in
        // this Flux carries a Throwable (adviseStream) or simply never
        // completes normally (no ChatResponse either way), never a
        // partial-usage payload, so there is nothing to reconcile against.
        // This is an accepted limitation, not a missed hook: the reservation
        // this releases in full is itself bounded by the (tenantId, userKey,
        // modality) window's TTL (see PerUserBudgetService#checkAndReserveBudget),
        // so the worst case is a bounded, self-correcting over-count for the
        // rest of that window - never an unbounded leak.
        // 2026-08-11 double-reconciliation fix, turn-scoped since 2026-08-12
        // (see class javadoc): only the turn's first terminal reconciliation -
        // across all sibling link instances and the outer pool-exhausted
        // release - may see the reservation; later ones (a sibling link after
        // an idle-timeout cancel, or extra same-turn calls through the same
        // client) pass 0, which the service treats as "nothing reserved,
        // record additively", so their real cost still lands without
        // re-releasing the reservation.
        if (zeroCost) {
            // V484 - nothing to price: give back whatever was reserved up front, once.
            if (guards.consumeSpendReservation() && reservedMicroCents > 0) {
                budgetService.releaseSpendReservation(tenantId, userKey, modality, reservedMicroCents, knownPlan);
            }
            return;
        }
        long reservedForThisReconcile = guards.consumeSpendReservation() ? reservedMicroCents : 0L;
        budgetService.reconcileSpendReservationForUsage(
            tenantId, userKey, modality, reservedForThisReconcile, model, usage, knownPlan);
    }
}
