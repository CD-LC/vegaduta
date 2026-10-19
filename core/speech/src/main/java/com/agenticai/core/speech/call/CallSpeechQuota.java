package com.agenticai.core.speech.call;

import java.time.Instant;
import java.util.function.ToLongFunction;

/**
 * The tenant's shared voice-generation budget (per-request length, daily and monthly caps by plan),
 * checked before a BYOK synthesis. Shared across providers so a tenant cannot bypass one provider's
 * cap by falling through to another. Kernel-owned (ADR-0011 rule a); implemented in the {@code
 * elevenlabs} module over its {@code VoiceQuotaService} ledger.
 */
public interface CallSpeechQuota {

    /**
     * @param otherProviderUsageSince the calling provider's own generated-audio count since an
     *     instant, folded into the shared ledger
     * @throws CallSpeechQuotaExceededException with a person-facing message when the request is
     *     over budget
     */
    void checkAllowed(String text, ToLongFunction<Instant> otherProviderUsageSince);
}
