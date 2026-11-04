package com.agenticai.core.agents.budget;

import com.agenticai.core.chat.CacheTokenAccounting;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.chat.ReasoningTokenAccounting;
import com.agenticai.core.security.TenantPlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.ai.chat.metadata.Usage;

/** Prices one call's {@link Usage} for {@link PerUserBudgetService}. */
final class PerUserBudgetUsageCost {

    private PerUserBudgetUsageCost() {
    }

    /**
     * The real dollar cost of one call's {@link Usage}: the catalog's full
     * estimate (base prompt+completion PLUS billable cache buckets and
     * out-of-completion reasoning), falling back to the configured per-1k-token
     * rate over the provider's own total when the catalog can't price it -
     * the same fallback {@code PerUserBudgetService#recordSpendForCall} uses, just fed from
     * {@code totalTokens} (then the token sum) rather than only the token pair.
     */
    static BigDecimal costForUsage(
        LlmPricingCatalog pricingCatalog, PerUserBudgetConfigResolver configResolver,
        String tenantId, String userKey, String model, Usage usage, TenantPlan knownPlan
    ) {
        if (usage == null) {
            // Nothing measured (failed/cancelled call) - a null cost reads as
            // 0 micro-cents to applySpendDelta, i.e. a full reservation release.
            return null;
        }
        Long promptTokens = usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue();
        Long completionTokens = usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().longValue();
        CacheTokenAccounting cache = CacheTokenAccounting.from(usage);
        ReasoningTokenAccounting reasoning = ReasoningTokenAccounting.from(usage);
        BigDecimal cost = pricingCatalog.estimateCostUsd(
            null, model, promptTokens, completionTokens,
            cache.billableCacheWriteTokens(), cache.billableCacheReadTokens(),
            reasoning.billableReasoningTokens());
        if (cost != null) {
            return cost;
        }
        long totalTokens = usage.getTotalTokens() != null
            ? usage.getTotalTokens().longValue()
            : (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        if (totalTokens <= 0) {
            return BigDecimal.ZERO;
        }
        PerUserBudgetService.BudgetConfig config = configResolver.resolveConfig(tenantId, userKey, knownPlan);
        return config.fallbackUsdPer1kTokens()
            .multiply(BigDecimal.valueOf(totalTokens))
            .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
    }
}
