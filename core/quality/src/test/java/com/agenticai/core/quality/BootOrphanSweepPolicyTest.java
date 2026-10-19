package com.agenticai.core.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BootOrphanSweepPolicyTest {

    @Test
    void unsetBlankOrUnknownModeKeepsTodaysFailEverythingBehaviour() {
        assertThat(BootOrphanSweepPolicy.mode(null)).isEqualTo(BootOrphanSweepPolicy.Mode.ALL);
        assertThat(BootOrphanSweepPolicy.mode("")).isEqualTo(BootOrphanSweepPolicy.Mode.ALL);
        assertThat(BootOrphanSweepPolicy.mode("all")).isEqualTo(BootOrphanSweepPolicy.Mode.ALL);
        assertThat(BootOrphanSweepPolicy.mode("typo")).isEqualTo(BootOrphanSweepPolicy.Mode.ALL);
    }

    @Test
    void staleAndOffParseCaseInsensitively() {
        assertThat(BootOrphanSweepPolicy.mode(" STALE ")).isEqualTo(BootOrphanSweepPolicy.Mode.STALE);
        assertThat(BootOrphanSweepPolicy.mode("Off")).isEqualTo(BootOrphanSweepPolicy.Mode.OFF);
    }

    @Test
    void minAgeParsesIsoDurationsAndFallsBackToTwoHours() {
        assertThat(BootOrphanSweepPolicy.minAge("PT30M")).isEqualTo(Duration.ofMinutes(30));
        assertThat(BootOrphanSweepPolicy.minAge(null)).isEqualTo(Duration.ofHours(2));
        assertThat(BootOrphanSweepPolicy.minAge("  ")).isEqualTo(Duration.ofHours(2));
        assertThat(BootOrphanSweepPolicy.minAge("2 hours")).isEqualTo(Duration.ofHours(2));
        assertThat(BootOrphanSweepPolicy.minAge("PT0S")).isEqualTo(Duration.ofHours(2));
        assertThat(BootOrphanSweepPolicy.minAge("-PT5M")).isEqualTo(Duration.ofHours(2));
    }
}
