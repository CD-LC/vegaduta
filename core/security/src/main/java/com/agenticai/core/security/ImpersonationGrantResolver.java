package com.agenticai.core.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI {@link ViewAsFilter} uses to turn an {@code X-View-As} grant into a session. Implemented in
 * core/platform ({@code ImpersonationService}); absent in slim contexts, in which case every {@code
 * X-View-As} request is rejected (never silently ignored).
 */
public interface ImpersonationGrantResolver {

    /**
     * Looks the session up by the grant's hash. Returns ended/expired sessions too (the filter
     * answers 410 for those, 401 for unknown grants). Results may be cached, but for at most 30
     * seconds.
     */
    Optional<ViewAsSession> resolve(String rawGrant);

    /**
     * Counts one honoured request. Must be cheap and non-blocking (batched by the implementation).
     */
    void recordRequest(UUID sessionId);

    /** A view-as session as the request path needs it. Never carries the raw grant. */
    record ViewAsSession(
            UUID id,
            String adminKeycloakId,
            String adminEmail,
            UUID tenantId,
            String tenantSchema,
            String tenantName,
            String targetKeycloakId,
            String targetEmail,
            List<String> targetRoles,
            Instant expiresAt,
            Instant endedAt) {

        public boolean isUsableAt(Instant now) {
            return endedAt == null && now.isBefore(expiresAt);
        }
    }
}
