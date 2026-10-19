package com.agenticai.core.tenancy.demo;

import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantSchemaGuard;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import com.agenticai.core.tenancy.TenantStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The one-time bootstrap of the single shared demo tenant (see {@link DemoSessionService}'s class
 * javadoc, "One SHARED tenant" and "Bootstrap ordering and compensation"): tenant row ({@code
 * PROVISIONING}) &rarr; schema + Flyway migration &rarr; tenant flips {@code ACTIVE}, with a
 * guarded schema drop on failure. Created and owned by {@link DemoSessionService}; not a bean.
 */
final class DemoSharedTenantBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DemoSharedTenantBootstrap.class);

    /** The one shared demo workspace's display name (bootstrap only - see {@link #getOrCreate}). */
    private static final String SHARED_DEMO_TENANT_NAME = "Demo Sandbox";

    private final TenantRepository tenantRepository;
    private final DemoSessionRecorder recorder;
    private final TenantSchemaMigrator schemaMigrator;
    private final DataSource dataSource;
    private final String keycloakRealm;
    private final String sharedDemoSchema;

    DemoSharedTenantBootstrap(
        TenantRepository tenantRepository,
        DemoSessionRecorder recorder,
        TenantSchemaMigrator schemaMigrator,
        DataSource dataSource,
        String keycloakRealm,
        String sharedDemoSchema
    ) {
        this.tenantRepository = tenantRepository;
        this.recorder = recorder;
        this.schemaMigrator = schemaMigrator;
        this.dataSource = dataSource;
        this.keycloakRealm = keycloakRealm;
        this.sharedDemoSchema = sharedDemoSchema;
    }

    /**
     * Returns the one shared demo tenant, provisioning it (real tenant row,
     * real migrated Postgres schema) exactly once if this is the first call
     * ever made on this deployment. Every call after that is a single
     * indexed lookup by {@link #sharedDemoSchema} - see {@code
     * TenantRepository#findBySchemaName}'s unique index on {@code
     * schema_name}.
     *
     * <p>Concurrency: the very first request(s) after a fresh deployment can
     * race here across threads/replicas. {@code schema_name} carries a
     * database-level unique constraint (the same one {@code
     * TenantProvisioningService#resolveUniqueSchemaName} relies on for
     * signup), so at most one {@code createProvisioningDemoTenant} insert can
     * ever win; every loser catches {@link DataIntegrityViolationException}
     * and simply re-reads the winner's row. No distributed lock needed for
     * something that only ever contends once per deployment's lifetime.
     */
    Tenant getOrCreate() {
        Optional<Tenant> existing = tenantRepository.findBySchemaName(sharedDemoSchema);
        if (existing.isPresent()) {
            return activeOrThrow(existing.get());
        }
        return provisionSharedDemoTenant();
    }

    private Tenant provisionSharedDemoTenant() {
        Tenant tenant;
        try {
            tenant = recorder.createProvisioningDemoTenant(SHARED_DEMO_TENANT_NAME, sharedDemoSchema, keycloakRealm);
        } catch (DataIntegrityViolationException raced) {
            // Another thread/replica's insert won the schema_name unique
            // constraint between our findBySchemaName and this insert.
            return tenantRepository.findBySchemaName(sharedDemoSchema)
                .map(this::activeOrThrow)
                .orElseThrow(() -> raced);
        }
        // SEC-001: only a schema this bootstrap actually created may be dropped
        // on rollback - never one that already existed (another tenant's, or
        // a platform/AGE schema that the configured name happens to match).
        boolean existedBefore = schemaExistsOrUnknown(sharedDemoSchema);
        try {
            schemaMigrator.migrate(sharedDemoSchema);
            return recorder.activateSharedDemoTenant(tenant.getId());
        } catch (RuntimeException e) {
            log.error("Shared demo sandbox bootstrap failed (schema {}) - rolling back", sharedDemoSchema, e);
            if (existedBefore
                || TenantSchemaGuard.isProtectedSchema(sharedDemoSchema)
                || TenantSchemaGuard.looksLikeGraphSchema(sharedDemoSchema)) {
                log.error("Demo rollback: NOT dropping schema {} - it existed before this bootstrap or is protected",
                    sharedDemoSchema);
            } else {
                dropSchemaQuietly(sharedDemoSchema);
            }
            recorder.markFailed(tenant.getId());
            throw new DemoSandboxUnavailableException(
                "The no-signup sandbox isn't ready yet. Please try again in a moment."
            );
        }
    }

    /** A shared tenant found in a terminal failure state cannot self-heal by retrying reads - surface it loudly instead of serving a broken sandbox. */
    private Tenant activeOrThrow(Tenant tenant) {
        if (tenant.getStatus() == TenantStatus.PROVISIONING_FAILED) {
            throw new DemoSandboxUnavailableException(
                "The no-signup sandbox is temporarily unavailable. You can still create a free account."
            );
        }
        return tenant;
    }

    /** Best-effort rollback, same posture (and same reasoning) as {@code TenantProvisioningService#compensate}. */
    private void dropSchemaQuietly(String schemaName) {
        // Defence in depth: the caller already refused pre-existing, protected and graph-shaped
        // schemas; re-check here so this DDL can never be reached with anything but a plain,
        // non-protected identifier (the name is operator config, app.demo.shared-schema).
        if (TenantSchemaGuard.isProtectedSchema(schemaName)) {
            log.error("Demo rollback: refusing to drop protected or malformed schema name {}", schemaName);
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + TenantSchemaGuard.quoteIdentifier(schemaName) + " CASCADE");
        } catch (SQLException e) {
            log.error("Demo rollback: failed to drop orphaned schema {}", schemaName, e);
        }
    }

    /** {@code true} if the schema exists OR its existence could not be determined (fail safe: never drop). */
    private boolean schemaExistsOrUnknown(String schemaName) {
        try (Connection connection = dataSource.getConnection()) {
            return TenantSchemaGuard.schemaExists(connection, schemaName);
        } catch (SQLException | RuntimeException e) {
            log.warn("Demo bootstrap: could not check whether schema {} exists - treating it as pre-existing", schemaName, e);
            return true;
        }
    }
}
