package com.agenticai.core.tenancy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Last-seen lookups over app users for other feature areas (keeps {@link AppUserRepository}
 * private).
 */
@Service
public class AppUserDirectoryService {

    private final AppUserRepository appUserRepository;

    public AppUserDirectoryService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /** Most recent last-seen across the tenant's users, or null if none. */
    @Transactional(readOnly = true)
    public Instant latestSeenForTenant(UUID tenantId) {
        return appUserRepository.findLatestSeenForTenant(tenantId);
    }

    /** Last-seen timestamps keyed by Keycloak user id. */
    @Transactional(readOnly = true)
    public Map<String, Instant> lastSeenByKeycloakId() {
        Map<String, Instant> lastSeen = new HashMap<>();
        for (AppUserRepository.KeycloakUserLastSeen r : appUserRepository.findAllLastSeen()) {
            lastSeen.put(r.getKeycloakUserId(), r.getLastSeenAt());
        }
        return lastSeen;
    }
}
