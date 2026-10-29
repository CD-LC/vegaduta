package com.agenticai.core.tenancy;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one implementation of "bring every ACTIVE tenant schema up to date", shared by {@link
 * ExistingTenantSchemaMigrationRunner} (on-boot, today's behaviour) and {@link
 * TenantMigrateOnlyRunner} (one-shot Job). Always a bean (no property gate) so the migrate-only Job
 * works even when {@code app.tenancy.migrate-on-boot=false}.
 *
 * <p>One tenant's failure is logged and does not stop the others - the same "one bad tenant never
 * blocks the rest" posture as before; the caller decides what a non-empty failure list means (the
 * boot runner warns, the Job exits non-zero).
 *
 * <p>Schemas are migrated {@code app.tenancy.migrate-parallelism} at a time (default 4; 1 = the old
 * sequential loop). Prod took ~70 s for 52 schemas sequentially, which is longer than the readiness
 * gate can safely hold (see {@link TenantMigrationGate}); each schema is an independent Flyway run
 * against its own {@code flyway_schema_history}, so overlapping them is safe, and 4 stays well
 * inside the Hikari pool (15) alongside boot-time traffic. Each run holds 2 pooled connections
 * (Flyway's lock connection plus the script connection), so the parallelism is capped to what
 * {@code spring.datasource.hikari.maximum-pool-size} allows (see {@link #boundedParallelism}); the
 * Helm migrate Job's pool is sized for the default 4.
 */
@Component
public class TenantMigrationService {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationService.class);

    private final TenantRepository tenantRepository;
    private final TenantSchemaMigrator schemaMigrator;
    private final int parallelism;
    private TenantMigrationFailures failures = new TenantMigrationFailures();

    /** Hikari's default maximum-pool-size when the property is not set (application.yml). */
    static final int DEFAULT_POOL_SIZE = 15;

    /**
     * Pooled connections one Flyway run holds at once: on PostgreSQL Flyway keeps a second
     * connection for its transactional advisory lock besides the one that runs the scripts.
     */
    static final int CONNECTIONS_PER_FLYWAY_RUN = 2;

    /**
     * Connections the process needs besides the parallel runs: the migrate-only Job's advisory-lock
     * connection (held for the whole run) plus one for the tenant lookup and other short queries.
     */
    static final int RESERVED_CONNECTIONS = 2;

    public TenantMigrationService(
            TenantRepository tenantRepository,
            TenantSchemaMigrator schemaMigrator,
            int parallelism) {
        this(tenantRepository, schemaMigrator, parallelism, DEFAULT_POOL_SIZE);
    }

    @Autowired
    public TenantMigrationService(
            TenantRepository tenantRepository,
            TenantSchemaMigrator schemaMigrator,
            @Value("${app.tenancy.migrate-parallelism:4}") int parallelism,
            @Value("${spring.datasource.hikari.maximum-pool-size:" + DEFAULT_POOL_SIZE + "}")
                    int poolSize) {
        this.tenantRepository = tenantRepository;
        this.schemaMigrator = schemaMigrator;
        this.parallelism = boundedParallelism(parallelism, poolSize);
        if (this.parallelism < Math.max(1, parallelism)) {
            log.warn(
                    "app.tenancy.migrate-parallelism={} needs about {} pooled connections but the"
                            + " pool has {}; running {} schema(s) at a time so Flyway runs do not"
                            + " starve each other of connections. Raise DB_HIKARI_MAX_POOL_SIZE to use"
                            + " the full parallelism.",
                    parallelism,
                    RESERVED_CONNECTIONS + CONNECTIONS_PER_FLYWAY_RUN * parallelism,
                    poolSize,
                    this.parallelism);
        }
    }

    /** Wired by Spring so the status guard and this service share one failure registry. */
    @Autowired
    public void setFailures(TenantMigrationFailures failures) {
        this.failures = failures;
    }

    /**
     * Re-runs the migration for the schemas that failed earlier; a success clears the schema's
     * failed mark so {@link TenantStatusGuardImpl} serves it again.
     *
     * @return the schemas that still fail
     */
    public List<String> retryFailed() {
        List<String> still = new ArrayList<>();
        for (String schema : failures.snapshot()) {
            try {
                schemaMigrator.migrate(schema);
                failures.recordSuccess(schema);
                log.info(
                        "Retry migration of tenant schema {} succeeded; tenant is served again.",
                        schema);
            } catch (RuntimeException e) {
                still.add(schema);
                log.warn(
                        "Retry migration of tenant schema {} failed again: {}",
                        schema,
                        e.toString());
            }
        }
        return still;
    }

    /**
     * The requested parallelism, lowered so that {@code RESERVED_CONNECTIONS + runs *
     * CONNECTIONS_PER_FLYWAY_RUN} fits in the pool (never below 1).
     */
    static int boundedParallelism(int requested, int poolSize) {
        int fitsInPool =
                Math.max(1, (poolSize - RESERVED_CONNECTIONS) / CONNECTIONS_PER_FLYWAY_RUN);
        return Math.max(1, Math.min(requested, fitsInPool));
    }

    /**
     * Migrates every ACTIVE tenant's schema to current.
     *
     * @return the schema names that failed to migrate (empty on full success)
     */
    public List<String> migrateAllActiveTenants() {
        List<Tenant> tenants = tenantRepository.findByStatus(TenantStatus.ACTIVE);
        List<String> failedSchemas = Collections.synchronizedList(new ArrayList<>());
        if (tenants.isEmpty()) {
            return failedSchemas;
        }
        int workers = Math.min(parallelism, tenants.size());
        log.info(
                "Bringing {} existing tenant schema(s) up to date with the latest migrations ({} in"
                        + " parallel)...",
                tenants.size(),
                workers);
        long started = System.nanoTime();
        int skippedBefore = schemaMigrator.skippedUpToDate();
        ExecutorService pool =
                TenantAwareExecutors.newFixedThreadPool(
                        workers, TenantAwareExecutors.threadFactory("tenant-migrate", true));
        try {
            List<Future<?>> futures = new ArrayList<>(tenants.size());
            for (Tenant tenant : tenants) {
                futures.add(pool.submit(() -> migrateOne(tenant, failedSchemas)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    // migrateOne catches RuntimeException itself; anything else here is a bug worth
                    // seeing.
                    log.error("Tenant migration task failed unexpectedly", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn(
                            "Interrupted while waiting for tenant migrations - remaining schemas"
                                    + " may be stale");
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        if (failedSchemas.isEmpty()) {
            // Only non-zero when app.tenancy.migrate-skip-up-to-date=true (see
            // TenantSchemaMigrator).
            int skipped = schemaMigrator.skippedUpToDate() - skippedBefore;
            String skippedNote =
                    skipped > 0
                            ? ", " + skipped + " already current and skipped without Flyway"
                            : "";
            log.info(
                    "All {} existing tenant schema(s) are up to date ({} s{}).",
                    tenants.size(),
                    seconds,
                    skippedNote);
        } else {
            log.warn(
                    "{} of {} tenant schema(s) failed startup migration ({} s) - see errors above.",
                    failedSchemas.size(),
                    tenants.size(),
                    seconds);
        }
        return new ArrayList<>(failedSchemas);
    }

    private void migrateOne(Tenant tenant, List<String> failedSchemas) {
        String schema = tenant.getSchemaName();
        try {
            schemaMigrator.migrate(schema);
            failures.recordSuccess(schema);
        } catch (RuntimeException e) {
            failedSchemas.add(schema);
            failures.recordFailure(schema);
            log.error(
                    "Startup migration failed for tenant '{}' (schema {}) - that tenant may still"
                            + " be missing recently-added tables/columns until this is fixed and core"
                            + " is restarted",
                    tenant.getName(),
                    schema,
                    e);
        }
    }
}
