package com.agenticai.core.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only tenant lookups for other feature areas, so they use the tenancy area's service
 * interface instead of its {@link TenantRepository}.
 */
@Service
public class TenantDirectoryService {

    private final TenantRepository tenantRepository;

    public TenantDirectoryService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> find(UUID tenantId) {
        return tenantRepository.findById(tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findBySchemaName(String schemaName) {
        return tenantRepository.findBySchemaName(schemaName);
    }

    @Transactional(readOnly = true)
    public List<Tenant> all() {
        return tenantRepository.findAll();
    }

    /** Every non-demo tenant, including lifecycle states the main roster hides. */
    @Transactional(readOnly = true)
    public List<Tenant> rosterExcludingDemo() {
        return tenantRepository.findRosterExcludingDemo();
    }
}
