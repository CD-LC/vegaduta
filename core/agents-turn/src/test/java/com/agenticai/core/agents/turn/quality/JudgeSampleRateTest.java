package com.agenticai.core.agents.turn.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The judge's sample rate is a setting an admin can change at run time: the configured value is the default, a
 * platform-setting row overrides it, the result is always a probability, and the per-turn read is time-boxed.
 */
class JudgeSampleRateTest {

    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final AtomicLong now = new AtomicLong(1_000);

    private JudgeSampleRate rate(long cacheMs) {
        return new JudgeSampleRate(settings, cacheMs, now::get);
    }

    @Test
    void withNoRowTheConfiguredRateIsUsed() {
        when(settings.getDouble(eq(JudgeSampleRate.KEY), anyDouble())).thenAnswer(inv -> inv.getArgument(1));

        assertThat(rate(0).current(0.05)).isEqualTo(0.05);
    }

    @Test
    void aPlatformSettingRowOverridesTheConfiguredRate() {
        when(settings.getDouble(JudgeSampleRate.KEY, 0.05)).thenReturn(0.5);

        assertThat(rate(0).current(0.05)).isEqualTo(0.5);
    }

    @Test
    void theRateIsAlwaysAProbability() {
        when(settings.getDouble(JudgeSampleRate.KEY, 0.05)).thenReturn(7.0, -3.0, Double.NaN);
        JudgeSampleRate r = rate(0);

        assertThat(r.current(0.05)).isEqualTo(1.0);
        assertThat(r.current(0.05)).isEqualTo(0.0);
        assertThat(r.current(0.05)).isEqualTo(0.0);
    }

    @Test
    void theSettingIsReadOnceUntilTheCacheExpires() {
        when(settings.getDouble(JudgeSampleRate.KEY, 0.05)).thenReturn(0.2, 0.9);
        JudgeSampleRate r = rate(10_000);

        assertThat(r.current(0.05)).isEqualTo(0.2);
        now.addAndGet(9_999);
        assertThat(r.current(0.05)).isEqualTo(0.2);
        verify(settings, times(1)).getDouble(JudgeSampleRate.KEY, 0.05);
        now.addAndGet(2);
        assertThat(r.current(0.05)).isEqualTo(0.9);
    }

    @Test
    void withoutAPlatformSettingsServiceTheConfiguredRateStands() {
        assertThat(new JudgeSampleRate(null, 10_000, now::get).current(0.05)).isEqualTo(0.05);
        assertThat(new JudgeSampleRate(null, 10_000, now::get).current(3.0)).isEqualTo(1.0);
    }
}
