package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.hosted.HostedPoolExhaustedException;
import com.agenticai.core.agents.hosted.HostedProviderHealth;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.resilience.CircuitBreaker;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Platform-hosted pool fallback chain and invalid-model self-heal, extracted verbatim from {@link
 * AgentChatService} (extract-only split). {@code AgentChatService} keeps package-private delegates
 * for the entry points its tests drive.
 */
final class HostedChainFailover {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final ObjectProvider<PlatformHostedLlmProviderService> platformHostedLlmProviderService;
    private final FreeTierChatClientService freeTierChatClientService;

    /** Runs the invalid-model self-heal; never the common ForkJoinPool (JAVA-008). */
    private final java.util.concurrent.Executor selfHealExecutor;

    HostedChainFailover(
            ObjectProvider<PlatformHostedLlmProviderService> platformHostedLlmProviderService,
            FreeTierChatClientService freeTierChatClientService,
            java.util.concurrent.Executor selfHealExecutor) {
        this.platformHostedLlmProviderService = platformHostedLlmProviderService;
        this.freeTierChatClientService = freeTierChatClientService;
        this.selfHealExecutor = selfHealExecutor;
    }

    /**
     * P3 (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §6 + Appendix A) - runs one turn against the
     * platform-hosted pool's fallback {@code chain}. This client-only form is the identity-free
     * adapter over the real implementation below: it is what {@code
     * AgentChatServiceHostedChainTest} drives (opaque marker clients, no pool rows, no Spring AI
     * graph), and it runs with no {@link HostedProviderHealth}, so no circuit breaking and no
     * latency observation happen through it.
     *
     * <p>Package-private static (not private) for exactly that test seam - it uses no instance
     * state (only the static logger and its arguments).
     */
    static Flux<String> attemptHostedChain(
            List<ChatClient> chain,
            int index,
            Throwable originalError,
            Function<ChatClient, Flux<String>> attempt,
            UUID agentId,
            String sessionId) {
        return attemptHostedChain(
                chain.stream()
                        .map(client -> new HostedChainLink(client, null, "provider", null, 0, 0, 0))
                        .toList(),
                index,
                originalError,
                attempt,
                agentId,
                sessionId,
                null,
                null,
                null);
    }

