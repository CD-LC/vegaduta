package com.agenticai.core.voice;

import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.providers.modality.SpeechToTextException;
import com.agenticai.core.providers.modality.SpeechToTextProvider;
import com.agenticai.core.providers.modality.SpeechToTextProviderException;
import com.agenticai.core.providers.modality.SpeechToTextProviderFactory;
import com.agenticai.core.providers.modality.SpeechToTextTranscriptionException;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import com.agenticai.core.speech.call.CallSpeechTranscriber;
import com.agenticai.core.voice.VoiceCallController.TranscriptionOutcome;
import com.agenticai.core.voice.VoiceCallController.VoiceTurnAudioEmptyException;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * The three-tier speech-to-text fallback chain for a voice turn: the agent's preferred provider (if
 * any) -&gt; sidecar Whisper (if present) -&gt; Sarvam. Extracted from {@link VoiceCallController}
 * so the controller holds no transcription logic; behaviour, log lines and error wording are
 * unchanged. Stateless, so safe to share between concurrent requests.
 */
final class VoiceTurnTranscriber {

    private static final Logger log = LoggerFactory.getLogger(VoiceTurnTranscriber.class);

    /**
     * A header-only WebM/Opus container from a too-quick push-to-talk tap is ~100-150 bytes; this
     * sits well below any genuine short utterance.
     */
    static final long MIN_VIABLE_AUDIO_BYTES = 1000;

    private final Optional<SidecarClient> sidecarClient;
    private final CallSpeechTranscriber transcriber;
    private final SpeechToTextProviderFactory providerFactory;

    VoiceTurnTranscriber(
            Optional<SidecarClient> sidecarClient,
            CallSpeechTranscriber transcriber,
            SpeechToTextProviderFactory providerFactory) {
        this.sidecarClient = sidecarClient;
        this.transcriber = transcriber;
        this.providerFactory = providerFactory;
    }

