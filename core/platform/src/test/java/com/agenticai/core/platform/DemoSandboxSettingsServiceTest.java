package com.agenticai.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the inactivityTimeoutHours knob added 2026-07-26 (migration V134)
 * and the editableHours knob added 2026-07-28 (the 24h-editable / view-only
 * split).
 */
class DemoSandboxSettingsServiceTest {

    private PlatformSettingsService settings;
    private DemoSandboxSettingsService service;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        service = new DemoSandboxSettingsService(
            settings,
            true, 2, 1440, 3, 5, 20, 36, true, 2, true, true, 3, 1, 3,
            24, // defaultInactivityTimeoutHours
            24, // defaultEditableHours
            "" // defaultExemptIps
        );
    }

    @Test
    void effectiveReportsTheNewFieldWithItsCodeDefault() {
        when(settings.getInt(eq("demo.inactivity-timeout-hours"), any(Integer.class))).thenReturn(24);
        // Other getInt/getBoolean calls fall back to whatever default is passed - fine for this test.
        when(settings.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));

        DemoSettingsResponse response = service.get();

        assertThat(response.effective().inactivityTimeoutHours()).isEqualTo(24);
        assertThat(response.defaults().inactivityTimeoutHours()).isEqualTo(24);
        assertThat(response.settingKeys()).containsEntry("inactivityTimeoutHours", "demo.inactivity-timeout-hours");
    }

    @Test
    void updateWritesTheNewKeyWhenSupplied() {
        when(settings.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));

        DemoSettingsUpdateRequest request = new DemoSettingsUpdateRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            12, // inactivityTimeoutHours
            48, // editableHours
            " 10.0.0.5 , 122.172.81.185,10.0.0.5 " // exemptIps - messy on purpose
        );

        service.update(request, "admin@test");

        verify(settings).update(eq("demo.inactivity-timeout-hours"), eq("12"), eq("admin@test"));
        verify(settings).update(eq("demo.editable-hours"), eq("48"), eq("admin@test"));
        // Stored canonically: trimmed, deduped, sorted.
        verify(settings).update(eq("demo.rate-limit.exempt-ips"), eq("10.0.0.5,122.172.81.185"), eq("admin@test"));
    }

    @Test
    void updateRejectsNonIpExemptList() {
        DemoSettingsUpdateRequest garbage = new DemoSettingsUpdateRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, "https://evil.example/not-an-ip"
        );

        assertThatThrownBy(() -> service.update(garbage, "admin@test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exemptIps");
    }

    @Test
    void updateRejectsOutOfRangeValue() {
        DemoSettingsUpdateRequest tooHigh = new DemoSettingsUpdateRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, 200, null, null
        );

        assertThatThrownBy(() -> service.update(tooHigh, "admin@test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inactivityTimeoutHours")
            .hasMessageContaining("1 and 168");
    }

    @Test
    void updateLeavesTheKeyAloneWhenFieldIsNull() {
        when(settings.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));

        DemoSettingsUpdateRequest onlyEnabled = new DemoSettingsUpdateRequest(
            true, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        service.update(onlyEnabled, "admin@test");

        verify(settings, org.mockito.Mockito.never()).update(eq("demo.inactivity-timeout-hours"), anyString(), anyString());
    }
}