    /**
     * The identity-aware chain used by the real hosted path - same control flow, plus the three
     * things the client-only overload structurally cannot do: name the provider that failed, decide
     * whether the failure is even worth another provider ({@link HostedFailoverPolicy}), and feed
     * {@link HostedProviderHealth} so a repeatedly-dead row stops costing every subsequent request
     * a full timeout.
     *
     * <p>Behaviour changes made here on top of the original P3 implementation, all driven by the
     * live symptom (a single-row pool whose Gemini model id Google 404s, surfacing a hard error in
     * the playground):
     *
     * <ul>
     *   <li>The chain wrapper now runs for EVERY hosted turn, including a one-row pool. It used to
     *       be applied only when {@code size() > 1}, which meant the common production shape got no
     *       classification, no health tracking, and the raw last-provider error.
     *   <li>Advancement is gated on {@link HostedFailoverPolicy#isRetryable} rather than "any
     *       {@link UpstreamProviderException}", so a content-policy refusal or an over-length
     *       prompt fails FAST with its own actionable message instead of being replayed against
     *       every provider first.
     *   <li>An exhausted chain no longer surfaces the primary provider's raw error verbatim - see
     *       {@link HostedPoolExhaustedException} for why "choose a different model for this agent"
     *       is the wrong thing to tell someone who does not choose the model.
     * </ul>
     *
     * <p>Everything the original discipline guaranteed still holds: flat and bounded (≤ {@code
     * chain.size()} attempts, never loops), never switches mid-stream (once a token is emitted the
     * response is committed to that provider), and non-provider failures (guardrail, tool step
     * limit) pass straight through untouched.
     *
     * @param health nullable - {@code null} from the client-only overload and from tests that drive
     *     the control flow with marker clients.
     * @param rungFailureListener nullable - notified (best-effort, never allowed to break the
     *     chain) for every pre-token rung failure, alongside the {@link HostedProviderHealth}
     *     write. The real chat path passes {@link #maybeSelfHealInvalidModelRow}, which re-runs
     *     model auto-selection for a pool row whose model id the upstream rejected as
     *     unknown/retired; {@code null} from the client-only overload and from tests.
     * @param exhaustedChainSyncHeal nullable - invoked ONLY on the terminal (chain-exhausting)
     *     failure, i.e. exactly once per turn at most. Where {@code rungFailureListener} kicks off
     *     self-heal in the BACKGROUND (fixes the NEXT turn), this one runs synchronously and, when
     *     it returns a non-null {@link HostedChainLink}, THIS turn is retried once more against it
     *     before the pool is declared exhausted - see {@code
     *     AgentChatService#syncHealChainExhaustionAndRebuild}. {@code null} from the client-only
     *     overload and from tests.
     */
    static Flux<String> attemptHostedChain(
            List<HostedChainLink> chain,
            int index,
            Throwable originalError,
            Function<ChatClient, Flux<String>> attempt,
            UUID agentId,
            String sessionId,
            HostedProviderHealth health,
            BiConsumer<HostedChainLink, Throwable> rungFailureListener,
            BiFunction<HostedChainLink, Throwable, HostedChainLink> exhaustedChainSyncHeal) {
        return attemptHostedChain(
                chain,
                index,
                originalError,
                attempt,
                agentId,
                sessionId,
                health,
                rungFailureListener,
                exhaustedChainSyncHeal,
                null);
    }

    /**
     * As above, with a replay gate. Failing over re-runs the turn on the next rung, tools included, and
     * "this rung emitted no token" does not mean nothing happened: a tool-calling turn emits no token
     * while it works. {@code replayBlockedBy} is asked before a retryable failure advances the chain
     * (and before the synchronous self-heal retry, which replays the turn too); a non-empty answer names
     * the tools that already ran, and the failure is surfaced as it is instead of replaying them.
     *
     * @param replayBlockedBy nullable; returns the tools that stand in the way of a replay, or
     *     {@code null}/empty when the turn may be run again
     */
    static Flux<String> attemptHostedChain(
            List<HostedChainLink> chain,
            int index,
            Throwable originalError,
            Function<ChatClient, Flux<String>> attempt,
            UUID agentId,
            String sessionId,
            HostedProviderHealth health,
            BiConsumer<HostedChainLink, Throwable> rungFailureListener,
            BiFunction<HostedChainLink, Throwable, HostedChainLink> exhaustedChainSyncHeal,
            Supplier<String> replayBlockedBy) {
        // Captured HERE, on the caller's thread (the request thread for the
        // index-0 call every production path makes), and threaded through the
        // recursion explicitly: every later rung and the self-heal retry run
        // inside onErrorResume / on boundedElastic, where the plain-ThreadLocal
        // TenantContext is absent - re-capturing there would capture nothing.
        return attemptHostedChain(
                chain,
                index,
                originalError,
                attempt,
                agentId,
                sessionId,
                health,
                rungFailureListener,
                exhaustedChainSyncHeal,
                TenantScope.capture(),
                replayBlockedBy);
    }

    /**
     * Subscribes to {@code source} with {@code scope}'s tenant context in force on the subscribing
     * thread, so everything that runs synchronously at subscription time - {@code attempt.apply}'s
     * ChatClient request assembly and the advisors that read {@code TenantContext} (metrics
     * attribution, the LLM-gateway content policy, governance) - sees the originating tenant rather
     * than none. An empty scope subscribes exactly as {@code Flux.defer} would.
     */
    static <T> Flux<T> subscribeInScope(TenantScope scope, Supplier<Flux<T>> source) {
        return TenantScopedFlux.subscribeInScope(scope, source);
    }

