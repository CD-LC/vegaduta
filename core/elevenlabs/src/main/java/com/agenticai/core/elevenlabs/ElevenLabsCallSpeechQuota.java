package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.speech.call.CallSpeechQuota;
import com.agenticai.core.speech.call.CallSpeechQuotaExceededException;
import java.time.Instant;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Component;

/**
 * The kernel's shared {@link CallSpeechQuota} over {@link VoiceQuotaService}'s ledger, so another
 * provider's BYOK synthesis (Sarvam on a live call) counts against the same daily/monthly budget
 * ElevenLabs enforces. The tenant's plan is resolved the way {@link ElevenLabsClient#textToSpeech}
 * resolves it: an unscoped caller (no tenant context on this thread) is treated as UNLIMITED - that
 * only happens for internal/test callers, never a real tenant request.
 */
@Component
public class ElevenLabsCallSpeechQuota implements CallSpeechQuota {

    private final VoiceQuotaService voiceQuotaService;
    private final TenantLimits tenantLimits;

    public ElevenLabsCallSpeechQuota(
            VoiceQuotaService voiceQuotaService, TenantLimits tenantLimits) {
        this.voiceQuotaService = voiceQuotaService;
        this.tenantLimits = tenantLimits;
    }

    @Override
    public void checkAllowed(String text, ToLongFunction<Instant> otherProviderUsageSince) {
        TenantPlan plan =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .map(tenantLimits::plan)
                        .orElse(TenantPlan.UNLIMITED);
        try {
            voiceQuotaService.checkAllowed(plan, text, otherProviderUsageSince);
        } catch (VoiceQuotaExceededException e) {
            throw new CallSpeechQuotaExceededException(e.getMessage(), e);
        }
    }
}
