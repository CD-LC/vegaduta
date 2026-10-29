package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SettingsProperties}: the {@code app.settings.*} defaults, the clamping setters (a bad
 * value can never produce a zero TTL, an empty cache, or a sub-20-character reason cap), and the
 * operator-pin lookups' documented case/dash/underscore-insensitive key matching.
 */
class SettingsPropertiesTest {

    @Test
    void defaultsMatchTheDocumentedValues() {
        SettingsProperties p = new SettingsProperties();

        assertThat(p.getCacheTtl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.getCacheMaxEntries()).isEqualTo(20_000);
        assertThat(p.getHistoryDefaultLimit()).isEqualTo(50);
        assertThat(p.getHistoryMaxLimit()).isEqualTo(200);
        assertThat(p.getBulkHistoryDefaultLimit()).isEqualTo(100);
        assertThat(p.getBulkHistoryMaxLimit()).isEqualTo(500);
        assertThat(p.getReasonMaxLength()).isEqualTo(500);
        assertThat(p.getLocked()).isEmpty();
        assertThat(p.getLockedReasons()).isEmpty();
    }

    @Test
    void normalizeKeyIgnoresCaseDashesUnderscoresAndDots() {
        assertThat(SettingsProperties.normalizeKey("Agent.Limits.Max-Tool_Steps"))
                .isEqualTo("agentlimitsmaxtoolsteps");
        assertThat(SettingsProperties.normalizeKey(null)).isEmpty();
    }

    @Test
    void isLockedMatchesAnEnvironmentVariableSpelling() {
        SettingsProperties p = new SettingsProperties();
        p.getLocked().put("agent.limits.max-tool-steps", true);

        assertThat(p.isLocked("agent.limits.maxToolSteps")).isTrue();
        assertThat(p.isLocked("AGENT_LIMITS_MAXTOOLSTEPS")).isTrue();
        assertThat(p.isLocked("agent.limits.other")).isFalse();
    }

    @Test
    void aFalsePinDoesNotLockAndAMissingReasonFallsBackToTheDefaultText() {
        SettingsProperties p = new SettingsProperties();
        p.getLocked().put("a.b", false);

        assertThat(p.isLocked("a.b")).isFalse();
        assertThat(p.lockedReason("a.b")).contains("Pinned by the platform operator");
    }

    @Test
    void lockedReasonReturnsTheOperatorTextWhenPresent() {
        SettingsProperties p = new SettingsProperties();
        p.getLockedReasons().put("chat.retry.max-attempts", "Pinned by SRE");

        assertThat(p.lockedReason("chat.retry.maxAttempts")).isEqualTo("Pinned by SRE");
        // A blank reason reads as no reason.
        p.getLockedReasons().put("chat.retry.max-attempts", "  ");
        assertThat(p.lockedReason("chat.retry.maxAttempts")).contains("Pinned by the platform operator");
    }

    @Test
    void settersClampOutOfRangeValues() {
        SettingsProperties p = new SettingsProperties();

        p.setCacheTtl(null);
        assertThat(p.getCacheTtl()).isEqualTo(Duration.ZERO);
        p.setCacheTtl(Duration.ofSeconds(-5));
        assertThat(p.getCacheTtl()).isEqualTo(Duration.ZERO);

        p.setCacheMaxEntries(0);
        assertThat(p.getCacheMaxEntries()).isEqualTo(1);
        p.setHistoryDefaultLimit(0);
        assertThat(p.getHistoryDefaultLimit()).isEqualTo(1);
        p.setHistoryMaxLimit(0);
        assertThat(p.getHistoryMaxLimit()).isEqualTo(1);
        p.setBulkHistoryDefaultLimit(0);
        assertThat(p.getBulkHistoryDefaultLimit()).isEqualTo(1);
        p.setBulkHistoryMaxLimit(0);
        assertThat(p.getBulkHistoryMaxLimit()).isEqualTo(1);
        p.setReasonMaxLength(5);
        assertThat(p.getReasonMaxLength()).isEqualTo(20);
    }

    @Test
    void nullMapsAreReplacedWithEmptyOnes() {
        SettingsProperties p = new SettingsProperties();

        p.setLocked(null);
        p.setLockedReasons(null);

        assertThat(p.getLocked()).isEmpty();
        assertThat(p.getLockedReasons()).isEmpty();

        p.setLocked(Map.of("a.b", true));
        p.setLockedReasons(Map.of("a.b", "why"));
        assertThat(p.getLocked()).containsEntry("a.b", true);
        assertThat(p.getLockedReasons()).containsEntry("a.b", "why");
    }
}
