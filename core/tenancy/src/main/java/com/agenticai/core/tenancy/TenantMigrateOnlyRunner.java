package com.agenticai.core.tenancy;

import java.util.List;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * "Migrate-only" mode ({@code app.tenancy.migrate-only=true}, default false):
 * the one-shot Kubernetes Job shape. By the time any {@link ApplicationRunner}
 * fires, Spring Boot's Flyway auto-configuration has already applied the
 * control-plane migrations (bean init), so this runner only adds the
 * all-tenant migration - via {@link TenantMigrationService}, the same code the
 * on-boot runner uses - and then terminates the JVM: exit 0 when every tenant
 * is current, non-zero otherwise (failing schemas are logged).
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so no other runner (boot
 * reconciliation, sweeps...) does work in a process that is about to exit. If
 * the control-plane Flyway migration fails the context never starts and the JVM
 * exits non-zero on its own, which the Job also reports as failed.
 *
 * <p>Concurrency: wrapped in {@link PgAdvisoryJobLock} so two concurrent Jobs
 * cannot double-migrate. The loser exits {@link #EXIT_LOCK_HELD} without
 * migrating (Job {@code backoffLimit} retries it later). Flyway's own
 * per-schema lock remains the second line of defence, and the advisory lock
 * is fail-open like every other user of it.
 *
 * <p>Exit codes: 0 all migrated, 1 one or more tenants failed or an
 * unexpected error, 75 another migrator holds the lock.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.tenancy.migrate-only", havingValue = "true")
public class TenantMigrateOnlyRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrateOnlyRunner.class);

    static final String LOCK_NAME = "tenant-schema-migrate-only";
    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_LOCK_HELD = 75;

    private final TenantMigrationService migrationService;
    private final PgAdvisoryJobLock jobLock;
    private final IntConsumer exitFunction;

    @Autowired
    public TenantMigrateOnlyRunner(TenantMigrationService migrationService, PgAdvisoryJobLock jobLock,
                                   ApplicationContext context) {
        this(migrationService, jobLock, code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    /** Test seam: inject the exit function so tests never kill the JVM. */
    TenantMigrateOnlyRunner(TenantMigrationService migrationService, PgAdvisoryJobLock jobLock,
                            IntConsumer exitFunction) {
        this.migrationService = migrationService;
        this.jobLock = jobLock;
        this.exitFunction = exitFunction;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitFunction.accept(migrate());
    }

    int migrate() {
        log.info("app.tenancy.migrate-only=true: control-plane migration done; migrating tenant schemas then exiting.");
        List<String> failed = new java.util.ArrayList<>();
        try {
            boolean ran = jobLock.tryRunExclusive(LOCK_NAME, () -> failed.addAll(migrationService.migrateAllActiveTenants()));
            if (!ran) {
                log.warn("Another migrate-only process holds the '{}' advisory lock - exiting {} without migrating.",
                    LOCK_NAME, EXIT_LOCK_HELD);
                return EXIT_LOCK_HELD;
            }
        } catch (RuntimeException e) {
            log.error("Migrate-only run failed unexpectedly", e);
            return EXIT_FAILED;
        }
        if (failed.isEmpty()) {
            log.info("Migrate-only run complete: all tenant schemas up to date. Exiting 0.");
            return EXIT_OK;
        }
        log.error("Migrate-only run FAILED for {} tenant schema(s): {}. Exiting {}.", failed.size(), failed, EXIT_FAILED);
        return EXIT_FAILED;
    }
}
