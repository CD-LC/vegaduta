package com.agenticai.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** {@code app.edition.billing-enabled}: off means "every plan limit is unlimited", with no setting read. */
class FreeModeServiceBillingEditionTest {

    @Test
    void billingEditionOffReportsFreeModeWithoutReadingTheSetting() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        FreeModeService service = new FreeModeService(settings, 0L, false);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.isBillingEdition()).isFalse();
        verifyNoInteractions(settings);
    }

    @Test
    void billingEditionOffIgnoresAnAdminSettingOfFalse() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getBoolean(FreeModeService.KEY, false)).thenReturn(false);

        assertThat(new FreeModeService(settings, 0L, false).isEnabled()).isTrue();
    }

    @Test
    void defaultConstructorKeepsTodaysBehaviourAndFollowsTheSetting() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getBoolean(FreeModeService.KEY, false)).thenReturn(false);
        FreeModeService service = new FreeModeService(settings, 0L);

        assertThat(service.isBillingEdition()).isTrue();
        assertThat(service.isEnabled()).isFalse();
        verify(settings).getBoolean(FreeModeService.KEY, false);

        when(settings.getBoolean(FreeModeService.KEY, false)).thenReturn(true);
        assertThat(service.isEnabled()).isTrue();
    }
}
