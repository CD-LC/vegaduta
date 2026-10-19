package com.agenticai.core.agents.hosted;

import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The hosted-pool chain order (cheapest, then fastest, known-dead last, the admin's priority as the
 * final tie-break), moved out of {@link FreeTierChatClientService} (extract-only split). {@link
 * FreeTierChatClientService#orderForFailover} delegates here; the javadoc of {@link #order} is the
 * honest account of which key is backed by real data.
 */
final class HostedChainOrdering {

    private static final Logger log = LoggerFactory.getLogger(FreeTierChatClientService.class);

    // Reference token mix used ONLY to rank pool rows against each other (see
    // order). 3:1 prompt:completion is the rough shape of a real
    // chat turn on this platform (system prompt + recalled memories + history
    // dwarf the reply), and it is fed straight into the SAME hand-maintained
    // $/MTok table LlmMetricsAdvisor already bills every call against - no
    // second, invented price list. It is a comparison key, never a charge: real
    // spend is still computed per call from actual usage.
    private static final long RANKING_PROMPT_TOKENS = 3_000_000L;
    private static final long RANKING_COMPLETION_TOKENS = 1_000_000L;

    private final LlmPricingCatalog pricingCatalog;
    private final HostedProviderHealth providerHealth;
    private final boolean orderByCost;

    HostedChainOrdering(
            LlmPricingCatalog pricingCatalog,
            HostedProviderHealth providerHealth,
            boolean orderByCost) {
        this.pricingCatalog = pricingCatalog;
        this.providerHealth = providerHealth;
        this.orderByCost = orderByCost;
    }

    /**
     * The order the chain is actually attempted in. Direct product-owner instruction: "if one
     * provider is not working, the system should detect it and switch to the next - preferring low
     * cost and low latency."
     *
     * <p>Four keys, in this order:
     *
     * <ol>
     *   <li><b>Known-dead last.</b> A row inside its {@link HostedProviderHealth} cooldown is
     *       pushed to the BACK of the chain rather than dropped, so a request never pays the
     *       timeout of a provider we already watched fail three times in a row - but a pool whose
     *       every row is tripped (or, as in production today, a pool with exactly ONE row) still
     *       gets tried instead of hard-failing on a breaker.
     *   <li><b>Cheapest first</b> - {@link LlmPricingCatalog}'s hand-maintained per-model $/MTok
     *       table (the same one that prices every {@code llm_call_log} row and the per-user dollar
     *       budget) evaluated against a fixed reference token mix. This is REAL data for the
     *       families the pool actually uses (Gemini flash/flash-lite, Groq-served Llama and Gemma,
     *       GPT-4o-mini...); nothing here invents a price.
     *   <li><b>Fastest first</b> - the observed latency EWMA {@link HostedProviderHealth}
     *       accumulates from this instance's own successful calls. Only a tie-break, because it is
     *       per-instance and starts empty after a restart.
     *   <li><b>The admin's {@code priority} column</b> - the final tie-break, and the ONLY key used
     *       at all when {@code app.hosted-pool.order-by-cost=false} or when neither model is
     *       priced.
     * </ol>
     *
     * <p><b>Honest gap:</b> a model the catalog does not know sorts after every model it does.
     * There is no per-row cost or latency column on {@code platform_hosted_llm_provider} to consult
     * instead, and {@code llm_call_log} - the one place real per-model cost/latency IS recorded -
     * lives in each TENANT's schema, so reading it here would mean a cross-schema scan on the hot
     * path of every turn and would rank a platform-wide pool from one tenant's traffic. Fixing that
     * properly means either adding cost/latency columns to the pool row (a migration, not built
     * here - see this task's report) or rolling {@code llm_call_log} up into a control-plane
     * per-model summary. Until then: add an unpriced model's rate to {@link LlmPricingCatalog}, or
     * express the intent with {@code priority}.
     */
    List<HostedProviderSelection> order(List<HostedProviderSelection> chain) {
        List<HostedProviderSelection> ordered = new ArrayList<>(chain);
        Comparator<HostedProviderSelection> byPriority =
                Comparator.comparingInt(selection -> selection.provider().getPriority());
        if (orderByCost) {
            ordered.sort(
                    Comparator.comparing(
                                    (HostedProviderSelection selection) ->
                                            rankingCostUsd(selection.provider()),
                                    Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()))
                            .thenComparing(
                                    (HostedProviderSelection selection) ->
                                            observedLatency(selection.provider()),
                                    Comparator.nullsLast(Comparator.<Double>naturalOrder()))
                            .thenComparing(byPriority));
        } else {
            ordered.sort(byPriority);
        }
        // Stable sort, applied last: preserves everything decided above WITHIN
        // each group while moving the tripped rows behind the healthy ones.
        ordered.sort(
                Comparator.comparing(
                        (HostedProviderSelection selection) ->
                                providerHealth.isTripped(healthKey(selection.provider()))));
        if (log.isDebugEnabled()) {
            ordered.forEach(
                    selection ->
                            log.debug(
                                    "Hosted pool chain order: {} (model={}, priority={},"
                                            + " refCostUsd={}, observedLatencyMs={}, tripped={})",
                                    selection.provider().getLabel(),
                                    selection.provider().getModel(),
                                    selection.provider().getPriority(),
                                    rankingCostUsd(selection.provider()),
                                    observedLatency(selection.provider()),
                                    providerHealth.isTripped(healthKey(selection.provider()))));
        }
        return ordered;
    }

    /**
     * Null - never a made-up number - when {@link LlmPricingCatalog} has no rate for this model.
     */
    private BigDecimal rankingCostUsd(String model) {
        return pricingCatalog.estimateCostUsd(
                model, RANKING_PROMPT_TOKENS, RANKING_COMPLETION_TOKENS);
    }

    /** V484 - a zero-cost row is exactly $0, so it sorts ahead of every priced row. */
    private BigDecimal rankingCostUsd(PlatformHostedLlmProvider provider) {
        return provider.isZeroCost() ? BigDecimal.ZERO : rankingCostUsd(provider.getModel());
    }


    /**
     * Null when this row has never successfully answered on this instance (post-restart, or brand
     * new).
     */
    private Double observedLatency(PlatformHostedLlmProvider provider) {
        OptionalDouble observed = providerHealth.observedLatencyMs(healthKey(provider));
        if (observed.isEmpty()) {
            return null;
        }
        return observed.getAsDouble();
    }

    /**
     * Stable per-ROW identity for health tracking. The row id is the real key; the label+model
     * fallback only matters for an unsaved/mocked row, and is still stable enough to be useful
     * within one process.
     */
    static String healthKey(PlatformHostedLlmProvider provider) {
        return provider.getId() != null
                ? "row:" + provider.getId()
                : "row:" + provider.getLabel() + ":" + provider.getModel();
    }
}
