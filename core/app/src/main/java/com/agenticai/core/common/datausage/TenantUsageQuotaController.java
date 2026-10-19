package com.agenticai.core.common.datausage;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.agents.tools.ToolRelevanceSelector;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.ProviderCachingRegistry;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only quota-state endpoint for the visible usage meter (item B3,
 * docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md §5.2): the platform already enforces a hosted-pool
 * monthly token window server-side ({@link FreeTierUsageLimiterService}), but users had no way to
 * SEE used-vs-limit, so hitting a cap read as a random failure instead of an upgrade path.
 *
 * <p>Lives in {@code core/app}'s datausage package for the same cross-module-aggregator reason
 * {@link TenantDataUsageService}'s javadoc documents: this needs {@code core/agents}' limiter
 * counters plus {@code core/security}'s {@link TenantLimits} plan read, and {@code core/app} is the
 * module that already depends on both.
 *
 * <p>Unlike sibling {@code GET /api/tenant/data-usage} (tenant-admin-only in {@code
 * SecurityConfig}), this deliberately carries no extra role gate and falls through to {@code
 * anyRequest().authenticated()}: the meter exists for every member who chats (any of them can burn
 * the shared window and see the resulting 403), and the response exposes nothing more sensitive
 * than counters that member's own usage moves. "Which tenant" still resolves strictly from {@link
 * TenantContext}, never a path variable.
 *
 * <p>{@code tenantId} passed to the limiter is {@link TenantContext#getTenantId()} verbatim - the
 * SAME string every enforcement call site uses ({@code
 * FreeTierChatClientService#buildChainForFreeTenant} et al.), so this reads exactly the Redis keys
 * enforcement writes.
 */
@RestController
@RequestMapping("/api/tenant")
@Profile("security")
public class TenantUsageQuotaController {

    /**
     * {@link SpendProtectionResponse#caching()}'s window - matches the task's "e.g. last 7 days"
     * guidance; short enough that a low-traffic tenant's numbers stay recent and relevant.
     */
    private static final int CACHE_WINDOW_DAYS = 7;

    private final TenantLimits tenantLimits;
    private final FreeTierUsageLimiterService usageLimiterService;
    private final ProviderCachingRegistry providerCachingRegistry;
    private final LlmCallLogService llmCallLogService;
    private final FreeTierChatClientService freeTierChatClientService;
    private final ToolRelevanceSelector toolRelevanceSelector;
    private final ApprovalPolicyService approvalPolicyService;

    /** Off (personal profile): the monthly ceiling is never enforced - see FreeTierUsageLimiterService. */
    @Value("${app.edition.billing-enabled:true}")
    private boolean billingEdition = true;

    public TenantUsageQuotaController(
            TenantLimits tenantLimits,
            FreeTierUsageLimiterService usageLimiterService,
            ProviderCachingRegistry providerCachingRegistry,
            LlmCallLogService llmCallLogService,
            FreeTierChatClientService freeTierChatClientService,
            ToolRelevanceSelector toolRelevanceSelector,
            ApprovalPolicyService approvalPolicyService) {
        this.tenantLimits = tenantLimits;
        this.usageLimiterService = usageLimiterService;
        this.providerCachingRegistry = providerCachingRegistry;
        this.llmCallLogService = llmCallLogService;
        this.freeTierChatClientService = freeTierChatClientService;
        this.toolRelevanceSelector = toolRelevanceSelector;
        this.approvalPolicyService = approvalPolicyService;
    }

    @GetMapping("/usage-quota")
    public TenantUsageQuotaResponse usageQuota() {
        String tenantId =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No TenantContext for an authenticated /api/tenant/usage-quota request - SecurityConfig should have blocked this"));
        TenantPlan plan = tenantLimits.plan(tenantId);
        // The one routing predicate AgentChatService/GenerationParamsInference
        // Service and every other caller now share for "does this tenant's chat
        // go through the hosted pool" - FREE always does; PREMIUM/UNLIMITED when
        // opted into usePlatformLlm or inside a live complimentary grant. (A
        // paid BYOK tenant CAN still have individual PLATFORM_HOSTED agents -
        // tenant-level is the honest granularity this endpoint has, and the
        // meter hides rather than over-claims for that edge.)
        boolean usesPlatformPool = tenantLimits.usesPlatformPool(tenantId, plan);
        FreeTierUsageLimiterService.QuotaSnapshot s =
                usageLimiterService.quotaSnapshot(tenantId, plan);
        return new TenantUsageQuotaResponse(
                plan.name(), usesPlatformPool, s.tokensUsedMonth(), s.tokensMonthlyLimit(), billingEdition);
    }

    /**
     * "What's protecting your spend" transparency panel (additive to {@link #usageQuota()}, not a
     * replacement). No {@link TenantContext} scoping needed beyond what {@link #llmCallLogService}
     * already applies: {@code LlmCallLogService}/{@code LlmCallLogRepository} run against
     * Hibernate's tenant-schema multi-tenancy the same way every other query on that repository
     * does (see {@code LlmCallLogRepository}'s own javadoc), so the cache-effectiveness numbers
     * below are already scoped to the calling tenant's own schema without this method passing a
     * tenant id anywhere.
     */
    @GetMapping("/spend-protection")
    public SpendProtectionResponse spendProtection() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(CACHE_WINDOW_DAYS, ChronoUnit.DAYS);

        // Source of truth for "which providers have caching active" - the
        // shared registry every ProviderCachingStrategy bean registers into
        // automatically, never a hardcoded provider list (Anthropic/OpenAI
        // today, Gemini and others as their strategies land).
        Map<String, LlmCallLogRepository.CacheEffectivenessRow> effectivenessByProvider =
                llmCallLogService.cacheEffectivenessSince(windowStart).stream()
                        .collect(
                                Collectors.toMap(
                                        LlmCallLogRepository.CacheEffectivenessRow::getProviderName,
                                        row -> row));

        List<SpendProtectionResponse.CachingProviderStatus> caching =
                providerCachingRegistry.activeProviders().stream()
                        .sorted(Comparator.comparing(ProviderName::name))
                        .map(
                                provider -> {
                                    LlmCallLogRepository.CacheEffectivenessRow row =
                                            effectivenessByProvider.get(provider.name());
                                    if (row == null || row.getTotalCalls() == 0) {
                                        // No calls to this provider in the window yet - real
                                        // absence of data, not a fabricated 0% metric.
                                        return new SpendProtectionResponse.CachingProviderStatus(
                                                provider.name(), true, 0, null, null);
                                    }
                                    long totalInputTokens =
                                            row.getPromptTokens()
                                                    + row.getCacheCreationTokens()
                                                    + row.getCacheReadTokens();
                                    Double cacheReadTokenShare =
                                            totalInputTokens > 0
                                                    ? row.getCacheReadTokens()
                                                            / (double) totalInputTokens
                                                    : null;
                                    Double callsWithCacheHitShare =
                                            row.getCallsWithCacheHit()
                                                    / (double) row.getTotalCalls();
                                    return new SpendProtectionResponse.CachingProviderStatus(
                                            provider.name(),
                                            true,
                                            row.getTotalCalls(),
                                            cacheReadTokenShare,
                                            callsWithCacheHitShare);
                                })
                        .toList();

        SpendProtectionResponse.CostMechanismStatus costAwareRouting =
                new SpendProtectionResponse.CostMechanismStatus(
                        "cost_aware_routing",
                        freeTierChatClientService.isCostAwareRoutingEnabled(),
                        "When your chat falls back across the hosted model pool, providers are tried in order of "
                                + "estimated cost first (cheapest ready provider, not just configured priority).",
                        null);

        boolean toolMinimizationEnabled = toolRelevanceSelector.isEnabled();
        SpendProtectionResponse.CostMechanismStatus toolSchemaMinimization =
                new SpendProtectionResponse.CostMechanismStatus(
                        "tool_schema_minimization",
                        toolMinimizationEnabled,
                        "Only the tools relevant to what you just asked are sent to the model each turn, instead of "
                                + "every tool your agent has enabled - fewer schema tokens billed per message.",
                        toolMinimizationEnabled
                                ? "Capped at "
                                        + toolRelevanceSelector.maxToolsPerTurn()
                                        + " tool schemas per turn"
                                        + " ("
                                        + toolRelevanceSelector.noMatchMaxToolsPerTurn()
                                        + " when nothing matches your message)."
                                : null);

        SpendProtectionResponse.CostMechanismStatus approvalGates =
                new SpendProtectionResponse.CostMechanismStatus(
                        "approval_gates",
                        true,
                        "Risky actions - sending to a channel, bulk campaign sends, and deletes - pause for your "
                                + "explicit approval before an agent can execute them, preventing runaway or accidental "
                                + "spend from an agent acting on its own. Payment/refund/transfer actions are pre-gated "
                                + "the same way for the moment any such tool is added - no agent can call one today.",
                        approvalPolicyService.defaultSensitivePatternCount()
                                + " action patterns gated by default"
                                + (approvalPolicyService.overrideCount() > 0
                                        ? ", "
                                                + approvalPolicyService.overrideCount()
                                                + " tenant-specific override(s) configured."
                                        : "."));

        return new SpendProtectionResponse(
                now,
                CACHE_WINDOW_DAYS,
                caching,
                costAwareRouting,
                toolSchemaMinimization,
                approvalGates);
    }
}
