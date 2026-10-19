package com.agenticai.core.common.security;

import com.agenticai.core.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant/user ids for the fraud subsystem, read from {@link TenantContext}
 * (set by {@code TenantContextFilter} from the verified JWT) instead of the
 * {@code tenantId}/{@code userId} request attributes the subsystem used to
 * read - nothing in core ever set those, so every check was skipped
 * (JAVA-005). The fraud tables key on UUID; a tenant id that is not a UUID
 * (e.g. a schema-style id) maps to a stable name-based UUID.
 */
final class FraudTenantIds {

    private FraudTenantIds() {
    }

    static Optional<UUID> currentTenant() {
        return TenantContext.current()
            .map(TenantContext::getTenantId)
            .filter(id -> !id.isBlank())
            .map(FraudTenantIds::toUuid);
    }

    static Optional<UUID> currentUser() {
        return TenantContext.current()
            .map(TenantContext::getSubject)
            .filter(subject -> subject != null && !subject.isBlank())
            .map(FraudTenantIds::toUuid);
    }

    static UUID toUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
    }
}
