package com.agenticai.core.agents.hosted;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.budget.PerUserBudgetTrackingAdvisor;
import com.agenticai.core.agents.budget.TurnReservationGuards;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.HostedLlmChatClientFactory;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.chat.gateway.GatewayGovernanceAdvisor;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Everything {@code AgentChatService} needs to route a tenant's chat call through the
 * platform-hosted pool instead of BYOK - consume the tenant's usage quota (throws if exhausted -
 * see {@link FreeTierUsageLimiterService}), apply the per-user dollar budget (P1), and build the
 * ordered {@link ChatClient} FALLBACK CHAIN (P3) so a provider outage falls to the next instead of
 * failing the turn.
 *
 * <p>Deliberately checks quota + budget BEFORE resolving a provider - a request that's going to be
 * rejected shouldn't bother picking/decrypting a provider row first.
 *
 * <p>Two merged capabilities:
 *
 * <ul>
 *   <li><b>P3 fallback chain</b> ({@link #buildClientChainForFreeTenant}) - one ready-to-use client
 *       per enabled pool row, head-first; quota is checked/consumed exactly ONCE regardless of
 *       chain length.
 *   <li><b>P1 per-user dollar budget</b> - when a per-user identity ({@code subjectKey}) + {@link
 *       Modality} are supplied (the interactive chat path), the platform's per-USER budget is gated
 *       before the call and a {@link PerUserBudgetTrackingAdvisor} (priced per the chain provider's
 *       own model) records spend after. Internal/system callers (Ask-AI assist, workflow-graph
 *       generation, param inference) pass no subject and are metered only by the per-tenant
 *       limiter.
 * </ul>
 *
 * <p>Chain ORDER is this class's own decision, not the repository's: {@code
 * PlatformHostedLlmProviderService#resolveEnabledChain} still hands over the rows in {@code
 * priority} ASC, and {@link #orderForFailover} then re-ranks them cheapest-then-fastest with
 * known-dead rows pushed to the back. That method's javadoc is the honest account of which of those
 * three keys is backed by real data and which is not.
 */
@Service
public class FreeTierChatClientService {

    private static final Logger log = LoggerFactory.getLogger(FreeTierChatClientService.class);

    /**
     * platform_setting key for the admin-selectable Gemini thinking effort applied to every
     * hosted-pool turn (2026-08-12). Gemini 2.5+ models think by default, thinking tokens are
     * billed as OUTPUT and drawn from the same maxTokens budget as the reply - "low" bounds that
     * silent spend to a small budget while keeping some reasoning quality; "none" disables it where
     * the model allows. Written by {@code PlatformController}'s {@code /chat-generation-settings}
     * endpoints, consumed here; {@code HostedLlmChatClientFactory} decides per-model whether/how to
     * forward it (never to non-Gemini providers).
     */
    public static final String KEY_GEMINI_REASONING_EFFORT =
            "platform.hosted-llm.gemini-reasoning-effort";

    public static final String DEFAULT_GEMINI_REASONING_EFFORT = "low";

    /** The only values the setting may hold - shared with PlatformController's PUT validation. */
    public static final Set<String> ALLOWED_GEMINI_REASONING_EFFORTS =
            Set.of("none", "low", "medium", "high");

    private final FreeTierUsageLimiterService usageLimiterService;
    private final PlatformHostedLlmProviderService platformHostedLlmProviderService;
    private final HostedLlmChatClientFactory hostedLlmChatClientFactory;
    private final PerUserBudgetService perUserBudgetService;
    private final HostedProviderHealth providerHealth;
    private final PlatformSettingsService platformSettingsService;
    private final boolean orderByCost;
    private final HostedChainOrdering ordering;
    private final GeminiReasoningEffortSetting reasoningEffort;
    /** V484 - see {@link #poolUnmetered()}. */
    private final boolean meterZeroCostRows;

    /** Pre-V484 shape (zero-cost rows unmetered), kept so existing call sites compile unchanged. */
    public FreeTierChatClientService(
            FreeTierUsageLimiterService usageLimiterService,
            PlatformHostedLlmProviderService platformHostedLlmProviderService,
            HostedLlmChatClientFactory hostedLlmChatClientFactory,
            PerUserBudgetService perUserBudgetService,
            LlmPricingCatalog pricingCatalog,
            HostedProviderHealth providerHealth,
            PlatformSettingsService platformSettingsService,
            boolean orderByCost,
            long reasoningConfigCacheMs) {
        this(
                usageLimiterService,
                platformHostedLlmProviderService,
                hostedLlmChatClientFactory,
                perUserBudgetService,
                pricingCatalog,
                providerHealth,
                platformSettingsService,
                orderByCost,
                reasoningConfigCacheMs,
                false);
    }

    @Autowired
    public FreeTierChatClientService(
            FreeTierUsageLimiterService usageLimiterService,
            PlatformHostedLlmProviderService platformHostedLlmProviderService,
            HostedLlmChatClientFactory hostedLlmChatClientFactory,
            PerUserBudgetService perUserBudgetService,
            LlmPricingCatalog pricingCatalog,
            HostedProviderHealth providerHealth,
            PlatformSettingsService platformSettingsService,
            // Escape hatch for an operator who wants the chain tried in exactly the
            // order they typed into Platform Admin: false = pure `priority` ASC,
            // ignoring cost and latency entirely (the pre-hardening behaviour).
            @Value("${app.hosted-pool.order-by-cost:true}") boolean orderByCost,
            // Same 10s time-box FreeTierUsageLimiterService's quota-config cache
            // uses, for the same reason: the effort is read on every chain build
            // (the chat hot path), an uncached read would add a Postgres round
            // trip per message.
            @Value("${app.hosted-pool.reasoning-config-cache-ms:10000}")
                    long reasoningConfigCacheMs,
            // V484 - true keeps the monthly token quota for a pool made only of
            // zero-cost rows (an operator sharing one small GPU still wants a
            // ceiling); the default is "free means free".
            @Value("${app.free-tier.meter-zero-cost-rows:false}") boolean meterZeroCostRows) {
        this.meterZeroCostRows = meterZeroCostRows;
        this.usageLimiterService = usageLimiterService;
        this.platformHostedLlmProviderService = platformHostedLlmProviderService;
        this.hostedLlmChatClientFactory = hostedLlmChatClientFactory;
        this.perUserBudgetService = perUserBudgetService;
        this.providerHealth = providerHealth;
        this.platformSettingsService = platformSettingsService;
        this.orderByCost = orderByCost;
        this.ordering = new HostedChainOrdering(pricingCatalog, providerHealth, orderByCost);
        this.reasoningEffort =
                new GeminiReasoningEffortSetting(platformSettingsService, reasoningConfigCacheMs);
    }

    /**
     * Every {@link GatewayGovernanceAdvisor} bean, attached to each hosted-pool link in {@link
     * #buildLink}. Setter-injected (optional) so slim contexts and the direct-constructor tests
     * keep working with none; empty = attach nothing.
     */
    private volatile List<GatewayGovernanceAdvisor> governanceAdvisors = List.of();

    @Autowired(required = false)
    public void setGovernanceAdvisors(List<GatewayGovernanceAdvisor> governanceAdvisors) {
        this.governanceAdvisors =
                governanceAdvisors == null ? List.of() : List.copyOf(governanceAdvisors);
    }

    /**
     * Per-tenant monthly hosted-pool spend cap (V477). Setter-injected (optional) like the
     * governance advisors above; null = no cap enforcement.
     */
    private volatile com.agenticai.core.agents.spendcap.SpendCapService spendCapService;

    @Autowired(required = false)
    public void setSpendCapService(
            com.agenticai.core.agents.spendcap.SpendCapService spendCapService) {
        this.spendCapService = spendCapService;
    }

    /** See {@link GeminiReasoningEffortSetting#effective()}. */
    String effectiveGeminiReasoningEffort() {
        return reasoningEffort.effective();
    }

    /**
     * The pool's liveness/latency memory, exposed so {@code AgentChatService} can attribute each
     * chain attempt's outcome back to the row that produced it without taking a 25th constructor
     * dependency of its own. Read-only from that caller's point of view - it only ever records
     * outcomes.
     */
    public HostedProviderHealth providerHealth() {
        return providerHealth;
    }

    /**
     * Cost-transparency panel: whether the hosted-pool fallback chain is currently ordered by
     * estimated cost (see {@link #orderForFailover}) rather than plain configured priority. Reads
     * the SAME field the real ordering decision uses, not a second {@code @Value} lookup of {@code
     * app.hosted-pool.order-by-cost} that could drift from it.
     */
    public boolean isCostAwareRoutingEnabled() {
        return orderByCost;
    }

    /** FREE-tenant convenience overload, kept so every pre-#1085 call site compiles unchanged. */
    public ChatClient buildClientForFreeTenant(
            String tenantId, int maxTokens, double temperature, double topP) {
        return buildClientForFreeTenant(tenantId, TenantPlan.FREE, maxTokens, temperature, topP);
    }

    /**
     * Single-client entry point - the HEAD of the fallback chain, no per-user budget. Used by
     * internal/non-conversational callers (AssistService, GenerationParamsInference,
     * WorkflowGraphGenerator) that have no end-user to attribute spend to. Identical behavior to
     * before P3, since the head is the same lowest-priority row {@code resolveTopPriorityEnabled}
     * always picked, and quota is still checked exactly once (in the chain builder).
     *
     * @param plan #1085 - the tenant's plan, selecting the usage-cap tier.
     */
    public ChatClient buildClientForFreeTenant(
            String tenantId, TenantPlan plan, int maxTokens, double temperature, double topP) {
        return buildClientChainForFreeTenant(
                        tenantId, plan, maxTokens, temperature, topP, null, null)
                .get(0);
    }

    /**
     * P3 fallback chain WITHOUT a per-user budget (no end-user identity) - for any caller that
     * wants the chain but has no subject to meter.
     */
    public List<ChatClient> buildClientChainForFreeTenant(
            String tenantId, TenantPlan plan, int maxTokens, double temperature, double topP) {
        return buildClientChainForFreeTenant(
                tenantId, plan, maxTokens, temperature, topP, null, null);
    }

    /**
     * The full entry point: the priority-ordered chain of ready-to-use hosted {@link ChatClient}s
     * (P3), gated by the per-tenant usage caps and - when a per-user identity is supplied - the
     * per-user dollar budget (P1).
     *
     * <p>Quota + per-user budget are checked/consumed exactly ONCE here, not per provider: a turn
     * that falls through three providers is still one turn against the caps. {@code
     * AgentChatService} tries the returned clients in order, falling to the next only on a provider
     * failure (429/timeout/5xx).
     *
     * @param subjectKey stable per-user identity to meter the dollar budget against; when null (or
     *     {@code modality} is null) the per-user budget is skipped entirely (internal/system
     *     callers).
     * @param modality which independent per-user sub-cap this call draws from ({@link
     *     Modality#TEXT} for chat).
     * @throws FreeTierProviderPoolEmptyException when the pool is disabled (master switch) or has
     *     no enabled row.
     */
    public List<ChatClient> buildClientChainForFreeTenant(
            String tenantId,
            TenantPlan plan,
            int maxTokens,
            double temperature,
            double topP,
            String subjectKey,
            Modality modality) {
        return buildChainForFreeTenant(
                        tenantId, plan, maxTokens, temperature, topP, subjectKey, modality)
                .stream()
                .map(HostedChainLink::client)
                .toList();
    }

    /**
     * Same contract as {@link #buildClientChainForFreeTenant}, but each rung keeps the identity of
     * the pool row that built it (see {@link HostedChainLink}) - which is what lets {@code
     * AgentChatService#attemptHostedChain} say WHICH provider failed, feed {@link
     * HostedProviderHealth}, and de-prioritise a row that keeps dying. The client-only overload
     * above is kept for every caller that just wants the clients.
     */
    public List<HostedChainLink> buildChainForFreeTenant(
            String tenantId,
            TenantPlan plan,
            int maxTokens,
            double temperature,
            double topP,
            String subjectKey,
            Modality modality) {
        return buildChainForFreeTenant(
                tenantId,
                plan,
                maxTokens,
                temperature,
                topP,
                subjectKey,
                modality,
                com.agenticai.core.chat.SamplingOptions.NONE);
    }

    /**
     * Same as the overload above, additionally threading the agent's optional {@link
     * com.agenticai.core.chat.SamplingOptions} (stop sequences, topK, penalties, seed) into every
     * hosted client; each provider path applies only what its API supports.
     */
    public List<HostedChainLink> buildChainForFreeTenant(
            String tenantId,
            TenantPlan plan,
            int maxTokens,
            double temperature,
            double topP,
            String subjectKey,
            Modality modality,
            com.agenticai.core.chat.SamplingOptions sampling) {
        // Two independent gates, both atomic reserve-then-check
        // (checkAndReserveTokenBudget/checkAndReserveBudget - see their own
        // javadoc for the TOCTOU race the reserve step closes):
        //   1. the platform-pool monthly TOKEN quota (the platform's own
        //      spend ceiling - see FreeTierUsageLimiterService),
        //   2. the per-user dollar budget (§4 P1, skipped when there is no
        //      end-user identity).
        // Either can reject before any provider is engaged, as can an empty
        // enabled-provider pool (an operator config gap, not a quota issue).
        // Whatever reservations already landed are released in full in the
        // catch below, then the exception rethrows unchanged - the caller's
        // handling is otherwise untouched. reservedTokens/reservedMicroCents
        // carry what was actually reserved into the advisors below, which
        // correct the reservation to the REAL usage once the turn completes
        // (see FreeTierTokenTrackingAdvisor/PerUserBudgetTrackingAdvisor).
        // A platform admin's read-only view-as request never spends (see ImpersonationContext).
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("llm-call");
        // Monthly hosted spend cap: checked before anything is reserved, so a
        // capped tenant's rejection costs nothing to roll back. BYOK never gets here.
        com.agenticai.core.agents.spendcap.SpendCapService cap = spendCapService;
        if (cap != null) {
            cap.assertWithinCap(tenantId);
        }
        // V484 - a pool made only of rows the operator runs themselves cannot cost
        // anyone money, so neither the monthly token quota nor the per-user dollar
        // budget is reserved (or counted) for this turn. A mixed pool still
        // reserves up front; the per-row advisors then record $0 for a zero-cost
        // row and release its dollar reservation at the end of the turn.
        boolean unmetered = poolUnmetered();
        boolean perUserBudgeted = subjectKey != null && modality != null && !unmetered;
        long reservedTokens = 0;
        long reservedMicroCents = 0;
        try {
            if (!unmetered) {
                reservedTokens =
                        usageLimiterService.checkAndReserveTokenBudget(tenantId, plan, maxTokens);
            }

            // §4 P1 - per-user dollar budget, a further independent gate, checked
            // before resolving/decrypting any provider (same ordering the token
            // check follows). Skipped when there is no end-user identity. Priced
            // off the SAME reservedTokens estimate the token gate just reserved -
            // see checkAndReserveBudget's javadoc for why it can't yet price
            // against a specific model.
            if (perUserBudgeted) {
                reservedMicroCents =
                        perUserBudgetService.checkAndReserveBudget(
                                tenantId, subjectKey, modality, reservedTokens, plan);
            }

            List<HostedProviderSelection> chain =
                    platformHostedLlmProviderService.resolveEnabledChain();
            if (chain.isEmpty()) {
                throw new FreeTierProviderPoolEmptyException();
            }
            chain = orderForFailover(chain);

            // One shared per-tenant token advisor across the chain (at most one
            // client runs per turn - the first that succeeds). The per-user budget
            // advisor is created PER selection so it prices against whichever
            // provider's model actually answered. Both extra advisors are registered
            // in a single defaultAdvisors call so neither replaces the other.
            // The ONE TurnReservationGuards instance below is what makes the
            // turn's two reservations single-release across EVERY holder -
            // the advisors here, AgentChatService's pool-exhausted release,
            // and any self-heal-rebuilt link (see that class's javadoc for
            // the idle-timeout-cancel interleavings this closes).
            TurnReservationGuards guards = new TurnReservationGuards();
            FreeTierTokenTrackingAdvisor tokenAdvisor =
                    unmetered
                            ? null
                            : new FreeTierTokenTrackingAdvisor(
                                    usageLimiterService, tenantId, reservedTokens, guards);
            List<HostedChainLink> links = new ArrayList<>(chain.size());
            for (HostedProviderSelection selection : chain) {
                links.add(
                        buildLink(
                                selection,
                                maxTokens,
                                temperature,
                                topP,
                                tenantId,
                                subjectKey,
                                modality,
                                tokenAdvisor,
                                reservedTokens,
                                reservedMicroCents,
                                plan,
                                guards,
                                sampling));
            }
            return links;
        } catch (RuntimeException e) {
            usageLimiterService.reconcileTokenReservation(tenantId, reservedTokens, 0);
            if (perUserBudgeted) {
                perUserBudgetService.releaseSpendReservation(
                        tenantId, subjectKey, modality, reservedMicroCents, plan);
            }
            throw e;
        }
    }

    /**
     * Companion for the token/dollar reservations {@link #buildChainForFreeTenant} made for a turn
     * whose chain it successfully BUILT, but every link then failed at actual generation time
     * ({@code HostedPoolExhaustedException}, thrown well after this class already returned - {@code
     * buildChainForFreeTenant}'s own try/catch only covers failures WITHIN that call, not a later
     * generation-time exhaustion). {@code reservedTokens}/{@code reservedMicroCents} come from
     * whichever {@link HostedChainLink} the caller still has in scope (the same value on every link
     * in one turn's chain - see that record's javadoc) since this class keeps no per-turn state of
     * its own between the two calls.
     *
     * <p>Never double-fires against the advisors' own reconciliation - but NOT "by construction of
     * the exception" alone, as this javadoc used to claim: that reasoning covered success (a
     * succeeded link means {@code HostedPoolExhaustedException} was never thrown) while missing the
     * CANCEL path - a mid-stream idle timeout ({@code Flux.timeout} in {@code
     * AgentChatService#streamFast}) cancels the current link, whose advisors' {@code doOnCancel}
     * releases both reservations in full, and then surfaces as a retryable 408 that can still
     * exhaust the chain and land here, which used to release the same reservations a SECOND time
     * (driving the tenant token windows negative and minting phantom per-user budget headroom).
     * 2026-08-12 fix: this now consumes the same turn-scoped {@code guards} the advisors use
     * ({@code HostedChainLink#guards}, same object on every link), so whichever path released first
     * wins and the other is a no-op.
     */
    public void releaseReservations(
            String tenantId,
            TenantPlan plan,
            String subjectKey,
            Modality modality,
            long reservedTokens,
            long reservedMicroCents,
            TurnReservationGuards guards) {
        if (guards.consumeTokenReservation()) {
            usageLimiterService.reconcileTokenReservation(tenantId, reservedTokens, 0);
        }
        if (subjectKey != null && modality != null && guards.consumeSpendReservation()) {
            perUserBudgetService.releaseSpendReservation(
                    tenantId, subjectKey, modality, reservedMicroCents, plan);
        }
    }

    private HostedChainLink buildLink(
            HostedProviderSelection selection,
            int maxTokens,
            double temperature,
            double topP,
            String tenantId,
            String subjectKey,
            Modality modality,
            FreeTierTokenTrackingAdvisor tokenAdvisor,
            long reservedTokens,
            long reservedMicroCents,
            TenantPlan plan,
            TurnReservationGuards guards,
            com.agenticai.core.chat.SamplingOptions sampling) {
        List<Advisor> extraAdvisors = new ArrayList<>();
        if (tokenAdvisor != null) {
            extraAdvisors.add(tokenAdvisor);
        }
        // Gateway governance (content policy / injection screening, response PII
        // flag) - the same advisors LlmGatewayService#resolveClient attaches to
        // every BYOK client. Hosted-pool clients never pass through that seam, so
        // without this the free-tier agent-chat path skipped governance entirely.
        extraAdvisors.addAll(governanceAdvisors);
        if (subjectKey != null && modality != null) {
            extraAdvisors.add(
                    new PerUserBudgetTrackingAdvisor(
                            perUserBudgetService,
                            tenantId,
                            subjectKey,
                            modality,
                            selection.provider().getModel(),
                            reservedMicroCents,
                            plan,
                            guards,
                            selection.provider().isZeroCost()));
        }
        ChatClient client =
                hostedLlmChatClientFactory
                        // The admin-selected Gemini thinking effort rides along on every
                        // hosted-pool client; the factory applies it only to thinking
                        // Gemini models and never forwards it to other providers.
                        .create(
                                selection.provider(),
                                selection.decryptedApiKey(),
                                maxTokens,
                                temperature,
                                topP,
                                effectiveGeminiReasoningEffort(),
                                sampling)
                        .mutate()
                        .defaultAdvisors(extraAdvisors.toArray(Advisor[]::new))
                        .build();
        PlatformHostedLlmProvider provider = selection.provider();
        return new HostedChainLink(
                client,
                healthKey(provider),
                provider.getLabel(),
                provider.getModel(),
                provider.getPriority(),
                reservedTokens,
                reservedMicroCents,
                guards,
                sampling,
                provider.effectiveSmallModel());
    }

    /**
     * Rebuilds ONE chain link for a pool row that was just self-healed mid-turn (see {@code
     * AgentChatService#syncHealChainExhaustionAndRebuild}) - the row's model id changed under it
     * after the original {@link #buildChainForFreeTenant} call, so its {@link ChatClient} (built
     * against the now-dead model) can no longer be retried and has to be rebuilt against the row's
     * freshly auto-selected model.
     *
     * <p>Deliberately does NOT re-check tenant quota or per-user budget - both were already
     * consumed/gated once for this turn by the {@code buildChainForFreeTenant} call the caller made
     * earlier in the same turn; re-checking here would double-charge a turn that is still, from the
     * caller's point of view, the SAME turn retrying against a corrected model.
     *
     * <p>2026-08-12 fix: the rebuilt link's advisors now carry the ORIGINAL turn's reservations and
     * turn-scoped {@link TurnReservationGuards} (read off {@code originalLink}) instead of {@code
     * reservedTokens=0}/ {@code reservedMicroCents=0}. The old zeros were meant to avoid
     * double-touching the original reservation, but they left it with NO owner on the
     * healed-retry-succeeds path: the terminal link had failed with an ERROR (never a cancel, so
     * neither advisor had reconciled), {@code HostedPoolExhaustedException} was never thrown (so
     * {@code AgentChatService}'s release never ran), and the healed link's zero-reservation
     * advisors recorded only additive usage - stranding the full reservation as a ~2x over-charge
     * for the rest of the window TTL. With the shared guards, "must not double-touch" is enforced
     * by the guard itself rather than by zeroing the reservation out, so the healed link's success
     * reconciles the original reservation exactly once - and a healed-retry failure still falls
     * through to the caller's pool-exhausted release, which the same guard makes single-fire.
     *
     * @param originalLink the failed link this rebuild replaces - the source of the turn's
     *     reservation amounts and shared guards (same values on every link of the original chain,
     *     see {@link HostedChainLink}).
     * @return empty when the row is no longer enabled/resolvable (deleted, disabled, or now fails
     *     the SSRF guard) between the heal and this call.
     */
    public Optional<HostedChainLink> rebuildLinkAfterSelfHeal(
            Long rowId,
            int maxTokens,
            double temperature,
            double topP,
            String tenantId,
            String subjectKey,
            Modality modality,
            HostedChainLink originalLink) {
        Optional<HostedChainLink> rebuilt =
                platformHostedLlmProviderService
                        .resolveEnabledSelectionById(rowId)
                        .map(
                                selection ->
                                        buildLink(
                                                selection,
                                                maxTokens,
                                                temperature,
                                                topP,
                                                tenantId,
                                                subjectKey,
                                                modality,
                                                poolUnmetered()
                                                        ? null
                                                        : new FreeTierTokenTrackingAdvisor(
                                                                usageLimiterService,
                                                                tenantId,
                                                                originalLink.reservedTokens(),
                                                                originalLink.guards()),
                                                originalLink.reservedTokens(),
                                                originalLink.reservedMicroCents(),
                                                null,
                                                originalLink.guards(),
                                                originalLink.sampling()));
        if (rebuilt.isPresent()) {
            log.info(
                    "Hosted pool row {} re-initialized against model '{}' after self-heal - ready"
                            + " for retry",
                    rowId,
                    rebuilt.get().model());
        } else {
            log.warn(
                    "Hosted pool row {} could not be re-initialized after self-heal - "
                            + "no longer enabled/resolvable, no retry possible",
                    rowId);
        }
        return rebuilt;
    }

    /** See {@link HostedChainOrdering#order} for the four keys and their honest gaps. */
    List<HostedProviderSelection> orderForFailover(List<HostedProviderSelection> chain) {
        return ordering.order(chain);
    }

    /** See {@link HostedChainOrdering#healthKey}. */
    static String healthKey(PlatformHostedLlmProvider provider) {
        return HostedChainOrdering.healthKey(provider);
    }

    /**
     * V484 - "free means free": true when every enabled pool row is {@link
     * PlatformHostedLlmProvider#isZeroCost() zero-cost} and {@code
     * app.free-tier.meter-zero-cost-rows} is off (the default). Such a turn reserves and counts
     * nothing; see {@link #buildChainForFreeTenant}.
     */
    private boolean poolUnmetered() {
        return !meterZeroCostRows && platformHostedLlmProviderService.allEnabledRowsZeroCost();
    }
}
