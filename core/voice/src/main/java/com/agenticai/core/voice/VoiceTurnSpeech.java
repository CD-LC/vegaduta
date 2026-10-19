package com.agenticai.core.voice;

import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.agenticai.core.speech.WavConcatenator;
import com.agenticai.core.speech.call.CallSpeechQuotaExceededException;
import com.agenticai.core.speech.call.CallSpeechSynthesizer;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The text-to-speech half of {@code VoiceCallController}'s streaming push-to-talk turn:
 * per-sentence synthesis through the ElevenLabs, Sarvam, text-only fallback chain, and the single
 * audio-ledger row written for the whole turn. Split out of {@code VoiceCallController} unchanged,
 * to keep that file under the file-size ratchet.
 */
final class VoiceTurnSpeech {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallController.class);

    private final CallSpeechSynthesizer elevenLabsTts;
    private final CallSpeechSynthesizer sarvamTts;

    VoiceTurnSpeech(CallSpeechSynthesizer elevenLabsTts, CallSpeechSynthesizer sarvamTts) {
        this.elevenLabsTts = elevenLabsTts;
        this.sarvamTts = sarvamTts;
    }

    /**
     * The synthesis half of {@code VoiceCallController#trySarvamTts}, without the store - {@code
     * VoiceCallController#turn}'s streaming pipeline synthesizes one call per sentence but persists
     * only once for the whole turn (see {@link #persistTurnAudio}), so it calls this directly
     * rather than {@code VoiceCallController#trySarvamTts}, which would store once per sentence and
     * inflate the audit table.
     *
     * <p>Quota-gated (2026-08-08 audit fix #2): unlike every other failure here, a {@code
     * VoiceQuotaExceededException} from {@code CallSpeechQuota} is deliberately let through
     * uncaught rather than swallowed into a {@code null} return - it's a business decision, not a
     * provider failure, and callers ({@code VoiceCallController#trySarvamTts}, {@link
     * #synthesizeChunk}) need to see it distinctly rather than silently treat this as "Sarvam
     * unavailable, fall through to text-only."
     */
    byte[] synthesizeSarvamOnly(String speechText) {
        if (sarvamTts == null) {
            return null;
        }
        return sarvamTts.synthesize(speechText, sarvamTts.defaultVoice());
    }

    /** One sentence-sized piece's TTS result - see {@link #synthesizeChunk}. */
    record ChunkResult(byte[] audioBytes, String method) {}

    /**
     * Per-sentence synthesis for {@code VoiceCallController#turn}'s streaming pipeline - the same
     * ElevenLabs -&gt; Sarvam -&gt; text-only-fallback chain the non-streaming version of this
     * endpoint used to run once for the whole reply (primary: tenant's cloned voice via ElevenLabs,
     * or the public "Rachel" voice if none cloned yet; secondary: the tenant's own Sarvam
     * credential; tertiary: no audio, frontend uses browser TTS), just scoped to one
     * already-speech-normalized fragment and run off the calling thread since {@code
     * ElevenLabsClient} and {@code SarvamTextToSpeechClient} are both blocking HTTP clients. Never
     * persists - {@code VoiceCallController#turn} stores exactly one row for the whole turn after
     * the stream completes; see {@link #persistTurnAudio}.
     *
     * <p>A {@code VoiceQuotaExceededException} from either tier (2026-08-08 audit fix #1) is
     * deliberately NOT caught here - it propagates out of the {@code fromCallable} lambda as a
     * {@code Mono} error instead of being swallowed into a silent {@code "text-only-fallback"}
     * result. {@code VoiceCallController#streamVoiceTurn}'s {@code onErrorResume} surfaces it as a
     * distinct {@code error} SSE event (see {@link VoiceTurnEvents#quotaExceededMessage}) rather
     * than the generic mid-stream-failure one. Falling through to Sarvam after an ElevenLabs quota
     * rejection would be pointless anyway - Sarvam is gated by the same shared ledger (see {@code
     * CallSpeechQuota}) and would just throw the same exception.
     */
    Mono<ChunkResult> synthesizeChunk(UUID agentId, String text, TenantScope tenant) {
        return Mono.fromCallable(
                        tenant.wrapCallable(
                                () -> {
                                    if (elevenLabsTts != null) {
                                        try {
                                            String voiceId = elevenLabsTts.defaultVoice();
                                            byte[] audio = elevenLabsTts.synthesize(text, voiceId);
                                            if (audio != null && audio.length > 0) {
                                                return new ChunkResult(audio, "elevenlabs");
                                            }
                                        } catch (CallSpeechQuotaExceededException e) {
                                            log.info(
                                                    "ElevenLabs TTS quota exceeded for agent {}"
                                                            + " chunk: {}",
                                                    agentId,
                                                    e.getMessage());
                                            throw e;
                                        } catch (Exception e) {
                                            log.warn(
                                                    "ElevenLabs TTS generation failed for agent {}"
                                                            + " chunk (credentials may not be"
                                                            + " configured): {}",
                                                    agentId,
                                                    e.getMessage());
                                        }
                                    }
                                    byte[] sarvamAudio = synthesizeSarvamOnly(text);
                                    if (sarvamAudio != null && sarvamAudio.length > 0) {
                                        return new ChunkResult(sarvamAudio, "sarvam");
                                    }
                                    return new ChunkResult(null, "text-only-fallback");
                                }))
                .subscribeOn(TenantAwareSchedulers.boundedElastic());
    }

    /**
     * Persists exactly one row for the whole turn - not one per chunk, which would inflate {@code
     * VoiceQuotaService}'s row-counted ledger (see that class's javadoc: it counts {@code
     * ElevenLabsGeneratedAudioRepository} rows for the ElevenLabs path specifically). Concatenates
     * every chunk that used whichever provider the FIRST successful chunk used - proper
     * concatenation, not picking one chunk, via {@link WavConcatenator} for Sarvam's WAV clips
     * (each carries its own RIFF header, so raw byte concatenation would produce an invalid
     * multi-header file) and plain byte concatenation for ElevenLabs' MP3 (frame-based,
     * concatenation-safe in practice). In the rare case a provider flips mid-turn, chunks that used
     * the OTHER provider are simply left out of this one audit/ledger row - nothing user-facing
     * depends on it, since playback already happened via the individually streamed {@code
     * audio-chunk} events. No-ops if every chunk fell back to text-only.
     */
    void persistTurnAudio(UUID agentId, List<ChunkResult> chunks) {
        String method =
                chunks.stream()
                        .map(ChunkResult::method)
                        .filter(m -> !"text-only-fallback".equals(m))
                        .findFirst()
                        .orElse(null);
        if (method == null) {
            return;
        }
        List<byte[]> matching =
                chunks.stream()
                        .filter(
                                c ->
                                        method.equals(c.method())
                                                && c.audioBytes() != null
                                                && c.audioBytes().length > 0)
                        .map(ChunkResult::audioBytes)
                        .toList();
        if (matching.isEmpty()) {
            return;
        }
        try {
            CallSpeechSynthesizer provider =
                    "elevenlabs".equals(method)
                            ? elevenLabsTts
                            : "sarvam".equals(method) ? sarvamTts : null;
            if (provider != null) {
                provider.store(provider.concatenate(matching), provider.defaultVoice());
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to persist streamed TTS audio for agent {}: {} (turn already completed"
                            + " successfully)",
                    agentId,
                    e.getMessage());
        }
    }
}
