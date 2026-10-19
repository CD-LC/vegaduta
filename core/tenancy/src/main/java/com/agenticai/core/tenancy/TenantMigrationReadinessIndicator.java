package com.agenticai.core.tenancy;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness contributor {@code tenantMigration}: OUT_OF_SERVICE until this
 * JVM's existing tenant schemas are migrated, so a rolling deploy keeps the
 * previous revision serving until the new one can actually answer for every
 * tenant. Wired into the {@code readiness} health group in application.yml
 * ({@code management.endpoint.health.group.readiness.include}); the liveness
 * group is untouched. Rationale and the safety valve live on
 * {@link TenantMigrationGate}.
 *
 * <p>Bean name is explicit because Spring Boot derives the contributor name by
 * stripping the {@code HealthIndicator} suffix - the group include list must
 * match it exactly.
 */
@Component("tenantMigrationHealthIndicator")
public class TenantMigrationReadinessIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationReadinessIndicator.class);

    private final TenantMigrationGate gate;
    private final Clock clock;
    private volatile boolean warnedExpired;

    // @Autowired is REQUIRED here: with two constructors Spring needs to be told
    // which one to inject, otherwise it looks for a no-arg one and the context
    // fails ("No default constructor found") - which is exactly how the first
    // deploy of this class crash-looped (p-8fb2ae6d, 2026-09-24).
    @Autowired
    public TenantMigrationReadinessIndicator(TenantMigrationGate gate) {
        this(gate, Clock.systemUTC());
    }

    TenantMigrationReadinessIndicator(TenantMigrationGate gate, Clock clock) {
        this.gate = gate;
        this.clock = clock;
    }

    @Override
    public Health health() {
        if (gate.isMigrated()) {
            return Health.up().withDetail("tenantSchemas", "migrated").build();
        }
        long heldMs = gate.heldFor(clock);
        if (heldMs >= gate.maxHold().toMillis()) {
            if (!warnedExpired) {
                warnedExpired = true;
                log.warn("Tenant-schema migration still running after {} s - opening readiness anyway so the "
                    + "liveness probe does not kill this pod (raise app.tenancy.migrate-parallelism or "
                    + "app.tenancy.readiness-gate-max-seconds if this recurs)", gate.maxHold().toSeconds());
            }
            return Health.up().withDetail("tenantSchemas", "migrating (gate budget elapsed)").build();
        }
        return Health.outOfService()
            .withDetail("tenantSchemas", "migrating")
            .withDetail("waitedMs", heldMs)
            .build();
    }
}
