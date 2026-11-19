package com.agenticai.core.platform;

import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant id / schema-name resolution for the platform console controllers, so they never touch
 * {@link TenantRepository} or the {@link Tenant} entity directly.
 */
@Service
public class PlatformTenantLookupService {

    private final TenantRepository tenantRepository;

    public PlatformTenantLookupService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /** The schema name of the tenant with this id, if any. */
    @Transactional(readOnly = true)
    public Optional<String> findSchemaNameById(UUID tenantId) {
        return tenantRepository.findById(tenantId).map(Tenant::getSchemaName);
    }

    /** The schema name when a tenant with exactly this schema name exists. */
    @Transactional(readOnly = true)
    public Optional<String> findSchemaNameBySchemaName(String schemaName) {
        return tenantRepository.findBySchemaName(schemaName).map(Tenant::getSchemaName);
    }

    /**
     * The schema name of the tenant with this id.
     *
     * @throws PlatformTenantNotFoundException when no tenant has that id
     */
    @Transactional(readOnly = true)
    public String requireSchemaName(UUID tenantId) {
        return findSchemaNameById(tenantId)
                .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
    }
}
