package com.agenticai.core.platform.abuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every knob is read fresh per scoring pass into one immutable {@link
 * AbuseThresholds} snapshot: a missing row must fall back to the documented
 * conservative default (never zero, never null), and a stored value must pass
 * through verbatim. Pinned because a default drifting toward zero would turn
 * the detector into a false-positive firehose.
 */
class AbuseConfigTest {

    private PlatformSettingsService settings;
    private AbuseConfig config;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        config = new AbuseConfig(settings);
    }

    @Test
    void missingRowsFallBackToTheConservativeDefaults() {
        when(settings.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        when(settings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        AbuseThresholds t = config.current();

        assertEquals(10, t.recentWindowMinutes());
        assertEquals(1440, t.baselineWindowMinutes());
        assertEquals(4.0, t.spendSpikeMultiplier());
        assertEquals(0.05, t.spendSpikeMinUsd());
        assertEquals(0.30, t.spendSpikeWeight());
        assertEquals(200_000, t.tokenBurnPerMinThreshold());
        assertEquals(0.25, t.tokenBurnWeight());
        assertEquals(60, t.messageFloodPerMinThreshold());
        assertEquals(0.20, t.messageFloodWeight());
        assertEquals(20, t.signupVelocityPerHourThreshold());
        assertEquals(0.10, t.signupVelocityWeight());
        assertEquals(0.5, t.toolErrorRatioThreshold());
        assertEquals(5, t.toolErrorMinSample());
        assertEquals(0.15, t.toolErrorWeight());
        assertEquals(0.20, t.geoVelocityWeight());
        assertEquals(40, t.watchThreshold());
        assertEquals(65, t.throttleThreshold());
        assertEquals(85, t.suspendThreshold());
        assertEquals(60, t.throttleTtlMinutes());
        assertFalse(t.autoSuspendEnabled(), "auto-suspend is opt-in, never defaulted on");
        assertFalse(t.scanEnabled(), "the sweep itself is opt-in too");
    }

    @Test
    void storedValuesPassThroughVerbatim() {
        when(settings.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        when(settings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        when(settings.getDouble("abuse.spend-spike.multiplier", 4.0)).thenReturn(9.5);
        when(settings.getLong("abuse.window.recent-minutes", 10)).thenReturn(30L);
        when(settings.getBoolean("abuse.scan.enabled", false)).thenReturn(true);

        AbuseThresholds t = config.current();

        assertEquals(9.5, t.spendSpikeMultiplier());
        assertEquals(30, t.recentWindowMinutes());
        assertTrue(t.scanEnabled());
        assertEquals(1440, t.baselineWindowMinutes(), "untouched keys keep their defaults");
    }
}
