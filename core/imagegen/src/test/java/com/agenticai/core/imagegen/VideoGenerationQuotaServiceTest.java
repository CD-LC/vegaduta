package com.agenticai.core.imagegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the PAID-ONLY entitlement rule for server-side video generation
 * (see {@link
 * VideoGenerationQuotaService}'s javadoc): a FREE tenant is rejected outright, before the
 * {@code enabled} flag or any cap is even consulted, so flipping video
 * generation on platform-wide can never silently gift FREE tenants
 * pool-billed Veo time. The anonymous {@code /api/demo/video} sandbox never
 * calls this service at all (it has its own global budget + rate limiter in
 * {@code DemoVideoController}/{@code DemoVideoService}), so nothing here
 * affects it.
 */
class VideoGenerationQuotaServiceTest {

    private VideoGenerationJobRepository jobRepository;
    private PlatformSettingsService platformSettings;
    private VideoGenerationQuotaService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(VideoGenerationJobRepository.class);
        platformSettings = mock(PlatformSettingsService.class);
        // Constructed with the feature ENABLED and generous paid defaults, so
        // any FREE rejection below is provably the plan gate, never the flag
        // or a cap.
        service = new VideoGenerationQuotaService(jobRepository, platformSettings, true, 60, 10, 100);
        when(platformSettings.getBoolean(anyString(), anyBoolean()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(platformSettings.getInt(anyString(), anyInt()))
            .thenAnswer(invocation -> invocation.getArgument(1));
    }

    // --- FREE: paid-only, no exceptions -------------------------------------

    @Test
    void freeIsRejectedEvenWithVideoGenerationEnabledAndAOneSecondClip() {
        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, 1))
            .isInstanceOf(VideoGenerationQuotaExceededException.class)
            .hasMessage(VideoGenerationQuotaService.FREE_PLAN_REJECTION_MESSAGE);
    }

    @Test
    void freeRejectionMessagePointsAtClipStudioAndTheUpgrade() {
        // The in-product upsell is the honest one, verbatim from the product
        // decision: the on-device Clip Studio is free, cinematic AI video is
        // paid. Copy approved by the coordinator 2026-08-05.
        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, 5))
            .hasMessageContaining("paid feature")
            .hasMessageContaining("upgrade to render")
            .hasMessageContaining("on-device Clip Studio");
    }

    @Test
    void freeIsRejectedBeforeTheEnabledFlagOrAnySettingOrTheLedgerIsConsulted() {
        // The rejection must not depend on ANY runtime state - an admin
        // enabling video generation, or editing any setting, can never widen
        // what FREE can do.
        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, 5))
            .isInstanceOf(VideoGenerationQuotaExceededException.class);

        verifyNoInteractions(platformSettings);
        verifyNoInteractions(jobRepository);
    }

    @Test
    void theRetiredFreeAllowanceSettingsAreNeverReadForAnyPlan() {
        // The dormant free-daily-cap/free-monthly-cap/free-max-duration
        // allowances were removed outright (not zeroed) - a leftover
        // admin-written row for one of those keys must never be consulted
        // again, for any plan.
        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, 5))
            .isInstanceOf(VideoGenerationQuotaExceededException.class);
        assertThatCode(() -> service.checkAllowed(TenantPlan.PREMIUM, 5)).doesNotThrowAnyException();

        verify(platformSettings, never()).getInt(eq("video.generation.free-daily-cap"), anyInt());
        verify(platformSettings, never()).getInt(eq("video.generation.free-monthly-cap"), anyInt());
        verify(platformSettings, never()).getInt(eq("video.generation.free-max-duration-seconds"), anyInt());
    }

    @Test
    void freeHasAZeroSecondDurationCeiling() {
        assertThat(service.maxDurationSecondsFor(TenantPlan.FREE)).isZero();
    }

    // --- PAID plans: flag + caps still enforced ------------------------------

    @Test
    void premiumUnderEveryCapIsAllowed() {
        when(jobRepository.countByCreatedAtAfterAndStatusNot(any(Instant.class), eq(VideoGenerationJobStatus.FAILED)))
            .thenReturn(0L);

        assertThatCode(() -> service.checkAllowed(TenantPlan.PREMIUM, 60)).doesNotThrowAnyException();
    }

    @Test
    void unlimitedIsTreatedAsAPaidPlan() {
        when(jobRepository.countByCreatedAtAfterAndStatusNot(any(Instant.class), eq(VideoGenerationJobStatus.FAILED)))
            .thenReturn(0L);

        assertThatCode(() -> service.checkAllowed(TenantPlan.UNLIMITED, 60)).doesNotThrowAnyException();
    }

    @Test
    void evenPaidPlansAreRejectedWhileTheFeatureFlagIsOff() {
        when(platformSettings.getBoolean(eq("video.generation.enabled"), anyBoolean())).thenReturn(false);

        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.PREMIUM, 5))
            .isInstanceOf(VideoGenerationQuotaExceededException.class)
            .hasMessageContaining("turned off");
    }

    @Test
    void paidRequestOverTheDurationCeilingIsRejected() {
        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.PREMIUM, 61))
            .isInstanceOf(VideoGenerationQuotaExceededException.class)
            .hasMessageContaining("up to 60s");
        verifyNoInteractions(jobRepository);
    }

    @Test
    void paidDailyCapIsEnforced() {
        when(jobRepository.countByCreatedAtAfterAndStatusNot(any(Instant.class), eq(VideoGenerationJobStatus.FAILED)))
            .thenReturn(10L);

        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.PREMIUM, 5))
            .isInstanceOf(VideoGenerationQuotaExceededException.class)
            .hasMessageContaining("today's video generation limit (10)");
    }

    @Test
    void paidMonthlyCapIsEnforced() {
        // Under the daily cap (first ledger read), at the monthly cap
        // (second read).
        when(jobRepository.countByCreatedAtAfterAndStatusNot(any(Instant.class), eq(VideoGenerationJobStatus.FAILED)))
            .thenReturn(5L, 100L);

        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.PREMIUM, 5))
            .isInstanceOf(VideoGenerationQuotaExceededException.class)
            .hasMessageContaining("month's video generation limit (100)");
    }
}