    private static Flux<String> attemptHostedChain(
            List<HostedChainLink> chain,
            int index,
            Throwable originalError,
            Function<ChatClient, Flux<String>> attempt,
            UUID agentId,
            String sessionId,
            HostedProviderHealth health,
            BiConsumer<HostedChainLink, Throwable> rungFailureListener,
            BiFunction<HostedChainLink, Throwable, HostedChainLink> exhaustedChainSyncHeal,
            TenantScope tenantScope,
            Supplier<String> replayBlockedBy) {
        HostedChainLink link = chain.get(index);
        // Per-row circuit breaker (app.resilience.circuit-breaker.enabled, default ON; with the
        // flag off the permit is
        // null and nothing below changes). An OPEN breaker skips the row exactly like a row that
        // just
        // failed - same recursion, no call made - but only while another row follows: the last row
        // is
        // always attempted so a single-row pool never becomes "no provider available" because of a
        // breaker.
        CircuitBreaker.Permit breakerPermit =
                health == null ? null : health.acquireBreakerPermit(link.healthKey());
        if (breakerPermit != null && !breakerPermit.granted()) {
            if (index < chain.size() - 1) {
                log.debug(
                        "Hosted pool provider '{}' skipped for agent {} session {}: circuit open",
                        link.label(),
                        agentId,
                        sessionId);
                return attemptHostedChain(
                        chain,
                        index + 1,
                        originalError,
                        attempt,
                        agentId,
                        sessionId,
                        health,
                        rungFailureListener,
                        exhaustedChainSyncHeal,
                        tenantScope,
                        replayBlockedBy);
            }
            breakerPermit = null;
        }
        CircuitBreaker.Permit permit = breakerPermit;
        AtomicBoolean emitted = new AtomicBoolean(false);
        Flux<String> attemptFlux =
                subscribeInScope(
                        tenantScope,
                        () -> {
                            long startedAtNanos = System.nanoTime();
                            return attempt.apply(link.client())
                                    .doOnNext(
                                            token -> {
                                                // First element only: proves the provider is alive,
                                                // clears any
                                                // failure streak, and is the one latency
                                                // observation the
                                                // cost/latency ordering has to work with.
                                                if (emitted.compareAndSet(false, true)) {
                                                    if (health != null) {
                                                        health.recordSuccess(
                                                                link.healthKey(),
                                                                (System.nanoTime() - startedAtNanos)
                                                                        / 1_000_000L);
                                                    }
                                                    if (permit != null) {
                                                        permit.success();
                                                    }
                                                }
                                            });
                        });
        if (permit != null) {
            // Only with a live permit (breakers ON and a row that can be shed): with the flag off
            // no
            // operator is added to the chain at all.
            attemptFlux =
                    attemptFlux.doFinally(
                            signal -> {
                                // Cancelled / completed empty before a first token: says nothing
                                // about the provider.
                                // Idempotent - a permit already settled (success or failure)
                                // ignores this.
                                permit.ignore();
                            });
        }
        return attemptFlux.onErrorResume(
                e -> {
                    Throwable firstError = originalError != null ? originalError : e;
                    if (emitted.get()) {
                        // Died mid-answer. Deliberately NOT counted as a health
                        // failure: this provider demonstrably responded, and the
                        // response is already committed to it either way.
                        return Flux.error(e);
                    }
                    if (e
                            instanceof
                            com.agenticai.core.chat.gateway
                                    .LlmGatewayContentPolicyViolationException) {
                        // A tenant content-policy BLOCK is a verdict on the prompt, not a
                        // provider fault: never charge it to shared provider health and
                        // never retry it on the next rung.
                        if (permit != null) {
                            permit.ignore();
                        }
                        return Flux.error(e);
                    }
                    if (permit != null) {
                        // Only failures that are the PROVIDER's count toward its breaker (narrower
                        // than "the next
                        // row gets a chance": 400/409 fail over but are request-shaped, see
                        // countsAgainstBreaker).
                        if (HostedFailoverPolicy.countsAgainstBreaker(e)) {
                            permit.failure(e);
                        } else {
                            permit.ignore();
                        }
                    }
                    if (health != null) {
                        health.recordFailure(link.healthKey());
                    }
                    boolean terminal = index >= chain.size() - 1;
                    // Skipped exactly on the terminal rung when a synchronous heal is
                    // available: firing this async listener here would win the shared
                    // cooldown claim (see claimSelfHealCooldown) before
                    // exhaustedChainSyncHeal below ever gets a chance to run, defeating
                    // the whole point of the synchronous path (healing in time to retry
                    // THIS turn instead of only the next one). Every other rung (there
                    // IS a next link to fall over to this turn) keeps firing it
                    // unchanged - background healing that row is still worth doing.
                    if (rungFailureListener != null
                            && !(terminal && exhaustedChainSyncHeal != null)) {
                        try {
                            tenantScope.run(() -> rungFailureListener.accept(link, e));
                        } catch (RuntimeException listenerFailure) {
                            // Observability/self-heal side-channel only - must never be
                            // what breaks a turn that is already failing over.
                            log.warn(
                                    "Hosted-chain rung-failure listener threw for provider '{}'",
                                    link.label(),
                                    listenerFailure);
                        }
                    }
                    if (!HostedFailoverPolicy.isRetryable(e)) {
                        log.warn(
                                "Hosted pool provider '{}' failed for agent {} session {} with a"
                                    + " non-retryable error ({}) - failing fast, another provider"
                                    + " would reject this the same way",
                                link.label(),
                                agentId,
                                sessionId,
                                e.getMessage());
                        return Flux.error(e);
                    }
                    // Replaying the turn on another rung (or on the self-healed rung) runs it again
                    // from the start. Not after a tool with side effects ran.
                    String toolsThatRan = replayBlockedBy == null ? null : replayBlockedBy.get();
                    if (toolsThatRan != null && !toolsThatRan.isEmpty()) {
                        log.warn(
                                "Hosted pool provider '{}' failed for agent {} session {} ({}) after the turn ran tools"
                                        + " ({}) - not failing over, the next provider would run them again",
                                link.label(),
                                agentId,
                                sessionId,
                                e.getMessage(),
                                toolsThatRan);
                        return Flux.error(e);
                    }
                    if (terminal) {
                        Supplier<Flux<String>> exhausted =
                                () -> {
                                    log.error(
                                            "Hosted pool fallback chain exhausted for agent {}"
                                                + " session {}: all {} enabled provider(s) failed"
                                                + " (last was '{}': {})",
                                            agentId,
                                            sessionId,
                                            chain.size(),
                                            link.label(),
                                            e.getMessage());
                                    return Flux.error(
                                            new HostedPoolExhaustedException(chain, firstError));
                                };
                        if (exhaustedChainSyncHeal == null) {
                            return exhausted.get();
                        }
                        // Last rung, no next link to fall over to - the ONLY point where a
                        // background heal (rungFailureListener, just above) would arrive too
                        // late to help this turn. Runs on boundedElastic (catalog refresh +
                        // DB write, both blocking I/O) so the terminal failure doesn't stall
                        // the reactor event loop while healing.
                        // The heal and the retry both run off the request thread, so
                        // both re-apply the tenant captured at chain entry - without it
                        // the retried turn's advisors (metrics attribution, content
                        // policy, governance) ran tenantless.
                        return Mono.fromCallable(
                                        tenantScope.wrapCallable(
                                                () -> exhaustedChainSyncHeal.apply(link, e)))
                                .subscribeOn(TenantAwareSchedulers.boundedElastic())
                                .onErrorResume(
                                        healThrew -> {
                                            log.warn(
                                                    "Synchronous hosted-pool self-heal threw for"
                                                            + " provider '{}' - failing this turn"
                                                            + " without a retry",
                                                    link.label(),
                                                    healThrew);
                                            return Mono.empty();
                                        })
                                .flatMapMany(
                                        healed -> {
                                            log.warn(
                                                    "Hosted pool provider '{}' self-healed to model"
                                                        + " '{}' for agent {} session {} - retrying"
                                                        + " this turn once against the freshly"
                                                        + " auto-selected model before giving up",
                                                    link.label(),
                                                    healed.model(),
                                                    agentId,
                                                    sessionId);
                                            AtomicBoolean retryEmitted = new AtomicBoolean(false);
                                            long retryStartedAtNanos = System.nanoTime();
                                            return subscribeInScope(
                                                            tenantScope,
                                                            () -> attempt.apply(healed.client()))
                                                    .doOnNext(
                                                            token -> {
                                                                if (retryEmitted.compareAndSet(
                                                                                false, true)
                                                                        && health != null) {
                                                                    health.recordSuccess(
                                                                            healed.healthKey(),
                                                                            (System.nanoTime()
                                                                                            - retryStartedAtNanos)
                                                                                    / 1_000_000L);
                                                                }
                                                            })
                                                    .onErrorResume(
                                                            retryFailure -> {
                                                                if (!retryEmitted.get()
                                                                        && health != null) {
                                                                    health.recordFailure(
                                                                            healed.healthKey());
                                                                }
                                                                log.error(
                                                                        "Hosted pool fallback chain"
                                                                            + " exhausted for agent"
                                                                            + " {} session {} even"
                                                                            + " after a self-healed"
                                                                            + " retry against '{}':"
                                                                            + " {}",
                                                                        agentId,
                                                                        sessionId,
                                                                        healed.label(),
                                                                        retryFailure.getMessage());
                                                                return Flux.error(
                                                                        new HostedPoolExhaustedException(
                                                                                chain, firstError));
                                                            });
                                        })
                                .switchIfEmpty(Flux.defer(exhausted));
                    }
                    log.warn(
                            "Hosted pool provider '{}' (model {}, #{} of {}) failed for agent {}"
                                    + " session {} ({}) - falling over to the next provider in the"
                                    + " chain",
                            link.label(),
                            link.model(),
                            index + 1,
                            chain.size(),
                            agentId,
                            sessionId,
                            e.getMessage());
                    return attemptHostedChain(
                            chain,
                            index + 1,
                            firstError,
                            attempt,
                            agentId,
                            sessionId,
                            health,
                            rungFailureListener,
                            exhaustedChainSyncHeal,
                            tenantScope,
                            replayBlockedBy);
                });
    }

