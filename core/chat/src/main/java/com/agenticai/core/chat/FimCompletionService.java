package com.agenticai.core.chat;

import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Server-side fill-in-the-middle code completion for IDE clients that have no local model and no
 * WebGPU - one hosted-pool call per request, on the platform's own keys, behind the platform's own
 * meters.
 *
 * <p><b>Entitlements first.</b> This is hosted inference, so it goes through the same gates a
 * hosted chat turn does: the FREE-tier message/token limiter and the per-user dollar budget,
 * reached via {@link FimCompletionEntitlements} (see that interface for why it is a port rather
 * than the two services injected directly - {@code core/agents} depends on {@code core/chat}, so
 * the reverse injection would be a module cycle). There is no unmetered path: with no
 * implementation on the context this service refuses every request.
 *
 * <p><b>Tenant context across the thread hop.</b> The model call runs on this class's own worker
 * pool, and {@link TenantContext} is a plain {@code ThreadLocal} that a pool thread does not
 * inherit. On a schema-per-tenant deployment an empty context is not a mislabelled metric - {@code
 * TenantSchemaResolver} falls back to the {@code public} schema, so anything the advisor chain
 * writes per call (see {@link LlmMetricsAdvisor}, which {@link HostedLlmChatClientFactory} attaches
 * by default) lands in the wrong place. So the request thread's context is captured before the
 * submit and re-applied on the worker for the duration of the call, restoring whatever was there
 * before - the capture-and-re-set pattern this codebase already uses for every other async hop
 * ({@code com.agenticai.core.agents.tools.TenantContextPropagatingToolCallback}, {@code
 * QuickTaskService}, {@code GeneratedImageController#streamBody}, and {@link LlmMetricsAdvisor}'s
 * own {@code withTenantContext}).
 *
 * <p>Scope of that propagation, so nobody reads more into it: the TENANT context crosses the hop.
 * The MDC (correlation id) and the OpenTelemetry span do not, so a log line or span emitted from
 * inside the provider call is not correlated to the originating request. That is a gap, not a
 * decision worth defending - it is called out here rather than left for the next reader to discover
 * from a log that will not join up.
 *
 * <p><b>Latency posture.</b> A completion request sits in a keystroke loop, so every knob here is
 * set for "answer fast or do not answer":
 *
 * <ul>
 *   <li>{@code maxTokens} defaults low and is clamped to a ceiling no caller can raise past;
 *   <li>the resolved pool row is cached for {@code app.fim.pool-cache-millis} rather than re-read
 *       per request - see {@link #cheapestEnabled()};
 *   <li>the call runs on a small bounded pool with a wall-clock deadline, and a deadline miss
 *       returns an EMPTY completion rather than making the editor wait. Be precise about what that
 *       deadline is: it bounds how long the CALLER waits, not the upstream request. Nothing here
 *       can abort a blocking HTTP read, and the LLM read timeout is a deliberately generous global
 *       ({@code app.llm.http.read-timeout}, default 10m - see {@link LlmHttpClientFactory}), so the
 *       worker thread stays occupied until the provider finally answers. The bounded pool with a
 *       direct-handoff queue is what keeps that from piling up: once every worker is stuck, new
 *       requests are rejected immediately and answer empty;
 *   <li>no retries, no provider failover chain. A hosted chat turn walks the whole pool on failure
 *       ({@code FreeTierChatClientService#buildChainForFreeTenant}); a completion that has already
 *       missed the typing window is worth nothing, so this takes one shot at one row.
 * </ul>
 *
 * <p><b>A missed deadline is not a free call.</b> The caller is answered empty, but the request is
 * still with the provider and the tokens it produces are still billed, so the reservation is NOT
 * refunded at the deadline. Settlement is deferred instead: the lease is handed to the in-flight
 * call, and whenever that call finally lands the real usage is settled (or, if it ends in a
 * failure, abandoned) on the worker thread under the same captured tenant context. That is why
 * {@link FimCompletionEntitlements.Lease} is documented as callable from another thread after the
 * request has returned. Two consequences worth stating: the reservation can stay outstanding for as
 * long as the upstream read timeout allows, and a process that dies mid-flight never terminates
 * that lease - over-reserving briefly is the direction this errs in deliberately, because
 * under-counting is the failure mode a spend meter must not have.
 *
 * <p><b>Model selection.</b> Rows come from the existing pool resolver ({@link
 * PlatformHostedLlmProviderService#resolveEnabledChain}, which applies the master switch, the
 * per-row enabled flag and the SSRF guard) - nothing is hardcoded here. Among the enabled rows this
 * prefers the CHEAPEST, priced through {@link LlmPricingCatalog}, because price is the only
 * small-and-fast signal any of this platform's model metadata actually exposes: a pool row carries
 * {@code model}/{@code baseUrl}/{@code priority}/{@code enabled} and no capability column ({@code
 * PlatformHostedLlmProvider}), and the catalog's per-model rates are the one machine-readable
 * ranking available. An id the catalog does not know is priced at its conservative default rate,
 * which sorts it below any known cheap model rather than accidentally winning. Ties keep the
 * resolver's own priority order, so an operator's chain ordering still decides between equals.
 */
@Service
public class FimCompletionService {

    private static final Logger log = LoggerFactory.getLogger(FimCompletionService.class);

    /** Token counts the cheapest-row ranking is priced at - a shape, not a prediction. */
    private static final long RANKING_PROMPT_TOKENS = 1000L;

    private static final long RANKING_COMPLETION_TOKENS = 100L;

    /** Low, because a completion that rambles has already lost the typing race. */
    private static final double TEMPERATURE = 0.1;

    private static final double TOP_P = 1.0;

    /** Guard on the memo below - pool model ids are operator-configured and few. */
    private static final int MAX_PRICE_MEMO_ENTRIES = 256;

    private final ObjectProvider<FimCompletionEntitlements> entitlementsProvider;
    private final PlatformHostedLlmProviderService hostedProviders;
    private final HostedLlmChatClientFactory chatClientFactory;
    private final LlmPricingCatalog pricingCatalog;

    /**
     * Memo over {@link #entitlementsProvider}. Only ever set to a non-null adapter, never to
     * "absent": an adapter that was missing at construction time (bean ordering, a deployment that
     * has not registered it yet) is looked up again on the next request instead of leaving this
     * instance permanently 503 until someone restarts it.
     */
    @Nullable private volatile FimCompletionEntitlements entitlements;

    private final int defaultMaxTokens;
    private final int maxTokensCeiling;
    private final long deadlineMillis;
    private final int maxCompletionChars;
    private final long poolCacheMillis;

    /**
     * {@link LlmPricingCatalog#estimateCostUsd} logs a WARN for every model id it cannot price.
     * Ranking the pool on every keystroke would emit that warning per row per request, so the
     * per-model price - a pure function of the id - is memoized.
     */
    private final Map<String, BigDecimal> priceMemo = new ConcurrentHashMap<>();

    /** See {@link #cheapestEnabled()} - TTL-only, no invalidation hook exists to subscribe to. */
    private final AtomicReference<CachedPick> poolPickCache = new AtomicReference<>();

    private final ThreadPoolExecutor executor;

    /**
     * The entitlements adapter is reached through an {@link ObjectProvider} rather than a plain
     * (nullable) parameter on purpose: this bean must still CONSTRUCT when no adapter is
     * registered. A hard dependency would fail context startup and take the whole application down
     * over one optional endpoint, which is a far worse failure than the endpoint answering 503 -
     * and a {@code @Nullable} parameter would make "does the app boot?" depend on Spring's
     * nullability handling rather than on something explicit.
     */
    public FimCompletionService(
            ObjectProvider<FimCompletionEntitlements> entitlements,
            PlatformHostedLlmProviderService hostedProviders,
            HostedLlmChatClientFactory chatClientFactory,
            LlmPricingCatalog pricingCatalog,
            @Value("${app.fim.max-tokens.default:48}") int defaultMaxTokens,
            @Value("${app.fim.max-tokens.ceiling:160}") int maxTokensCeiling,
            @Value("${app.fim.deadline-millis:1500}") long deadlineMillis,
            @Value("${app.fim.max-concurrent:8}") int maxConcurrent,
            @Value("${app.fim.max-completion-chars:2000}") int maxCompletionChars,
            @Value("${app.fim.pool-cache-millis:60000}") long poolCacheMillis) {
        this.entitlementsProvider = entitlements;
        this.hostedProviders = hostedProviders;
        this.chatClientFactory = chatClientFactory;
        this.pricingCatalog = pricingCatalog;
        this.defaultMaxTokens = Math.max(1, defaultMaxTokens);
        this.maxTokensCeiling = Math.max(this.defaultMaxTokens, maxTokensCeiling);
        this.deadlineMillis = Math.max(1L, deadlineMillis);
        this.maxCompletionChars = Math.max(1, maxCompletionChars);
        this.poolCacheMillis = Math.max(0L, poolCacheMillis);
        // Direct handoff (SynchronousQueue) + AbortPolicy: no queueing. A
        // request that cannot start RIGHT NOW is already too late to be
        // useful, so it is rejected and answered empty instead of waiting
        // behind someone else's stuck upstream call.
        AtomicInteger threadCounter = new AtomicInteger();
        this.executor =
                TenantAwareExecutors.newThreadPool(
                        0,
                        Math.max(1, maxConcurrent),
                        30L,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        TenantAwareExecutors.numberedThreadFactory("fim-completion-", true),
                        new ThreadPoolExecutor.AbortPolicy());
        this.entitlements = entitlements.getIfAvailable();
        if (this.entitlements == null) {
            log.error(
                    "No FimCompletionEntitlements bean is registered - POST /api/completions/fim will refuse every "
                            + "request until one appears. This endpoint spends platform-paid hosted inference, so it must "
                            + "not run unmetered; register the adapter over FreeTierUsageLimiterService/PerUserBudgetService "
                            + "to enable it.");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Thrown when there is no {@link FimCompletionEntitlements} on the context. A distinct type
     * rather than a bare {@link IllegalStateException} so {@link FimCompletionController} can map
     * exactly this to 503 without also swallowing an {@code IllegalStateException} thrown from
     * inside an adapter or a provider client, which is a server fault and must not be reported as
     * "the deployment is incomplete". Still extends {@code IllegalStateException} so any existing
     * caller that catches that keeps working.
     */
    public static final class EntitlementsUnavailableException extends IllegalStateException {

        EntitlementsUnavailableException(String message) {
            super(message);
        }
    }

    /** What the endpoint hands back. {@code model} is null whenever {@code completion} is empty. */
    public record FimCompletionResult(String completion, String model) {

        static FimCompletionResult empty() {
            return new FimCompletionResult("", null);
        }
    }

    /** True when this instance can serve at all - false means no entitlements adapter is wired. */
    public boolean metered() {
        return entitlements() != null;
    }

    /**
     * One completion. Never throws for "no suggestion" - an empty pool, a missed deadline, an
     * upstream failure and a model that had nothing to say all return an empty completion, because
     * an editor has nothing useful to do with any of those. Quota and budget rejections are the
     * deliberate exception: those propagate (402/403 via {@code GlobalExceptionHandler}) so the
     * client can tell "you are out of quota" apart from "no suggestion right now".
     *
     * @throws EntitlementsUnavailableException when no {@link FimCompletionEntitlements}
     *     implementation is registered - see the class javadoc.
     */
    public FimCompletionResult complete(
            String prefix, String suffix, String language, Integer requestedMaxTokens) {
        FimCompletionEntitlements adapter = entitlements();
        if (adapter == null) {
            throw new EntitlementsUnavailableException(
                    "Code completion is unavailable: no entitlements adapter is registered, and this endpoint "
                            + "must not serve platform-paid inference unmetered.");
        }
        if ((prefix == null || prefix.isEmpty()) && (suffix == null || suffix.isEmpty())) {
            // Nothing either side of the cursor - there is no middle to fill.
            return FimCompletionResult.empty();
        }
        int maxTokens = clampMaxTokens(requestedMaxTokens);

        // Gate BEFORE any provider is resolved or any key decrypted - the same
        // ordering FreeTierChatClientService#buildChainForFreeTenant uses.
        FimCompletionEntitlements.Lease lease = adapter.reserve(maxTokens);

        ModelCall call;
        try {
            call = invokeModel(prefix, suffix, language, maxTokens, lease);
        } catch (RuntimeException e) {
            lease.abandon();
            log.debug("FIM completion call failed - answering empty", e);
            return FimCompletionResult.empty();
        }
        if (call.settlementDeferred()) {
            // The call is still in flight and will be billed; the lease now
            // belongs to it (see #settleWhenItLands). Touching it here would
            // be the second terminal call on one reservation.
            return FimCompletionResult.empty();
        }
        ModelReply reply = call.reply();
        if (reply == null) {
            lease.abandon();
            return FimCompletionResult.empty();
        }

        // The call happened, so it is metered - whatever the cleanup below
        // makes of the text. Under-counting is the failure mode a spend meter
        // must not have.
        lease.settle(reply.model(), reply.promptTokens(), reply.completionTokens());

        String completion =
                FimResponseCleaner.clean(reply.text(), prefix, suffix, maxCompletionChars);
        return completion.isEmpty()
                ? FimCompletionResult.empty()
                : new FimCompletionResult(completion, reply.model());
    }

    /** Caller-supplied ceiling honoured only downwards; absent/non-positive means the default. */
    int clampMaxTokens(Integer requested) {
        if (requested == null || requested <= 0) {
            return defaultMaxTokens;
        }
        return Math.min(requested, maxTokensCeiling);
    }

    /**
     * Resolve a row, build the prompt for THAT row's model, make the one call under a wall-clock
     * deadline. Package-private seam so unit tests can exercise the quota/settlement contract
     * without mocking {@link ChatClient}'s fluent chain - the same seam {@link
     * ErrorExplanationService#invokeModel} uses.
     *
     * <p>{@code lease} is passed in because the deadline path has to hand settlement over to the
     * in-flight call rather than refund it; a caller must respect {@link
     * ModelCall#settlementDeferred()} and leave the lease alone when it is set.
     *
     * @return never null. May throw - {@link #complete} catches and abandons the lease.
     */
    ModelCall invokeModel(
            String prefix,
            String suffix,
            String language,
            int maxTokens,
            FimCompletionEntitlements.Lease lease) {
        HostedProviderSelection selection = cheapestEnabled();
        if (selection == null) {
            log.debug("FIM completion skipped - no enabled platform-hosted LLM pool row");
            return ModelCall.nothing();
        }
        String model = selection.provider().getModel();
        FimPrompt.Built prompt = FimPrompt.build(prefix, suffix, language, model);
        ChatClient chatClient =
                chatClientFactory.create(
                        selection.provider(),
                        selection.decryptedApiKey(),
                        maxTokens,
                        TEMPERATURE,
                        TOP_P);

        return callWithDeadline(chatClient, prompt, model, lease);
    }

    /**
     * Waits {@code deadlineMillis} for the call. A saturated pool yields nothing (no call was made,
     * so the lease is refundable); a missed deadline or an interrupt yields a DEFERRED outcome (a
     * call WAS made and will be billed, so the lease goes with it); upstream failures are rethrown
     * for {@link #complete} to abandon.
     */
    private ModelCall callWithDeadline(
            ChatClient chatClient,
            FimPrompt.Built prompt,
            String model,
            FimCompletionEntitlements.Lease lease) {
        TenantContext captured = TenantContext.current().orElse(null);
        CompletableFuture<ChatResponse> inFlight;
        try {
            inFlight = submitCall(chatClient, prompt, captured);
        } catch (RejectedExecutionException e) {
            log.debug("FIM completion rejected - all workers busy, answering empty");
            return ModelCall.nothing();
        }
        try {
            return ModelCall.of(
                    toReply(inFlight.get(deadlineMillis, TimeUnit.MILLISECONDS), model));
        } catch (TimeoutException e) {
            settleWhenItLands(inFlight, model, lease, captured);
            log.debug(
                    "FIM completion exceeded its {}ms deadline - answering empty, metering when the provider answers",
                    deadlineMillis);
            return ModelCall.deferred();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            settleWhenItLands(inFlight, model, lease, captured);
            return ModelCall.deferred();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("FIM completion call failed", cause);
        }
    }

    /**
     * Submits the one model call to the bounded pool with the request thread's {@code
     * capturedContext} re-applied on the worker. Package- private seam: a test can override it to
     * hand back a future it controls and so exercise the deadline/settlement paths without a real
     * provider.
     *
     * @throws RejectedExecutionException when no worker is free - the executor's {@code
     *     AbortPolicy}, thrown synchronously by {@code CompletableFuture#supplyAsync}'s own {@code
     *     execute} call.
     */
    CompletableFuture<ChatResponse> submitCall(
            ChatClient chatClient,
            FimPrompt.Built prompt,
            @Nullable TenantContext capturedContext) {
        return CompletableFuture.supplyAsync(
                () -> withTenantContext(capturedContext, () -> callOnWorker(chatClient, prompt)),
                executor);
    }

    /**
     * The one blocking model call, as it runs ON the worker thread - by which point {@link
     * #submitCall} has already re-applied the request's tenant context around it. Package-private
     * seam so a test can stand in for the provider (and observe what context the worker really
     * sees) without mocking {@link ChatClient}'s fluent chain.
     */
    @Nullable
    ChatResponse callOnWorker(ChatClient chatClient, FimPrompt.Built prompt) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (prompt.system() != null) {
            spec = spec.system(prompt.system());
        }
        return spec.user(prompt.user()).call().chatResponse();
    }

    /**
     * Hands the reservation to a call that outran its deadline. The provider bills whatever that
     * call produces, so this settles with the REAL usage once it arrives rather than refunding at
     * the deadline; a call that ends in a failure produced no billable completion and is abandoned,
     * the same rule {@link #complete}'s synchronous failure path applies.
     *
     * <p>Runs on whichever thread completes the call - the pool worker, or this one if the future
     * has already completed by the time the callback is registered - so the captured tenant context
     * is re-applied here too. Registered only AFTER the request thread has decided to defer, which
     * is what keeps "exactly one terminal call per lease" true.
     */
    private void settleWhenItLands(
            CompletableFuture<ChatResponse> inFlight,
            String model,
            FimCompletionEntitlements.Lease lease,
            @Nullable TenantContext capturedContext) {
        inFlight.whenComplete(
                (response, error) -> {
                    try {
                        withTenantContext(
                                capturedContext,
                                () -> {
                                    ModelReply reply =
                                            error == null ? toReply(response, model) : null;
                                    if (reply == null) {
                                        lease.abandon();
                                    } else {
                                        lease.settle(
                                                reply.model(),
                                                reply.promptTokens(),
                                                reply.completionTokens());
                                    }
                                    return null;
                                });
                    } catch (RuntimeException e) {
                        // Never rethrow into the future's completion machinery: it
                        // would be swallowed there anyway, and this is the only place
                        // the failure can be seen.
                        log.warn(
                                "FIM post-deadline settlement failed for model {} - that reservation is left outstanding",
                                model,
                                e);
                    }
                });
    }

    /**
     * Re-applies {@code capturedContext} on whichever thread is running right now, runs {@code
     * work}, then restores whatever was there before (never unconditionally clears, so this cannot
     * clobber a context some other, already-correct mechanism had set on this thread). A null
     * captured context is a no-op - {@code work} still runs, just without touching {@link
     * TenantContext}. Same shape as {@link LlmMetricsAdvisor}'s own {@code withTenantContext}.
     */
    @Nullable
    private static <T> T withTenantContext(
            @Nullable TenantContext capturedContext, Supplier<T> work) {
        if (capturedContext == null) {
            return work.get();
        }
        Optional<TenantContext> previous = TenantContext.current();
        TenantContext.set(capturedContext);
        try {
            return work.get();
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /** Whatever the response carried, or null when there is no output to meter or serve. */
    @Nullable
    private static ModelReply toReply(@Nullable ChatResponse response, String model) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        Long promptTokens = null;
        Long completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            promptTokens =
                    usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue();
            completionTokens =
                    usage.getCompletionTokens() == null
                            ? null
                            : usage.getCompletionTokens().longValue();
        }
        return new ModelReply(
                response.getResult().getOutput().getText(), model, promptTokens, completionTokens);
    }

    /**
     * Cheapest enabled pool row, ties broken by the resolver's priority order; null when the pool
     * is empty.
     *
     * <p><b>Why this is cached.</b> {@link PlatformHostedLlmProviderService#resolveEnabledChain} is
     * a database read of the pool settings row plus every enabled provider row, and an AES decrypt
     * per row. Unresolved per request, that is that whole round trip per keystroke.
     *
     * <p><b>What the cache may hold.</b> The pool lives in the {@code control_plane} schema (that
     * is the schema on {@code PlatformHostedLlmProvider}'s own JPA table annotation), i.e. it is
     * platform-wide configuration, not per-tenant - so one entry is correct and cannot serve one
     * tenant another's row. It does hold a decrypted key in memory - until the entry is replaced by
     * the next resolve after the TTL expires, not for the TTL and then cleared - which is the same
     * plaintext the {@link ChatClient} built from it already holds for the duration of a call.
     *
     * <p><b>Invalidation, stated honestly.</b> There is none to subscribe to: {@code
     * PlatformHostedLlmProviderService}'s create/update/delete publish no event and evict no cache,
     * and this module cannot add one to it. So this is TTL-only ({@code app.fim.pool-cache-millis},
     * default 60000; set 0 to resolve every request), the same process-local-TTL-with-stated-
     * staleness posture {@code SocialAuthConfigController} and {@code ProviderModelListingService}
     * already take. The consequence to be clear about: for up to the TTL after an admin changes the
     * pool, completions can still go to the previously-picked row - including a row that was just
     * disabled. Everything else about the pool (the master switch, the per-row enabled flag, the
     * SSRF guard) is still applied, just as of the last resolve rather than as of this request.
     */
    @Nullable
    private HostedProviderSelection cheapestEnabled() {
        long now = System.currentTimeMillis();
        CachedPick cached = poolPickCache.get();
        if (cached != null && now - cached.resolvedAtMillis() < poolCacheMillis) {
            return cached.selection();
        }
        HostedProviderSelection picked = resolveCheapestEnabled();
        poolPickCache.set(new CachedPick(picked, now));
        return picked;
    }

    /** The uncached resolve-and-rank {@link #cheapestEnabled()} memoizes. */
    @Nullable
    private HostedProviderSelection resolveCheapestEnabled() {
        List<HostedProviderSelection> chain = hostedProviders.resolveEnabledChain();
        HostedProviderSelection best = null;
        BigDecimal bestPrice = null;
        for (HostedProviderSelection candidate : chain) {
            BigDecimal price = rankingPrice(candidate.provider().getModel());
            // Strictly-less-than keeps the earlier (higher-priority) row on a tie.
            if (best == null
                    || (price != null && (bestPrice == null || price.compareTo(bestPrice) < 0))) {
                best = candidate;
                bestPrice = price;
            }
        }
        return best;
    }

    /**
     * Memoized {@link LlmPricingCatalog} estimate for a fixed token shape; null for a row with no
     * model id.
     */
    @Nullable
    private BigDecimal rankingPrice(@Nullable String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        BigDecimal memoized = priceMemo.get(model);
        if (memoized != null) {
            return memoized;
        }
        BigDecimal price =
                pricingCatalog.estimateCostUsd(
                        model, RANKING_PROMPT_TOKENS, RANKING_COMPLETION_TOKENS);
        if (price != null && priceMemo.size() < MAX_PRICE_MEMO_ENTRIES) {
            priceMemo.put(model, price);
        }
        return price;
    }

    /**
     * Lazily-resolved entitlements adapter, or null when none is registered yet. See the field's
     * javadoc.
     */
    @Nullable
    private FimCompletionEntitlements entitlements() {
        FimCompletionEntitlements resolved = entitlements;
        if (resolved != null) {
            return resolved;
        }
        resolved = entitlementsProvider.getIfAvailable();
        if (resolved != null) {
            entitlements = resolved;
        }
        return resolved;
    }

    /**
     * The pool pick as of {@code resolvedAtMillis}; {@code selection} is null for "the pool was
     * empty".
     */
    private record CachedPick(@Nullable HostedProviderSelection selection, long resolvedAtMillis) {}

    /**
     * The outcome of one invocation attempt: what to serve, and who owns the reservation now.
     * {@code settlementDeferred} means a call is in flight past its deadline and has taken the
     * lease with it - the caller must not settle or abandon.
     */
    record ModelCall(@Nullable ModelReply reply, boolean settlementDeferred) {

        /**
         * Nothing was called (empty pool, no free worker) - the lease is still the caller's to
         * refund.
         */
        static ModelCall nothing() {
            return new ModelCall(null, false);
        }

        /** The call outran its deadline; settlement happens when it lands. */
        static ModelCall deferred() {
            return new ModelCall(null, true);
        }

        /**
         * The call answered in time - {@code reply} may still be null when it carried no output.
         */
        static ModelCall of(@Nullable ModelReply reply) {
            return new ModelCall(reply, false);
        }
    }

    /** What one model invocation produced - raw text plus whatever usage the provider reported. */
    record ModelReply(String text, String model, Long promptTokens, Long completionTokens) {}
}
