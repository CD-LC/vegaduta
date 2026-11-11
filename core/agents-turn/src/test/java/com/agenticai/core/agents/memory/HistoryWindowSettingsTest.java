package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.tenancy.PlatformSettingsService;
import org.junit.jupiter.api.Test;

/**
 * {@code chat.history.*}: a workspace's own replay window replaces the platform's for that
 * workspace, and a missing catalog or a failing read leaves the platform window in force.
 */
class HistoryWindowSettingsTest {

    @org.junit.jupiter.api.AfterEach
    void releaseTheCatalog() {
        HistoryWindowSettings.use(null);
    }

    private static HistoryWindowSettings platformWindow() {
        PlatformSettingsService platform = mock(PlatformSettingsService.class);
        when(platform.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        return new HistoryWindowSettings(platform, 40, 32_000, 8_000, 10_000);
    }

    @Test
    void withoutACatalogTheWorkspaceGetsThePlatformWindow() {
        HistoryWindowSettings settings = platformWindow();

        assertThat(settings.current())
                .isEqualTo(new HistoryWindowSettings.HistoryConfig(40, 32_000, 8_000));
        assertThat(settings.current()).isSameAs(settings.platform());
    }

    @Test
    void aWorkspaceValueReplacesThePlatformWindow() {
        HistoryWindowSettings settings = platformWindow();
        SettingsResolver catalog = mock(SettingsResolver.class);
        when(catalog.getInt(
                        eq(HistoryWindowSettings.MAX_TURNS),
                        any(com.agenticai.core.settings.SettingContext.class)))
                .thenReturn(8);
        when(catalog.getInt(
                        eq(HistoryWindowSettings.MAX_CHARS),
                        any(com.agenticai.core.settings.SettingContext.class)))
                .thenReturn(6_000);
        when(catalog.getInt(
                        eq(HistoryWindowSettings.MAX_TOKENS),
                        any(com.agenticai.core.settings.SettingContext.class)))
                .thenReturn(1_500);

        HistoryWindowSettings.use(catalog);

        assertThat(settings.current())
                .isEqualTo(new HistoryWindowSettings.HistoryConfig(8, 6_000, 1_500));
        assertThat(settings.platform())
                .isEqualTo(new HistoryWindowSettings.HistoryConfig(40, 32_000, 8_000));
    }

    @Test
    void aStoredValueBelowTheFloorNeverBlanksTheWindow() {
        HistoryWindowSettings settings = platformWindow();
        SettingsResolver catalog = mock(SettingsResolver.class);
        when(catalog.getInt(anyString(), any(com.agenticai.core.settings.SettingContext.class)))
                .thenReturn(-5);
        HistoryWindowSettings.use(catalog);

        assertThat(settings.current()).isEqualTo(new HistoryWindowSettings.HistoryConfig(1, 0, 0));
    }

    @Test
    void aFailingReadKeepsThePlatformWindow() {
        HistoryWindowSettings settings = platformWindow();
        SettingsResolver catalog = mock(SettingsResolver.class);
        when(catalog.getInt(anyString(), any(com.agenticai.core.settings.SettingContext.class)))
                .thenThrow(new IllegalStateException("settings store down"));
        HistoryWindowSettings.use(catalog);

        assertThat(settings.current())
                .isEqualTo(new HistoryWindowSettings.HistoryConfig(40, 32_000, 8_000));
    }

    @Test
    void theCatalogEntriesAreWorkspaceWideAndDefaultToTheShippedWindow() {
        java.util.List<SettingDefinition> definitions = new HistoryWindowCatalog().definitions();

        assertThat(definitions)
                .extracting(SettingDefinition::key)
                .containsExactly(
                        "chat.history.maxTurns", "chat.history.maxChars", "chat.history.maxTokens");
        assertThat(definitions)
                .allSatisfy(d -> assertThat(d.scopes()).containsExactly(SettingScope.TENANT));
        assertThat(definitions)
                .extracting(SettingDefinition::platformValue)
                .containsExactly(40L, 32_000L, 8_000L);
    }
}
