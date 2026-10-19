package com.agenticai.core.platform;

import java.math.BigDecimal;

/**
 * Body for {@code PUT /api/platform/smart-ai/features/{key}} - one Smart AI
 * feature's own switch/cap, layered under the master control in {@link
 * SmartAiBudgetConfigUpdateRequest}. Every field is optional/nullable, same
 * partial-update posture as its sibling requests in this package.
 *
 * @param enabled  per-feature switch - false degrades just this feature,
 *                 independent of every other Smart AI feature.
 * @param dailyUsd this feature's own daily USD cap, overriding {@code
 *                 platform.smart-ai.budget.default-feature-daily-usd}.
 */
public record SmartAiFeatureConfigUpdateRequest(Boolean enabled, BigDecimal dailyUsd) {
}