    /**
     * Minimum time between two self-heal attempts for the SAME pool row - a persistently-failing
     * row (e.g. its provider's whole catalog is stale, so auto-selection keeps re-picking the dead
     * model) must not thrash the catalog refresh + control-plane write on every turn. Same
     * in-memory, per-instance posture as {@link HostedProviderHealth} and for the same reason: this
     * is platform-wide shared state consulted on a failure path, a worst case of one redundant heal
     * attempt per replica per cooldown is the acceptable trade for zero hot-path I/O.
     */
    private volatile java.time.Duration selfHealCooldown = new ChatResilienceProperties().getHostedSelfHealCooldown();

    /** Sets {@code app.chat.resilience.hosted-self-heal-cooldown}; until it is called the shipped default applies. */
    void useSelfHealCooldown(java.time.Duration cooldown) {
        this.selfHealCooldown = cooldown == null ? new ChatResilienceProperties().getHostedSelfHealCooldown() : cooldown;
    }

    /**
     * Last self-heal ATTEMPT (not success) per pool row id - see {@link
     * #maybeSelfHealInvalidModelRow}.
     */
    private final Map<Long, Long> hostedModelSelfHealLastAttemptMs = new ConcurrentHashMap<>();

    /**
     * Hosted-pool invalid-model SELF-HEAL trigger - the {@code rungFailureListener} the real chat
     * path hands to {@link #attemptHostedChain}. When a rung fails with a 400/404 whose body says
     * the model id itself doesn't exist ({@link ProviderErrorTranslator#isInvalidModelMessage} -
     * the single shared phrase list), this kicks off {@link
     * PlatformHostedLlmProviderService#reautoSelectModelForRow} for that row asynchronously, so the
     * pool stops dying on a vendor-retired model id without waiting for an admin to notice and
     * blank the model by hand.
     *
     * <p>Everything heavy happens OFF this (reactor) thread: this method only classifies the error,
     * resolves the row id from the link's health key, and atomically claims the per-row cooldown
     * slot; the catalog refresh + re-selection + save run on a background thread ({@code
     * CompletableFuture.runAsync}, the same fire-and-forget idiom {@code
     * LlmQualityJudgeService#sampleAsync} established). {@code reautoSelectModelForRow} itself
     * re-checks every guard that matters (row still exists, model was auto-selected, provider
     * known) - see its javadoc. Best-effort throughout: any failure is logged and the turn's own
     * failover behavior is completely unaffected. Package-private (not private) purely for {@code
     * AgentChatServiceInvalidModelSelfHealTest}'s seam.
     */
    void maybeSelfHealInvalidModelRow(HostedChainLink link, Throwable error) {
        SelfHealCandidate candidate = resolveSelfHealCandidate(link, error);
        if (candidate == null || !claimSelfHealCooldown(candidate.rowId())) {
            return;
        }
        CompletableFuture.runAsync(
                com.agenticai.core.security.concurrent.ContextPropagatingExecutor.propagating(
                        () -> {
                            try {
                                candidate
                                        .poolService()
                                        .reautoSelectModelForRow(
                                                candidate.rowId(), candidate.failureDetail());
                            } catch (RuntimeException healFailure) {
                                log.warn(
                                        "Hosted-pool invalid-model self-heal failed for row {}",
                                        candidate.rowId(),
                                        healFailure);
                            }
                        }),
                selfHealExecutor);
    }

