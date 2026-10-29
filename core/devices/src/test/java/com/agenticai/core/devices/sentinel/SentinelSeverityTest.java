package com.agenticai.core.devices.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link SentinelSeverity#atLeast} backs the "escalate only ALERT-or-worse" gate. */
class SentinelSeverityTest {

    @Test
    void orderingIsInfoWatchAlertCritical() {
        assertThat(SentinelSeverity.INFO.ordinal()).isLessThan(SentinelSeverity.WATCH.ordinal());
        assertThat(SentinelSeverity.WATCH.ordinal()).isLessThan(SentinelSeverity.ALERT.ordinal());
        assertThat(SentinelSeverity.ALERT.ordinal())
                .isLessThan(SentinelSeverity.CRITICAL.ordinal());
    }

    @Test
    void atLeastIsInclusiveAndOrdered() {
        assertThat(SentinelSeverity.ALERT.atLeast(SentinelSeverity.ALERT)).isTrue();
        assertThat(SentinelSeverity.CRITICAL.atLeast(SentinelSeverity.ALERT)).isTrue();
        assertThat(SentinelSeverity.WATCH.atLeast(SentinelSeverity.ALERT)).isFalse();
        assertThat(SentinelSeverity.INFO.atLeast(SentinelSeverity.WATCH)).isFalse();
    }
}
