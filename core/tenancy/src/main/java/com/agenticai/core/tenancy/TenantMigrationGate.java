package com.agenticai.core.tenancy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "Have this JVM's existing tenant schemas been brought up to date yet?" -
 * the state behind the readiness gate.
 *
 * <p>Why it exists (prod, 2026-09-24): Container Apps routes traffic to a new
 * revision the moment its readiness probe passes, but
 * {@link ExistingTenantSchemaMigrationRunner} is an {@code ApplicationRunner},
 * so it starts <em>after</em> the web server is already answering. For the
 * ~70 s it took to migrate 52 schemas, new code ran against old schemas and
 * the Agents page 500'd with {@code relation "agent_experiment" does not
 * exist}. The readiness health group now includes an indicator that reads this
 * gate, so the old revision keeps serving until the new one is really ready.
 *
 * <p>Safety valve: prod's liveness probe hits the same readiness endpoint
 * (60 s initial delay, 6 x 10 s failures), so a gate that never opened would
 * get the pod killed mid-migration. {@link #isOpen()} therefore also opens
 * after {@code app.tenancy.readiness-gate-max-seconds} (default 60) even if
 * the runner is still going - the pre-gate behaviour, with a WARN. With
 * parallel migration (see {@link TenantMigrationService}) the runner normally
 * finishes well inside that budget.
 *
 * <p>Opened at construction when {@code app.tenancy.migrate-on-boot=false},
 * because then nothing would ever call {@link #markMigrated()}.
 */
@Component
public class TenantMigrationGate {

    private final AtomicBoolean migrated = new AtomicBoolean(false);
    private final Instant armedAt;
    private final Duration maxHold;

    // Two constructors => Spring must be told which to inject (see the same note
    // on TenantMigrationReadinessIndicator).
    @Autowired
    public TenantMigrationGate(
        @Value("${app.tenancy.migrate-on-boot:true}") boolean migrateOnBoot,
        @Value("${app.tenancy.readiness-gate-max-seconds:60}") long maxHoldSeconds
    ) {
        this(migrateOnBoot, maxHoldSeconds, Clock.systemUTC());
    }

    /** Clock-injecting constructor for tests (the indicator's tests live in core/app). */
    public TenantMigrationGate(boolean migrateOnBoot, long maxHoldSeconds, Clock clock) {
        this.armedAt = clock.instant();
        this.maxHold = Duration.ofSeconds(Math.max(0, maxHoldSeconds));
        if (!migrateOnBoot) {
            migrated.set(true);
        }
    }

    /** Called once by the boot runner when every ACTIVE schema has been attempted. */
    public void markMigrated() {
        migrated.set(true);
    }

    public boolean isMigrated() {
        return migrated.get();
    }

    /** Milliseconds this JVM has been waiting for the migration to finish. */
    public long heldFor(Clock clock) {
        return Duration.between(armedAt, clock.instant()).toMillis();
    }

    public Duration maxHold() {
        return maxHold;
    }

    /** True once migrated, or once the safety-valve budget has elapsed. */
    public boolean isOpen(Clock clock) {
        return isMigrated() || heldFor(clock) >= maxHold.toMillis();
    }
}
