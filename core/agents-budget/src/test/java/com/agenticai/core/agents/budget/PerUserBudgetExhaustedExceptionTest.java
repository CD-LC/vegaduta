package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.TenantLimitExceededException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PerUserBudgetExhaustedException}'s payable-402 shape: it stays a {@link
 * TenantLimitExceededException} for every existing catch-site, and the extra fields the 402 body is
 * built from round-trip through the getters (resetSeconds nullable).
 */
class PerUserBudgetExhaustedExceptionTest {

    @Test
    void carriesTheModalityCapSpentAndResetDetails() {
        PerUserBudgetExhaustedException e =
                new PerUserBudgetExhaustedException(
                        "Daily text budget reached",
                        "text",
                        new BigDecimal("0.20"),
                        new BigDecimal("0.15"),
                        43_200L);

        assertThat(e).isInstanceOf(TenantLimitExceededException.class);
        assertThat(e.getMessage()).isEqualTo("Daily text budget reached");
        assertThat(e.getModality()).isEqualTo("text");
        assertThat(e.getCapUsd()).isEqualByComparingTo("0.20");
        assertThat(e.getSpentUsd()).isEqualByComparingTo("0.15");
        assertThat(e.getResetSeconds()).isEqualTo(43_200L);
    }

    @Test
    void resetSecondsMayBeAbsentWhenTheStoreCannotReportIt() {
        PerUserBudgetExhaustedException e =
                new PerUserBudgetExhaustedException(
                        "capped", "voice", new BigDecimal("0.15"), new BigDecimal("0.15"), null);

        assertThat(e.getResetSeconds()).isNull();
    }
}
