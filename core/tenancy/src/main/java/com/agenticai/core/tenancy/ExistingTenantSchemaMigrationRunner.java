package com.agenticai.core.tenancy;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Closes a real gap found running the Playwright E2E suite: {@link
 * TenantProvisioningService} only ever ran Flyway against a tenant's schema
 * once, at signup time. Every migration added to {@code
 * classpath:db/migration/tenant} afterward (Knowledge Stage F1's V15/V16,
 * F3c's V17, and every future one) would never reach a tenant created before
 * that migration existed - the first symptom being an unrelated background
 * job throwing {@code relation "..." does not exist} against that one
 * tenant's schema, as happened here for {@code knowledge_ingestion_job}.
 *
 * <p>Runs once per application startup, migrating every {@link
 * TenantStatus#ACTIVE} tenant's schema to current via {@link
 * TenantSchemaMigrator}. Deliberately NOT using {@link TenantSchemaIterator}
 * here - that utility exists to set {@link com.agenticai.core.security.TenantContext}
 * so a *JPA repository* call gets routed to the right schema by Hibernate's
 * multi-tenancy resolver; this class talks to Flyway directly against a
 * schema name via the raw {@link javax.sql.DataSource}, no JPA/TenantContext
 * involved, so that machinery doesn't apply here.
 *
 * <p>Safe to run every single boot: {@code Flyway#migrate()} is idempotent
 * (a schema already at the latest version is a fast no-op), and one
 * tenant's migration failing is logged and does not stop the others or
 * prevent the application from starting - same "one bad tenant never blocks
 * the rest" posture {@link TenantSchemaIterator} already established for
 * scheduled jobs, applied here to startup instead.
 *
 * <p>Unconditional on profiles, matching {@link TenantSchemaIterator}'s own
 * posture - {@link TenantRepository} and the {@code control_plane.tenant}
 * table it reads are always available regardless of which optional profiles
 * (security/knowledge/mcp/e2e) are active in a given run. But gated on
 * {@code app.tenancy.migrate-on-boot} (default true) because a schema
 * migration is a CONTROL-PLANE responsibility: the dedicated {@code
 * app-worker} deployable and any second API replica set it to false so
 * exactly one process shape ever migrates tenant schemas - N instances all
 * racing the same per-schema Flyway locks is how a routine rolling restart
 * turns into a boot stall.
 */
@Component
@ConditionalOnProperty(name = "app.tenancy.migrate-on-boot", havingValue = "true", matchIfMissing = true)
// HIGHEST_PRECEDENCE so this runner always precedes every other
// ApplicationRunner - most of all VersionSnapshotBackfillRunner (agents,
// LOWEST_PRECEDENCE), which blocks in TenantMigrationGate.waitForMigrations
// for up to 5 minutes until this runner opens the gate. With equal
// precedence the runner order was arbitrary; when the backfill ran first it
// starved this runner on the single main-thread callRunners loop, so the
// gate could never open until the 5-minute timeout elapsed - observed live
// as readiness staying REFUSING_TRAFFIC ~7.5 minutes into boot on
// 2026-09-28's pre-prod boot check (jstack: main TIMED_WAITING).
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExistingTenantSchemaMigrationRunner implements ApplicationRunner {

    private final TenantMigrationService migrationService;
    private final TenantMigrationGate gate;

    public ExistingTenantSchemaMigrationRunner(TenantMigrationService migrationService, TenantMigrationGate gate) {
        this.migrationService = migrationService;
        this.gate = gate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Failures are logged inside the service and deliberately never stop startup here.
        try {
            java.util.List<String> failed = migrationService.migrateAllActiveTenants();
            if (!failed.isEmpty()) {
                // The service recorded them in TenantMigrationFailures: the status guard answers
                // 403 for these tenants (instead of serving a stale schema) until a retry
                // (TenantMigrationRetryJob) or a restart migrates them.
                org.slf4j.LoggerFactory.getLogger(ExistingTenantSchemaMigrationRunner.class)
                        .error(
                                "Opening the readiness gate with {} tenant schema(s) failed to migrate;"
                                        + " they are refused (403) until migrated: {}",
                                failed.size(),
                                failed);
            }
        } finally {
            // Readiness (TenantMigrationReadinessIndicator) waits on this; opened
            // even after a failure so a bad tenant degrades exactly as before
            // rather than holding the whole revision out of rotation.
            gate.markMigrated();
        }
    }
}
