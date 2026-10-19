package com.agenticai.core.elevenlabs;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the 2026-07-29 cost-control fix: the {@code
 * elevenlabs.voice.quota-enabled} master switch must FAIL CLOSED (feature
 * unavailable) when turned off, not grant unlimited spend. Before this fix
 * {@link VoiceQuotaService#checkAllowed} returned (allowed) whenever {@code
 * !isEnabled()}, which removed the per-request character cap AND the daily/
 * monthly caps on the one gate in front of a paid, per-character external API
 * ({@link ElevenLabsClient#textToSpeech}). {@code ImageGenerationQuotaService}
 * and {@code VideoGenerationQuotaService} in {@code core.imagegen} both throw
 * when their own master switch is off - this test locks voice into the same
 * shape.
 */
class VoiceQuotaServiceTest {

    private ElevenLabsGeneratedAudioRepository audioRepository;
    private PlatformSettingsService platformSettings;
    private VoiceQuotaService service;

    @BeforeEach
    void setUp() {
        audioRepository = mock(ElevenLabsGeneratedAudioRepository.class);
        platformSettings = mock(PlatformSettingsService.class);
        // Every getter falls through to whatever default the call site supplied,
        // so the effective config is exactly the constructor's in-code defaults
        // unless a test overrides a specific key.
        when(platformSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(platformSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(audioRepository.countByCreatedAtAfter(any())).thenReturn(0L);

        service = new VoiceQuotaService(
            audioRepository,
            platformSettings,
            true,   // defaultQuotaEnabled
            300,    // free-max-characters-per-request
            5000,   // premium-max-characters-per-request
            5,      // free-daily-cap
            50,     // free-monthly-cap
            200,    // premium-daily-cap
            3000    // premium-monthly-cap
        );
    }

    @Test
    void allowsFreePlanUnderEveryCapWhenEnabled() {
        assertThatCode(() -> service.checkAllowed(TenantPlan.FREE, "hello")).doesNotThrowAnyException();
    }

    @Test
    void disabledMasterSwitchBlocksEvenAnUnlimitedTenant() {
        // The master switch is a platform-wide kill switch, not a plan gate -
        // it must block regardless of plan, including UNLIMITED, exactly like
        // ImageGenerationQuotaService/VideoGenerationQuotaService's own
        // isEnabled() check runs before their UNLIMITED short-circuit.
        when(platformSettings.getBoolean("elevenlabs.voice.quota-enabled", true)).thenReturn(false);

        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.UNLIMITED, "hello"))
            .isInstanceOf(VoiceQuotaExceededException.class);
        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, "hello"))
            .isInstanceOf(VoiceQuotaExceededException.class);
    }

    @Test
    void disabledMasterSwitchDoesNotGrantUnlimitedSpend() {
        // Regression guard for the exact bug: previously `!isEnabled()` was
        // OR'd into the early-return alongside UNLIMITED, so turning the
        // feature off silently removed the per-request character cap too.
        when(platformSettings.getBoolean("elevenlabs.voice.quota-enabled", true)).thenReturn(false);

        String farOverAnyCap = "x".repeat(50_000);
        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, farOverAnyCap))
            .isInstanceOf(VoiceQuotaExceededException.class);
    }

    @Test
    void unlimitedPlanSkipsCapsWhenEnabled() {
        when(audioRepository.countByCreatedAtAfter(any())).thenReturn(1_000_000L);

        assertThatCode(() -> service.checkAllowed(TenantPlan.UNLIMITED, "x".repeat(50_000)))
            .doesNotThrowAnyException();
    }

    @Test
    void freePlanBlockedOverPerRequestCharacterCap() {
        String tooLong = "x".repeat(301);

        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, tooLong))
            .isInstanceOf(VoiceQuotaExceededException.class);
    }

    @Test
    void freePlanBlockedAtDailyCap() {
        when(audioRepository.countByCreatedAtAfter(any(Instant.class))).thenReturn(5L);

        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, "hi"))
            .isInstanceOf(VoiceQuotaExceededException.class);
    }

    // ====== Cross-provider overload (2026-08-08 audit fix #2: Sarvam TTS
    // had zero quota enforcement of its own, letting a FREE tenant bypass
    // this exact daily/monthly cap just by switching TTS provider) ======

    @Test
    void crossProviderOverloadFoldsOtherProviderUsageIntoDailyCap() {
        // Zero ElevenLabs usage, but 5 Sarvam rows already used today -
        // still must block a FREE tenant at the same daily cap (5).
        when(audioRepository.countByCreatedAtAfter(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, "hi", since -> 5L))
            .isInstanceOf(VoiceQuotaExceededException.class);
    }

    @Test
    void crossProviderOverloadFoldsOtherProviderUsageIntoMonthlyCap() {
        when(audioRepository.countByCreatedAtAfter(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.checkAllowed(TenantPlan.FREE, "hi", since -> 50L))
            .isInstanceOf(VoiceQuotaExceededException.class);
    }

    @Test
    void crossProviderOverloadAllowsWhenCombinedUsageUnderCap() {
        when(audioRepository.countByCreatedAtAfter(any())).thenReturn(0L);

        assertThatCode(() -> service.checkAllowed(TenantPlan.FREE, "hi", since -> 4L))
            .doesNotThrowAnyException();
    }

    @Test
    void crossProviderOverloadStillSkipsCapsForUnlimitedPlan() {
        when(audioRepository.countByCreatedAtAfter(any())).thenReturn(0L);

        assertThatCode(() -> service.checkAllowed(TenantPlan.UNLIMITED, "x".repeat(50_000), since -> 1_000_000L))
            .doesNotThrowAnyException();
    }

    @Test
    void twoArgOverloadIsUnaffectedByTheCrossProviderChange() {
        // Regression guard: the pre-existing 2-arg method (used by
        // ElevenLabsClient) must behave byte-identically to before this
        // overload was added - it folds in zero "other provider" usage.
        when(audioRepository.countByCreatedAtAfter(any())).thenReturn(0L);

        assertThatCode(() -> service.checkAllowed(TenantPlan.FREE, "hello")).doesNotThrowAnyException();
    }
}
