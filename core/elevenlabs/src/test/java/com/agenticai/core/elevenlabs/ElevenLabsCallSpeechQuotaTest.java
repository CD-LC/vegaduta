package com.agenticai.core.elevenlabs;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.speech.call.CallSpeechQuotaExceededException;
import java.time.Instant;
import java.util.Set;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ElevenLabsCallSpeechQuota}'s adapter job: resolve the calling tenant's plan the
 * same way the TTS client does (unscoped thread = UNLIMITED internal caller), delegate the cap
 * math to {@link VoiceQuotaService} and translate its typed rejection into the kernel's {@link
 * CallSpeechQuotaExceededException} so cross-provider callers catch one shape.
 */
class ElevenLabsCallSpeechQuotaTest {

    private VoiceQuotaService voiceQuotaService;
    private TenantLimits tenantLimits;
    private ElevenLabsCallSpeechQuota quota;

    private static final ToLongFunction<Instant> NO_OTHER_USAGE = since -> 0L;

    @BeforeEach
    void setUp() {
        voiceQuotaService = mock(VoiceQuotaService.class);
        tenantLimits = mock(TenantLimits.class);
        quota = new ElevenLabsCallSpeechQuota(voiceQuotaService, tenantLimits);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void anUnscopedCallerIsTreatedAsUnlimited() {
        TenantContext.clear();

        quota.checkAllowed("hello", NO_OTHER_USAGE);

        verify(voiceQuotaService).checkAllowed(eq(TenantPlan.UNLIMITED), eq("hello"), any());
    }

    @Test
    void theCallingTenantsPlanIsResolvedThroughTenantLimits() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.FREE);

        quota.checkAllowed("hello", NO_OTHER_USAGE);

        verify(voiceQuotaService).checkAllowed(eq(TenantPlan.FREE), eq("hello"), any());
    }

    @Test
    void theCrossProviderUsageFunctionIsPassedThrough() {
        TenantContext.clear();
        ToLongFunction<Instant> sarvamUsage = since -> 7L;

        quota.checkAllowed("hello", sarvamUsage);

        verify(voiceQuotaService).checkAllowed(any(), eq("hello"), eq(sarvamUsage));
    }

    @Test
    void aVoiceQuotaRejectionSurfacesAsTheSharedKernelException() {
        TenantContext.clear();
        doThrow(new VoiceQuotaExceededException("You've reached today's voice generation limit."))
                .when(voiceQuotaService)
                .checkAllowed(any(), eq("hello"), any());

        assertThatThrownBy(() -> quota.checkAllowed("hello", NO_OTHER_USAGE))
                .isInstanceOf(CallSpeechQuotaExceededException.class)
                .hasMessageContaining("today's voice generation limit")
                .hasCauseInstanceOf(VoiceQuotaExceededException.class);
    }

    @Test
    void anAllowedCallPropagatesNothing() {
        TenantContext.clear();

        assertThatCode(() -> quota.checkAllowed("hello", NO_OTHER_USAGE))
                .doesNotThrowAnyException();
    }
}
