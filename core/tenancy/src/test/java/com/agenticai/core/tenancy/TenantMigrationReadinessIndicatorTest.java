package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/** The readiness gate's three answers: still migrating, migrated, budget elapsed. */
class TenantMigrationReadinessIndicatorTest {

    private static final Instant T0 = Instant.parse("2026-09-24T00:00:00Z");

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void outOfServiceWhileMigratingInsideTheBudget() {
        TenantMigrationGate gate = new TenantMigrationGate(true, 60, at(T0));
        TenantMigrationReadinessIndicator indicator =
            new TenantMigrationReadinessIndicator(gate, at(T0.plus(Duration.ofSeconds(10))));

        assertEquals(Status.OUT_OF_SERVICE, indicator.health().getStatus());
        assertEquals("migrating", indicator.health().getDetails().get("tenantSchemas"));
    }

    @Test
    void upOnceTheRunnerMarksMigrated() {
        TenantMigrationGate gate = new TenantMigrationGate(true, 60, at(T0));
        gate.markMigrated();

        assertEquals(Status.UP, new TenantMigrationReadinessIndicator(gate, at(T0)).health().getStatus());
    }

    @Test
    void upAgainAfterTheBudgetElapsesEvenIfStillMigrating() {
        TenantMigrationGate gate = new TenantMigrationGate(true, 60, at(T0));
        TenantMigrationReadinessIndicator indicator =
            new TenantMigrationReadinessIndicator(gate, at(T0.plus(Duration.ofSeconds(61))));

        assertEquals(Status.UP, indicator.health().getStatus());
        assertEquals("migrating (gate budget elapsed)", indicator.health().getDetails().get("tenantSchemas"));
    }

    @Test
    void upImmediatelyWhenMigrateOnBootIsOff() {
        TenantMigrationGate gate = new TenantMigrationGate(false, 60, at(T0));

        assertEquals(Status.UP, new TenantMigrationReadinessIndicator(gate, at(T0)).health().getStatus());
    }
}
