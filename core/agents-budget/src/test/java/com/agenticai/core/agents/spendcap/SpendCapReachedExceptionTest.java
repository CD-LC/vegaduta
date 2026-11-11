package com.agenticai.core.agents.spendcap;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.TenantLimitExceededException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SpendCapReachedException}'s person-facing message (cap + reset date + the BYOK
 * escape hatch), its {@code TenantLimitExceededException} lineage and the {@link
 * SpendCapAlertSink.Alert} record the threshold-crossing sinks receive.
 */
class SpendCapReachedExceptionTest {

    @Test
    void messageNamesTheCapResetDateAndByokEscapeHatch() {
        SpendCapReachedException e =
                new SpendCapReachedException(
                        new BigDecimal("50"),
                        new BigDecimal("50"),
                        Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(e).isInstanceOf(TenantLimitExceededException.class);
        assertThat(e.getMessage())
                .contains("$50")
                .contains("2026-10-01")
                .contains("own provider keys");
        assertThat(SpendCapReachedException.ERROR_CODE).isEqualTo("spend_cap_reached");
    }

    @Test
    void gettersReturnWhatWasPassed() {
        Instant resets = Instant.parse("2026-11-01T00:00:00Z");
        SpendCapReachedException e =
                new SpendCapReachedException(new BigDecimal("25"), new BigDecimal("24.50"), resets);

        assertThat(e.getCapUsd()).isEqualByComparingTo("25");
        assertThat(e.getMonthToDateUsd()).isEqualByComparingTo("24.50");
        assertThat(e.getResetsAt()).isEqualTo(resets);
    }

    @Test
    void alertRecordCarriesTheThresholdContext() {
        UUID tenantId = UUID.randomUUID();
        SpendCapAlertSink.Alert alert =
                new SpendCapAlertSink.Alert(
                        tenantId,
                        "acme_corp",
                        "Acme Corp",
                        80,
                        new BigDecimal("50"),
                        new BigDecimal("40"),
                        LocalDate.of(2026, 9, 1));

        assertThat(alert.tenantId()).isEqualTo(tenantId);
        assertThat(alert.tenantSchema()).isEqualTo("acme_corp");
        assertThat(alert.tenantName()).isEqualTo("Acme Corp");
        assertThat(alert.stage()).isEqualTo(80);
        assertThat(alert.capUsd()).isEqualByComparingTo("50");
        assertThat(alert.monthToDateUsd()).isEqualByComparingTo("40");
        assertThat(alert.monthStart()).isEqualTo(LocalDate.of(2026, 9, 1));
    }
}