    TranscriptionOutcome transcribe(
            MultipartFile audio,
            UUID agentId,
            VoiceModelProvider preferredProvider,
            String preferredModel)
            throws IOException {
        if (audio == null || audio.isEmpty()) {
            log.warn(
                    "Voice turn rejected for agent {}: audio is null or empty (audio=null:{},"
                            + " size:{})",
                    agentId,
                    audio == null,
                    audio != null ? audio.getSize() : "N/A");
            throw new VoiceTurnAudioEmptyException(
                    "No audio was uploaded - this usually means the microphone wasn't recording."
                        + " Check your browser's microphone permissions and ensure you're connected"
                        + " to an audio device. Try the 'Test Mic' button in the voice panel for"
                        + " diagnostics.");
        }
        log.info(
                "Voice turn received for agent {} audioSize={} bytes originalFilename={}",
                agentId,
                audio.getSize(),
                audio.getOriginalFilename());
        // A too-quick tap-and-release can produce a header-only WebM/Opus
        // container (~100-150 bytes, no audio frames) that isEmpty() above
        // doesn't catch - the client (CallPanel.tsx) now guards against this
        // itself, but a stale client build or a direct API caller can still
        // send one. Reject it here too, BEFORE burning a Whisper + Sarvam
        // call that would both fail on it anyway (observed in prod: a
        // 110-byte upload correctly failed both providers, wastefully).
        if (audio.getSize() < MIN_VIABLE_AUDIO_BYTES) {
            log.warn(
                    "Voice turn rejected for agent {}: audio too short to contain speech (size:{})",
                    agentId,
                    audio.getSize());
            throw new VoiceTurnAudioEmptyException(
                    "That recording was too short to contain any speech - hold the button a little"
                            + " longer and try again.");
        }

        // 1) Speech-to-text: Load agent's selected provider and implement fallback chain
        // Primary: agent's selected provider (if set + available)
        // Secondary: sidecar Whisper (always available if present)
        // Tertiary: Sarvam (if available)
        String userText = null;
        String transcriptionMethod = null;

        // Try agent's selected provider first (if configured)
        if (preferredProvider != null) {
            VoiceModelProvider selectedProvider = preferredProvider;
            String selectedModel = preferredModel;

            try {
                var providerOpt = providerFactory.getProvider(selectedProvider);
                if (providerOpt.isPresent()) {
                    SpeechToTextProvider provider = providerOpt.get();
                    if (provider.isAvailable()) {
                        try {
                            userText =
                                    provider.transcribe(
                                            audio.getBytes(),
                                            audio.getOriginalFilename(),
                                            selectedModel,
                                            null // auto-detect language
                                            );
                            transcriptionMethod = selectedProvider.getProviderId();
                            log.info(
                                    "Transcribed via {} for agent {} audioSize={} textLength={}",
                                    transcriptionMethod,
                                    agentId,
                                    audio.getSize(),
                                    userText != null ? userText.length() : 0);
                        } catch (SpeechToTextTranscriptionException e) {
                            // Audio issue, don't retry - re-throw
                            log.warn(
                                    "Audio transcription failed (client audio issue) for agent {}"
                                            + " using {}: {}",
                                    agentId,
                                    transcriptionMethod,
                                    e.getMessage());
                            throw new VoiceTurnAudioEmptyException(
                                    "Could not transcribe that recording. " + e.getMessage());
                        } catch (SpeechToTextProviderException e) {
                            // Provider infra issue - log and fall back to secondary
                            log.warn(
                                    "Provider {} temporarily unavailable for agent {}, will try"
                                            + " fallback: {} (retryable={})",
                                    transcriptionMethod,
                                    agentId,
                                    e.getMessage(),
                                    e.isRetryable());
                            userText = null; // mark for fallback
                        }
                    } else {
                        log.warn(
                                "Agent {}'s selected provider {} is not available: {}",
                                agentId,
                                selectedProvider.getProviderId(),
                                provider.getUnavailabilityReason());
                    }
                } else {
                    log.debug(
                            "Agent {}'s selected provider {} is not registered on this deployment",
                            agentId,
                            selectedProvider.getProviderId());
                }
            } catch (SpeechToTextException e) {
                // Neither of the two known subtypes above - unexpected, fall back
                log.warn(
                        "Unexpected error with agent {}'s selected provider: {}, will try fallback",
                        agentId,
                        e.getMessage());
            } catch (VoiceTurnAudioEmptyException e) {
                throw e; // re-throw audio transcription exceptions
            } catch (Exception e) {
                // The SpeechToTextException arm above at least names a known contract; this one
                // is the genuine catch-all, so it is the one case where the message alone is
                // guaranteed insufficient - an NPE or a Spring proxy failure here reports as
                // "null" and the fallback chain quietly hides it. Log the throwable.
                log.warn(
                        "Unexpected error with agent {}'s selected provider, will try fallback",
                        agentId,
                        e);
            }
        } else {
            log.debug("Agent {} has no selected voice provider, will use fallback chain", agentId);
        }

        // Fallback 1: Try sidecar Whisper if primary provider didn't work
        if (!StringUtils.hasText(userText) && sidecarClient.isPresent()) {
            try {
                log.debug(
                        "Attempting sidecar Whisper transcription for agent {} audioSize={}",
                        agentId,
                        audio.getSize());
                // transcribeAudioLive, NOT transcribeAudio: this runs synchronously
                // on this live voice-call request thread (see #turn's own javadoc -
                // STT completes before the SSE response begins), so it must not
                // carry SidecarClient's 30-minute background-job-runner read
                // timeout - see transcribeAudioLive's javadoc.
                userText =
                        sidecarClient
                                .get()
                                .transcribeAudioLive(audio.getOriginalFilename(), audio.getBytes());
                transcriptionMethod = "sidecar-whisper";
                log.info(
                        "Transcribed via sidecar Whisper for agent {} audioSize={} textLength={}",
                        agentId,
                        audio.getSize(),
                        userText != null ? userText.length() : 0);
            } catch (Exception e) {
                log.warn(
                        "Sidecar Whisper transcription failed for agent {}, will try Sarvam: {}",
                        agentId,
                        e.getMessage());
            }
        } else if (!StringUtils.hasText(userText)) {
            log.debug(
                    "Sidecar not available (knowledge profile inactive) for agent {}, will use"
                            + " Sarvam",
                    agentId);
        }

        // Fallback 2: Try Sarvam if sidecar transcription didn't work or isn't available
        if (!StringUtils.hasText(userText)) {
            try {
                log.debug(
                        "Attempting Sarvam transcription for agent {} audioSize={}",
                        agentId,
                        audio.getSize());
                if (transcriber == null) {
                    throw new CallSpeechTranscriber.NotConfiguredException(
                            "no speech-to-text fallback is registered", null);
                }
                userText = transcriber.transcribe(audio.getBytes(), audio.getOriginalFilename());
                transcriptionMethod = "sarvam";
                log.info(
                        "Transcribed via Sarvam for agent {} textLength={}",
                        agentId,
                        userText != null ? userText.length() : 0);
            } catch (CallSpeechTranscriber.NotConfiguredException e) {
                // Distinct from a genuine transcription failure (2026-08-08
                // audit fix #4): this means no STT provider was ever
                // reachable at all for this turn (agent has no selected
                // provider or an unavailable one, sidecar isn't running/not
                // present, AND no Sarvam credential exists either) - a
                // configuration gap, not an audio-quality problem. WARN, not
                // ERROR: this is the expected zero-config state, not an
                // infra failure.
                log.warn(
                        "No speech-to-text provider configured for agent {} (Sarvam credential"
                                + " missing, and no other provider succeeded): {}",
                        agentId,
                        e.getMessage());
                throw new VoiceTurnAudioEmptyException(
                        "No speech-to-text provider is configured for this agent. Add a Sarvam"
                                + " credential, or select another voice provider for this agent, to"
                                + " enable transcription.");
            } catch (Exception e) {
                log.error(
                        "Sarvam transcription also failed for agent {}: {}",
                        agentId,
                        e.getMessage(),
                        e);
                throw new VoiceTurnAudioEmptyException(
                        "Could not transcribe any speech from that recording. Please ensure your"
                                + " audio is clear and try again.");
            }
        }

        if (!StringUtils.hasText(userText)) {
            log.warn("Transcription produced empty text for agent {}", agentId);
            throw new VoiceTurnAudioEmptyException(
                    "No speech was detected in that recording. Please try speaking more clearly.");
        }

        return new TranscriptionOutcome(userText, transcriptionMethod);
    }
}