    /**
     * Synchronous fast-path counterpart of {@link #maybeSelfHealInvalidModelRow}, used as {@code
     * attemptHostedChain}'s {@code exhaustedChainSyncHeal} for the real chat path - see that
     * parameter's javadoc for why this exists at all.
     *
     * <p>Same classification and the same per-row cooldown map as the async trigger above (shared
     * via {@link #resolveSelfHealCandidate}/{@link #claimSelfHealCooldown}), so the two never
     * double-heal the same row: this runs for the LAST rung's failure (the case the async trigger
     * cannot help with THIS turn anyway), and whichever of the two claims the cooldown slot first
     * performs the actual heal.
     *
     * <p>Unlike the async trigger, this one blocks the calling thread (the caller runs it on {@code
     * Schedulers.boundedElastic()} - see {@code attemptHostedChain}) until the heal either changes
     * the row's model or gives up, then rebuilds a fresh {@link HostedChainLink} for it via {@link
     * FreeTierChatClientService#rebuildLinkAfterSelfHeal} so the caller can retry the SAME turn
     * once more instead of only fixing the next one.
     *
     * @return the freshly-rebuilt link when the row's model actually changed and the row is still
     *     resolvable, {@code null} in every other case (nothing to heal, cooldown not claimed, heal
     *     failed, re-picked the same model, or the row became unresolvable in between) - the caller
     *     treats {@code null} as "give up, no retry".
     */
    HostedChainLink syncHealChainExhaustionAndRebuild(
            HostedChainLink link,
            Throwable error,
            int maxTokens,
            double temperature,
            double topP,
            String tenantId,
            String subjectKey,
            PerUserBudgetService.Modality modality) {
        SelfHealCandidate candidate = resolveSelfHealCandidate(link, error);
        if (candidate == null || !claimSelfHealCooldown(candidate.rowId())) {
            return null;
        }
        boolean changed;
        try {
            changed =
                    candidate
                            .poolService()
                            .reautoSelectModelForRow(candidate.rowId(), candidate.failureDetail());
        } catch (RuntimeException healFailure) {
            log.warn(
                    "Synchronous hosted-pool self-heal failed for row {}",
                    candidate.rowId(),
                    healFailure);
            return null;
        }
        if (!changed) {
            return null;
        }
        return freeTierChatClientService
                // The failed link rides along so the rebuilt link's advisors carry
                // the ORIGINAL turn's reservations + shared guards - see
                // rebuildLinkAfterSelfHeal's 2026-08-12 javadoc for the stranded-
                // reservation over-charge this closes.
                .rebuildLinkAfterSelfHeal(
                        candidate.rowId(),
                        maxTokens,
                        temperature,
                        topP,
                        tenantId,
                        subjectKey,
                        modality,
                        link)
                .orElse(null);
    }

