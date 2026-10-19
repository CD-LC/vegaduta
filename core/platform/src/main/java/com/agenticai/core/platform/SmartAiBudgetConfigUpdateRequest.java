package com.agenticai.core.platform;

import java.math.BigDecimal;

/**
 * Body for {@code PUT /api/platform/smart-ai} - the master control surface
 * for the whole Smart AI feature layer (see {@link PlatformFeatureBudgetService}).
 * Every field is optional/nullable - a null field leaves that setting's
 * stored row untouched (partial update), same posture as {@link
 * PerUserBudgetConfigUpdateRequest}.
 *
 * @param enabled                master switch - false degrades every Smart AI
 *                               feature to its non-AI behaviour immediately.
 * @param masterDailyUsd         combined daily USD ceiling across every Smart AI feature.
 * @param windowSeconds          length of the shared spend window (e.g. {@code 86400} for daily).
 * @param defaultFeatureDailyUsd daily USD cap used for any feature with no override of its own.
 */
public record SmartAiBudgetConfigUpdateRequest(
    Boolean enabled,
    BigDecimal masterDailyUsd,
    Integer windowSeconds,
    BigDecimal defaultFeatureDailyUsd
) {
}
