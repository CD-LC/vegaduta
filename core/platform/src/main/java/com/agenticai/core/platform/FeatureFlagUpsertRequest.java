package com.agenticai.core.platform;

/** Master Roadmap 2026 item #13 - create-or-update body for {@code PUT /api/platform/feature-flags/{key}}. */
public record FeatureFlagUpsertRequest(String description, boolean enabled, int rolloutPercentage) {
}
