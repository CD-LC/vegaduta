package com.agenticai.core.agents.job.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingSource;
import com.agenticai.core.settings.SettingValue;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The lookup the sweep and the resume builder share: the resolver when there is one, the platform properties otherwise or on failure. */
class ApprovalContinuationSettingsTest {

    private static final UUID AGENT = UUID.randomUUID();

    private static ApprovalContinuationProperties platform(boolean enabled, boolean jobsContinue) {
        ApprovalContinuationProperties p = new ApprovalContinuationProperties();
        p.setEnabled(enabled);
        p.setJobsContinueAfterDenial(jobsContinue);
        return p;
    }

    private static SettingsResolver answering(Map<String, Object> values) {
        return new SettingsResolver() {
            @Override
            public SettingValue get(String key, SettingContext ctx) {
                return new SettingValue(values.get(key), SettingSource.AGENT, false, null);
            }

            @Override
            public Map<String, SettingValue> resolveAll(SettingContext ctx) {
                return Map.of();
            }

            @Override
            public SettingsRegistry registry() {
                return new SettingsRegistry(List.of());
            }
        };
    }

    private static SettingsResolver failing() {
        return new SettingsResolver() {
            @Override
            public SettingValue get(String key, SettingContext ctx) {
                throw new IllegalStateException("settings unavailable");
            }

            @Override
            public Map<String, SettingValue> resolveAll(SettingContext ctx) {
                throw new IllegalStateException("settings unavailable");
            }

            @Override
            public SettingsRegistry registry() {
                return new SettingsRegistry(List.of());
            }
        };
    }

    @Test
    void withoutAResolverThePlatformPropertiesAnswerAndNoPropertiesMeansOff() {
        assertThat(ApprovalContinuationSettings.enabled(platform(true, false), null, AGENT)).isTrue();
        assertThat(ApprovalContinuationSettings.enabled(platform(false, false), null, AGENT)).isFalse();
        assertThat(ApprovalContinuationSettings.jobsContinueAfterDenial(platform(true, true), null, AGENT)).isTrue();
        assertThat(ApprovalContinuationSettings.enabled(null, null, AGENT)).isFalse();
        assertThat(ApprovalContinuationSettings.jobsContinueAfterDenial(null, null, AGENT)).isFalse();
    }

    @Test
    void theResolverWinsOverThePlatformProperties() {
        SettingsResolver resolver = answering(Map.of(
                ApprovalContinuationSettings.ENABLED, false,
                ApprovalContinuationSettings.JOBS_CONTINUE_AFTER_DENIAL, true));

        assertThat(ApprovalContinuationSettings.enabled(platform(true, false), resolver, AGENT)).isFalse();
        assertThat(ApprovalContinuationSettings.jobsContinueAfterDenial(platform(true, false), resolver, AGENT)).isTrue();
    }

    @Test
    void aFailingResolverFallsBackToThePlatformProperties() {
        assertThat(ApprovalContinuationSettings.enabled(platform(true, true), failing(), AGENT)).isTrue();
        assertThat(ApprovalContinuationSettings.jobsContinueAfterDenial(platform(true, true), failing(), AGENT)).isTrue();
        assertThat(ApprovalContinuationSettings.jobsContinueAfterDenial(platform(true, false), failing(), AGENT)).isFalse();
    }
}
