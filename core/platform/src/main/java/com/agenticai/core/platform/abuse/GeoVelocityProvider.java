package com.agenticai.core.platform.abuse;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase P5 (Fraud & Anomaly) - the pluggable seam for the "impossible travel"
 * (geo-velocity) signal. This codebase has no per-request IP/geo capture today,
 * so there is NO default bean: {@link AbuseSignalService} injects this via
 * {@code ObjectProvider} and, when absent, marks the geo-velocity signal
 * {@code unavailable} (excluded from the aggregate - the graceful-degradation
 * contract). An operator who later wires an IP/geo feed (e.g. from an ingress
 * header or a WhatsApp/voice channel's originating number's region) implements
 * this interface and registers it as a bean - no other change needed.
 *
 * <p>Implementations MUST be fail-open: any internal error should surface as
 * {@link Optional#empty()} (treated as "no signal"), never a thrown exception,
 * so a geo-lookup hiccup can never block or mis-score a principal.
 */
public interface GeoVelocityProvider {

    /**
     * @return a 0.0..1.0 impossible-travel anomaly score for this principal
     *     over the given window (1.0 = physically impossible movement between
     *     observed locations), or {@link Optional#empty()} when there is not
     *     enough location data to judge.
     */
    Optional<Double> impossibleTravelScore(UUID tenantId, String userId, long windowMinutes);
}
