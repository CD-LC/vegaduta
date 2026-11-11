package com.agenticai.core.agents.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.context.ContextCompactionSettings.Thresholds;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingsResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code chat.compaction.*}: an agent's or a workspace's thresholds replace the deployment's for
 * that run, and a missing catalog or a failing read leaves the deployment's in force.
 */
class ContextCompactionSettingsTest {

    private static final Thresholds PLATFORM = new Thresholds(0.6, 0.85, 3);

    private static SettingsResolver catalog(double clearAt, double compactAt, int keep) {
        SettingsResolver catalog = mock(SettingsResolver.class);
        when(catalog.getDecimal(eq(ContextCompactionSettings.CLEAR_AT), any(SettingContext.class)))
                .thenReturn(clearAt);
        when(catalog.getDecimal(
                        eq(ContextCompactionSettings.COMPACT_AT), any(SettingContext.class)))
                .thenReturn(compactAt);
        when(catalog.getInt(
                        eq(ContextCompactionSettings.KEEP_RECENT_TOOL_RESULTS),
                        any(SettingContext.class)))
                .thenReturn(keep);
        return catalog;
    }

    @Test
    void withoutACatalogTheRunUsesTheDeploymentThresholds() {
        assertThat(ContextCompactionSettings.thresholds(null, UUID.randomUUID(), PLATFORM))
                .isSameAs(PLATFORM);
    }

    @Test
    void theAgentsValuesReplaceTheDeployments() {
        Thresholds at =
                ContextCompactionSettings.thresholds(
                        catalog(0.3, 0.5, 1), UUID.randomUUID(), PLATFORM);

        assertThat(at).isEqualTo(new Thresholds(0.3, 0.5, 1));
    }

    @Test
    void aClearShareAboveTheSummariseShareIsLoweredToIt() {
        Thresholds at = ContextCompactionSettings.thresholds(catalog(0.9, 0.5, 3), null, PLATFORM);

        assertThat(at.clearAt()).isEqualTo(0.5);
        assertThat(at.compactAt()).isEqualTo(0.5);
    }

    @Test
    void aFailingReadKeepsTheDeploymentThresholds() {
        SettingsResolver broken = mock(SettingsResolver.class);
        when(broken.getDecimal(anyString(), any(SettingContext.class)))
                .thenThrow(new IllegalStateException("settings store down"));

        assertThat(ContextCompactionSettings.thresholds(broken, UUID.randomUUID(), PLATFORM))
                .isSameAs(PLATFORM);
    }

    @Test
    void theCatalogDefaultsAreTheDeploymentsValues() {
        MockEnvironment env =
                new MockEnvironment().withProperty("app.context.compaction.clear-at", "0.4");

        assertThat(new ContextCompactionSettings(env).definitions())
                .extracting(SettingDefinition::key, SettingDefinition::platformValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chat.compaction.clearAt", 0.4),
                        org.assertj.core.groups.Tuple.tuple("chat.compaction.compactAt", 0.85),
                        org.assertj.core.groups.Tuple.tuple(
                                "chat.compaction.keepRecentToolResults", 3L));
    }
}