    /**
     * Shared by {@link #maybeSelfHealInvalidModelRow} and {@link
     * #syncHealChainExhaustionAndRebuild}.
     */
    private record SelfHealCandidate(
            Long rowId, PlatformHostedLlmProviderService poolService, String failureDetail) {}

    /**
     * Classifies a rung failure as heal-eligible (400/404 + the shared invalid-model phrase list +
     * a resolvable saved-row id + the pool service actually being available) without touching the
     * cooldown map or doing any I/O - both self-heal paths run this same check before deciding
     * anything.
     */
    private SelfHealCandidate resolveSelfHealCandidate(HostedChainLink link, Throwable error) {
        if (!(error instanceof UpstreamProviderException upstream)) {
            return null;
        }
        int status = upstream.getUpstreamStatus();
        if (status != 400 && status != 404) {
            return null;
        }
        if (!ProviderErrorTranslator.isInvalidModelMessage(upstream.getMessage())) {
            return null;
        }
        Long rowId = hostedPoolRowId(link.healthKey());
        if (rowId == null) {
            return null;
        }
        PlatformHostedLlmProviderService poolService =
                platformHostedLlmProviderService.getIfAvailable();
        if (poolService == null) {
            return null;
        }
        return new SelfHealCandidate(rowId, poolService, upstream.getMessage());
    }

