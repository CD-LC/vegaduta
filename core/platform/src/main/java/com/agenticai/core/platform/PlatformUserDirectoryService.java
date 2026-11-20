package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.AppUserDirectoryService;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantDirectoryService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only tenant / last-seen lookups behind {@link PlatformUserController}, so the controller
 * never touches repositories or entities directly.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class PlatformUserDirectoryService {

    private final TenantDirectoryService tenants;
    private final AppUserDirectoryService appUsers;

    public PlatformUserDirectoryService(
            TenantDirectoryService tenants, AppUserDirectoryService appUsers) {
        this.tenants = tenants;
        this.appUsers = appUsers;
    }

    /** The schema name of the tenant with this id, if any. */
    @Transactional(readOnly = true)
    public Optional<String> schemaNameOf(UUID tenantId) {
        return tenants.find(tenantId).map(Tenant::getSchemaName);
    }

    /** Tenant display names keyed by schema name. */
    @Transactional(readOnly = true)
    public Map<String, String> tenantNamesBySchema() {
        Map<String, String> names = new HashMap<>();
        for (Tenant t : tenants.all()) {
            names.put(t.getSchemaName(), t.getName());
        }
        return names;
    }

    /** Last-seen timestamps keyed by Keycloak user id. */
    @Transactional(readOnly = true)
    public Map<String, Instant> lastSeenByKeycloakId() {
        return appUsers.lastSeenByKeycloakId();
    }
}
