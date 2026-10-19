package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The signup trial is driven by platform_setting rows (V473), so an admin can change it without a deploy. */
class SignupTrialSettingsTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private PlatformSettingsService settings;
    private TenantProvisioningRecorder recorder;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        when(settings.getBoolean(eq(TenantProvisioningRecorder.KEY_TRIAL_ENABLED), anyBoolean())).thenReturn(true);
        when(settings.getInt(eq(TenantProvisioningRecorder.KEY_TRIAL_MONTHS), anyInt())).thenReturn(3);
        when(settings.getString(eq(TenantProvisioningRecorder.KEY_TRIAL_PLAN), anyString())).thenReturn("UNLIMITED");
        when(settings.getString(eq(TenantProvisioningRecorder.KEY_TRIAL_FREE_UNTIL), anyString())).thenReturn("");
        recorder = new TenantProvisioningRecorder(mock(TenantRepository.class), mock(AppUserRepository.class));
        ReflectionTestUtils.setField(recorder, "platformSettings", settings);
    }

    @Test
    void defaultsAreThreeCalendarMonthsOfUnlimited() {
        TenantProvisioningRecorder.SignupGrant grant = recorder.signupGrant(NOW).orElseThrow();
        assertEquals(TenantPlan.UNLIMITED, grant.plan());
        assertEquals(Instant.parse("2027-01-08T10:00:00Z"), grant.expiresAt());
    }

    @Test
    void theMonthsAreConfigurableAndCalendarBased() {
        when(settings.getInt(eq(TenantProvisioningRecorder.KEY_TRIAL_MONTHS), anyInt())).thenReturn(1);
        // 31 January + 1 month is the end of February, never March (a calendar promise, not 30 days).
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"), recorder.signupGrant(Instant.parse("2026-01-31T10:00:00Z")).orElseThrow().expiresAt());
    }

    @Test
    void thePlanIsConfigurableToPremiumAndAnythingElseIsUnlimited() {
        when(settings.getString(eq(TenantProvisioningRecorder.KEY_TRIAL_PLAN), anyString())).thenReturn("premium");
        assertEquals(TenantPlan.PREMIUM, recorder.signupGrant(NOW).orElseThrow().plan());
        when(settings.getString(eq(TenantProvisioningRecorder.KEY_TRIAL_PLAN), anyString())).thenReturn("FREE");
        assertEquals(TenantPlan.UNLIMITED, recorder.signupGrant(NOW).orElseThrow().plan(), "FREE would be a no-op grant");
        when(settings.getString(eq(TenantProvisioningRecorder.KEY_TRIAL_PLAN), anyString())).thenReturn("garbage");
        assertEquals(TenantPlan.UNLIMITED, recorder.signupGrant(NOW).orElseThrow().plan());
    }

    @Test
    void disablingOrZeroMonthsMeansNoBonus() {
        when(settings.getBoolean(eq(TenantProvisioningRecorder.KEY_TRIAL_ENABLED), anyBoolean())).thenReturn(false);
        assertEquals(Optional.empty(), recorder.signupGrant(NOW));
        when(settings.getBoolean(eq(TenantProvisioningRecorder.KEY_TRIAL_ENABLED), anyBoolean())).thenReturn(true);
        when(settings.getInt(eq(TenantProvisioningRecorder.KEY_TRIAL_MONTHS), anyInt())).thenReturn(0);
        assertEquals(Optional.empty(), recorder.signupGrant(NOW));
    }

    @Test
    void monthsAreClampedToASaneRange() {
        when(settings.getInt(eq(TenantProvisioningRecorder.KEY_TRIAL_MONTHS), anyInt())).thenReturn(10_000);
        assertEquals(Instant.parse("2029-10-08T10:00:00Z"), recorder.signupGrant(NOW).orElseThrow().expiresAt());
        when(settings.getInt(eq(TenantProvisioningRecorder.KEY_TRIAL_MONTHS), anyInt())).thenReturn(-5);
        assertEquals(Optional.empty(), recorder.signupGrant(NOW));
    }

    @Test
    void aFixedEndDateActsAsAFloorOnlyWhenItIsLater() {
        when(settings.getString(eq(TenantProvisioningRecorder.KEY_TRIAL_FREE_UNTIL), anyString())).thenReturn("2027-06-01");
        assertEquals(Instant.parse("2027-06-01T00:00:00Z"), recorder.signupGrant(NOW).orElseThrow().expiresAt());
        when(settings.getString(eq(TenantProvisioningRecorder.KEY_TRIAL_FREE_UNTIL), anyString())).thenReturn("2026-11-01T00:00:00Z");
        assertEquals(Instant.parse("2027-01-08T10:00:00Z"), recorder.signupGrant(NOW).orElseThrow().expiresAt(), "an earlier date never shortens the grant");
    }

    @Test
    void anUnreadableFloorIsIgnored() {
        when(settings.getString(eq(TenantProvisioningRecorder.KEY_TRIAL_FREE_UNTIL), anyString())).thenReturn("not a date");
        assertEquals(Instant.parse("2027-01-08T10:00:00Z"), recorder.signupGrant(NOW).orElseThrow().expiresAt());
    }

    @Test
    void withoutASettingsServiceTheOriginalBehaviourStands() {
        TenantProvisioningRecorder plain = new TenantProvisioningRecorder(mock(TenantRepository.class), mock(AppUserRepository.class));
        TenantProvisioningRecorder.SignupGrant grant = plain.signupGrant(NOW).orElseThrow();
        assertEquals(TenantPlan.UNLIMITED, grant.plan());
        assertTrue(!grant.expiresAt().isBefore(TenantProvisioningRecorder.signupTrialExpiry(NOW)));
    }
}