    /**
     * Atomically claims the per-row self-heal cooldown slot - {@code compute()} so two concurrent
     * failing turns (or the async and synchronous heal paths both firing for the same terminal
     * failure) can't both launch a heal for the same row.
     */
    private boolean claimSelfHealCooldown(Long rowId) {
        long now = System.currentTimeMillis();
        AtomicBoolean claimed = new AtomicBoolean(false);
        hostedModelSelfHealLastAttemptMs.compute(
                rowId,
                (id, lastAttempt) -> {
                    if (lastAttempt == null
                            || now - lastAttempt >= selfHealCooldown.toMillis()) {
                        claimed.set(true);
                        return now;
                    }
                    return lastAttempt;
                });
        return claimed.get();
    }

    /**
     * The pool row id embedded in a {@link HostedChainLink#healthKey()}, or {@code null} when there
     * isn't one. Inverse of {@code FreeTierChatClientService#healthKey}, whose saved-row form is
     * {@code "row:" + provider.getId()} - the unsaved/mocked-row fallback form ({@code "row:" +
     * label + ":" + model}) deliberately fails the parse, which is correct: a row that was never
     * saved has nothing to heal.
     */
    private static Long hostedPoolRowId(String healthKey) {
        if (healthKey == null || !healthKey.startsWith("row:")) {
            return null;
        }
        try {
            return Long.valueOf(healthKey.substring("row:".length()));
        } catch (NumberFormatException notASavedRowId) {
            return null;
        }
    }
}
