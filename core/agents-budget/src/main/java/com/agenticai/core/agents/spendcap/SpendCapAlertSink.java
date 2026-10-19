package com.agenticai.core.agents.spendcap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Receives a spend-cap threshold crossing (80 or 100 percent) exactly once per
 * tenant, month and stage - the once-ness is claimed atomically in
 * {@code control_plane.tenant} before any sink is called. Implemented in
 * core/platform (tenant-admin email + platform issue). Runs on a background
 * thread; exceptions are logged and swallowed.
 */
public interface SpendCapAlertSink {

    record Alert(UUID tenantId, String tenantSchema, String tenantName, int stage, BigDecimal capUsd,
                 BigDecimal monthToDateUsd, LocalDate monthStart) {
    }

    void onThresholdCrossed(Alert alert);
}
