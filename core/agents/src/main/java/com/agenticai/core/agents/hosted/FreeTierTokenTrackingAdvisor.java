package com.agenticai.core.agents.hosted;

import com.agenticai.core.agents.budget.TurnReservationGuards;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.LlmMetricsAdvisor;
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
 * #972 - "There should be limit on the tokens and messages they send." A
 * second advisor, layered ONLY onto the {@link org.springframework.ai.chat.client.ChatClient}
 * a FREE-tier tenant's call actually uses (see {@link FreeTierChatClientService#buildClientForFreeTenant}
 * - it's added via {@code .mutate().defaultAdvisors(...)} on top of the
 * client {@code HostedLlmChatClientFactory} already builds, alongside {@code
 * LlmMetricsAdvisor} which every hosted-pool call already goes through for
 * cost/latency logging). This one exists purely to feed real post-call token
 * counts back into {@link FreeTierUsageLimiterService#reconcileTokenReservation} -
 * {@link FreeTierUsageLimiterService#checkAndReserveTokenBudget} is the
 * pre-call gate that reserved {@code reservedTokens} against the same
 * counters this advisor now corrects to the real total.
 *
 * <p>Deliberately NOT merged into {@code LlmMetricsAdvisor} itself (that
 * class lives in {@code core.chat}, which cannot depend on {@code
 * core.agents} - {@code core.agents} depends on {@code core.chat}, not the
 * other way around, per this codebase's module layering; see {@code
 * PlatformHostedLlmProvider}'s javadoc for the same kind of layering
 * constraint elsewhere). {@code tenantId} is captured once at construction
 * (by {@link FreeTierChatClientService}, which already knows it - one
 * {@link org.springframework.ai.chat.client.ChatClient} per FREE-tier call,
 * never shared across tenants) rather than read from thread-local {@code
 * TenantContext} the way {@code LlmMetricsAdvisor} has to - so this class
 * carries none of that advisor's thread-hop/context-recapture complexity.
 *
 * <p><b>2026-08-08 usage-counter audit fix - only ever reconciles on SUCCESS,
 * deliberately.</b> This advisor is shared across every rung of a fallback
 * chain (one instance, reused across every {@code HostedChainLink} - see
 * {@code FreeTierChatClientService#buildChainForFreeTenant}). A chain link
 * that fails and gets retried is NOT the turn's outcome - only whichever link
 * (if any) actually succeeds is, and only {@link #adviseStream}'s {@code
 * doOnComplete}/{@link #adviseCall}'s normal return fire for that case;
 * Reactor's {@code doOnComplete} never fires on an error signal, so a failed
 * link's stream simply reconciles nothing, exactly as intended - reconciling
 * on ITS failure too would either release the reservation before a LATER
 * link in the same chain succeeds (a real usage record would then land with
 * no reservation left to correct, silently under-counting) or, if reconciled
 * again on that later success, double-release it. The turn's OTHER possible
 * outcome - every link in the chain fails and the reservation must be
 * released - is handled once, at the OUTER level, by {@code
 * AgentChatService}'s existing {@code HostedPoolExhaustedException} handling
 * (see {@code HostedChainLink#reservedTokens}) - never here.
 *
 * <p><b>2026-08-09 leak fix - cancel is NOT the same case as error, and
 * needed its own hook.</b> A client disconnecting mid-stream (browser
 * navigated away, or the request-lifecycle connect/idle timeout on the web
 * client aborting its fetch) delivers Reactor's third terminal signal -
 * cancel - to whichever link is currently subscribed, and {@code
 * doOnComplete} never fires for it (same as it never fires for an error).
 * Unlike a link-level error, a cancel is NOT retryable - it tears down the
 * whole chain, not just the current link, so there is no later link that
 * might still succeed and no risk of double- or under-counting by releasing
 * here. Before this fix nothing ever reconciled a cancelled turn's
 * reservation, so it sat charged against the tenant's daily/monthly token
 * window for the rest of that window's TTL on every client-side abort -
 * quietly burning real quota against zero delivered tokens.
 *
 * <p><b>2026-08-11 double-reconciliation fix - the reservation is consumed
 * exactly once per advisor INSTANCE; every later reconciliation is purely
 * additive.</b> The turn's {@link org.springframework.ai.chat.client.ChatClient}
 * (with this advisor attached as a default) is reused for EXTRA same-turn
 * LLM calls beyond the user-facing answer: {@code LlmQualityJudgeService}'s
 * sampled judge, {@code AnswerConsistencyChecker}/{@code ReflexionService}/
 * {@code TaskPlanningService} on the deeper reasoning modes, the outbound
 * {@code ToxicityGuardrail}, and {@code EntityExtractionService} via {@code
 * remember_fact}. Each of those re-enters {@link #adviseCall}/{@link
 * #adviseStream} and previously re-ran {@link
 * FreeTierUsageLimiterService#reconcileTokenReservation} with the SAME
 * captured {@code reservedTokens} - and since that method adds {@code
 * actual - reserved} to the counters each time, every extra call released
 * the one reservation AGAIN (a ~500-token judge call against a ~10k
 * reservation credited ~9.5k phantom tokens back to the tenant, per extra
 * call). The {@link #reservationConsumed} one-shot guard makes only the
 * FIRST terminal reconciliation (stream complete, cancel, or the first
 * {@code adviseCall} return) see the reservation; every subsequent one
 * passes {@code reservedTokens=0}, which the service documents as "fall
 * back to a plain additive {@code recordTokenUsage}" - so the extra calls'
 * REAL usage still counts (they are real hosted-pool spend) without ever
 * re-touching the reservation. The no-doOnError rule above is untouched - a
 * failed link still reconciles nothing and does NOT consume the guard, so
 * the reservation stays intact for a later link's success.
 *
 * <p><b>2026-08-12 guard-scope fix - the guard is TURN-scoped ({@link
 * TurnReservationGuards}), not instance-scoped.</b> Instance state looked
 * like the correct once-per-reservation scope for this advisor (one shared
 * instance per turn's chain), but the same reservation is ALSO releasable by
 * {@code AgentChatService}'s outer {@code HostedPoolExhaustedException} path
 * ({@code FreeTierChatClientService#releaseReservations}) and by the
 * advisors of a mid-turn self-heal-rebuilt link - and the mid-stream idle
 * timeout ({@code Flux.timeout} cancelling a link that then fails over as a
 * retryable 408) makes cancel-then-exhaustion / cancel-then-success real
 * interleavings, not hypotheticals. All of those holders now share the ONE
 * {@link TurnReservationGuards} instance, so whichever reaches a terminal
 * signal first consumes the reservation and everyone later is additive -
 * see that class's javadoc for the full write-up.
 */
public class FreeTierTokenTrackingAdvisor implements CallAdvisor, StreamAdvisor {

    private final FreeTierUsageLimiterService usageLimiterService;
    private final String tenantId;
    /** See {@link FreeTierUsageLimiterService#reconcileTokenReservation}'s
     * {@code reservedTokens} parameter - {@code <= 0} means nothing was
     * reserved for this turn (falls back to a plain additive record). */
    private final long reservedTokens;
    /** 2026-08-11 double-reconciliation fix, re-scoped to the turn on
     * 2026-08-12 (see class javadoc): only the first terminal reconciliation
     * across ALL of the turn's reservation holders may see the reservation;
     * every later one behaves as if nothing was reserved (pure additive
     * record). */
    private final TurnReservationGuards guards;

    public FreeTierTokenTrackingAdvisor(FreeTierUsageLimiterService usageLimiterService, String tenantId, long reservedTokens) {
        this(usageLimiterService, tenantId, reservedTokens, new TurnReservationGuards());
    }

    public FreeTierTokenTrackingAdvisor(
        FreeTierUsageLimiterService usageLimiterService, String tenantId, long reservedTokens, TurnReservationGuards guards
    ) {
        this.usageLimiterService = usageLimiterService;
        this.tenantId = tenantId;
        this.reservedTokens = reservedTokens;
        this.guards = guards;
    }

    @Override
    public String getName() {
        return "FreeTierTokenTrackingAdvisor";
    }

    @Override
    public int getOrder() {
        // After LlmMetricsAdvisor (order 0) in the chain doesn't matter for
        // correctness here (each advisor reads the response independently,
        // neither mutates it) - left at the default ordering position.
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
        // input tokens on message_start and output on message_delta - the
        // last chunk carried output only), so the tenant's token-window quota
        // reconciled less than the call actually consumed and the durable row
        // recorded.
        LlmMetricsAdvisor.StreamUsageAccumulator usage = new LlmMetricsAdvisor.StreamUsageAccumulator();
        return streamAdvisorChain.nextStream(chatClientRequest)
            .doOnNext(usage::accept)
            .doOnComplete(() -> reconcile(usage.merged()))
            // 2026-08-09 leak fix - see class javadoc's "cancel is NOT the
            // same case as error" paragraph: a cancel always ends the whole
            // turn (never just this link), so releasing here can never
            // double- or under-count the way reconciling on a retryable
            // link-level error would.
            .doOnCancel(() -> reconcile(usage.merged()));
        // Still no doOnError hook - deliberately (see class javadoc): a link
        // that errors out is not the turn's outcome when the chain retries,
        // and reconciling here would double- or under-count against a later
        // link's own reconciliation. A partial/failed response typically
        // carries no usage metadata anyway (unchanged reasoning from before
        // this fix).
    }

    private static Usage usageOf(ChatResponse chatResponse) {
        return (chatResponse == null || chatResponse.getMetadata() == null) ? null : chatResponse.getMetadata().getUsage();
    }

    private void reconcile(Usage usage) {
        // 2026-08-08 edge-case audit: same accepted limitation documented on
        // PerUserBudgetTrackingAdvisor#reconcile - a provider that bills for
        // a partial completion before erroring mid-stream has no partial
        // usage captured here, because Spring AI never hands this Flux a
        // ChatResponse on an error/cancel signal to read tokens off of in
        // the first place (only doOnComplete/onNext ever see one). The
        // reservation this releases in full is bounded by the daily/monthly
        // Redis window TTL (see FreeTierUsageLimiterService#checkAndReserveTokenBudget),
        // so an uncaptured partial cost is a bounded, self-correcting
        // over-count for the rest of that window, not an unbounded leak.
        long actualTokens = usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens().longValue();
        // 2026-08-11 double-reconciliation fix, turn-scoped since 2026-08-12
        // (see class javadoc): only the turn's first terminal reconciliation -
        // across this instance, the outer pool-exhausted release, and any
        // self-heal-rebuilt link's advisor - may see the reservation; later
        // ones (extra same-turn calls through the same client: judge,
        // consistency checker, guardrails, entity extraction) pass 0, which
        // the service treats as "nothing reserved, record additively". The
        // CAS is deliberately consumed even if the service call below then
        // throws: fail-open bookkeeping (the catch) already accepts losing
        // ONE reconciliation to a store hiccup, and re-arming would risk the
        // far worse outcome this fix exists to prevent - releasing the same
        // reservation twice on a later call.
        long reservedForThisReconcile = guards.consumeTokenReservation() ? reservedTokens : 0L;
        try {
            usageLimiterService.reconcileTokenReservation(tenantId, reservedForThisReconcile, actualTokens);
        } catch (RuntimeException e) {
            // Never let a bookkeeping failure break the actual response the
            // person is looking at - same fail-open posture every write in
            // FreeTierUsageLimiterService already takes.
        }
    }
}
