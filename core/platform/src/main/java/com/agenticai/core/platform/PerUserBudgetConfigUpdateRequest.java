package com.agenticai.core.platform;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Body for {@code PUT /api/platform/per-user-budget}
 * (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4, Phase P1). Every field is
 * optional/nullable - a null field leaves that setting's stored row untouched
 * (partial update), the same optional-field posture {@link
 * PlatformLlmTaskUpsertRequest} uses for {@code apiKey}.
 *
 * @param enabled                master switch for per-user budget metering.
 * @param dailyUsd               base daily budget per user, in USD (e.g. {@code 0.15}).
 * @param windowSeconds          length of the spend window (e.g. {@code 86400} for daily).
 * @param splitPct               each modality's cap as a percentage of {@code dailyUsd},
 *                               keyed by modality name (case-insensitive: {@code text},
 *                               {@code image}, {@code voice}, {@code video}). Unknown keys
 *                               are ignored. These are independent sub-caps, not a shared pool.
 * @param fallbackUsdPer1kTokens price per 1,000 tokens used only when the pricing catalog
 *                               can't price the hosted model that served a call.
 */
public record PerUserBudgetConfigUpdateRequest(
    Boolean enabled,
    BigDecimal dailyUsd,
    Integer windowSeconds,
    Map<String, Integer> splitPct,
    BigDecimal fallbackUsdPer1kTokens
) {

    /** Parses the string-keyed {@link #splitPct} map into a typed {@link Modality} map, skipping unknown keys. */
    public Map<Modality, Integer> resolveSplitPct() {
        if (splitPct == null) {
            return null;
        }
        Map<Modality, Integer> resolved = new EnumMap<>(Modality.class);
        splitPct.forEach((name, pct) -> {
            if (name == null || pct == null) {
                return;
            }
            try {
                resolved.put(Modality.valueOf(name.trim().toUpperCase(Locale.ROOT)), pct);
            } catch (IllegalArgumentException ignored) {
                // Unknown modality name - ignore rather than reject the whole update.
            }
        });
        return resolved;
    }
}
