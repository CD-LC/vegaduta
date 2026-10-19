package com.agenticai.core.platform;

import com.agenticai.core.tenancy.FeatureFlag;
import java.time.Instant;
import java.util.UUID;

/**
 * Master Roadmap 2026 item #13 (A/B testing & feature flags, §B). Thin
 * projection of {@link FeatureFlag}, same "never expose a JPA entity
 * straight out of a controller" posture {@link PlatformSettingResponse}
 * already follows.
 */
public record FeatureFlagResponse(
    UUID id, String key, String description, boolean enabled, int rolloutPercentage,
    Instant createdAt, Instant updatedAt, String updatedBy
) {
    public static FeatureFlagResponse from(FeatureFlag flag) {
        return new FeatureFlagResponse(
            flag.getId(), flag.getKey(), flag.getDescription(), flag.isEnabled(), flag.getRolloutPercentage(),
            flag.getCreatedAt(), flag.getUpdatedAt(), flag.getUpdatedBy()
        );
    }
}
