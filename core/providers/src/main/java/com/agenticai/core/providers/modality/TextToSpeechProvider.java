package com.agenticai.core.providers.modality;

import org.jspecify.annotations.Nullable;

/**
 * Abstraction for text-to-speech (TTS) providers - the output-side twin of
 * {@link SpeechToTextProvider}. Each implementation synthesizes speech audio
 * from text using a specific backend (ElevenLabs, Sarvam).
 *
 * <p>Implementations are registered with {@link TextToSpeechProviderFactory}
 * and selected by {@code AgentService}/{@code VoiceCallController} based on
 * the agent's {@code ttsProvider} field, mirroring exactly how {@link
 * SpeechToTextProvider} implementations are selected via {@code
 * voiceModelProvider}.
 *
 * <p><b>Quota caveat (read before adding a new caller):</b> {@code
 * VoiceCallController}'s default (unset-{@code ttsProvider}) fallback chain
 * applies tenant-plan TTS quota checks itself ({@code VoiceQuotaService}) -
 * {@code elevenlabs.ElevenLabsTextToSpeechProvider} inherits that check because it calls
 * the same {@code ElevenLabsClient#textToSpeech} the chain already used,
 * which quota-checks internally. {@code sarvam.SarvamTextToSpeechProvider} does
 * NOT: the existing Sarvam quota check ({@code
 * VoiceCallController#checkSarvamQuota}) is a private method on that
 * controller, not on {@code SarvamTextToSpeechClient} itself. Any NEW caller
 * of this interface for Sarvam (outside {@code VoiceCallController}'s own
 * explicit-provider branch, which reuses its existing quota-checked private
 * helpers rather than calling this interface directly) must apply its own
 * quota gate first.
 *
 * <p>All implementations MUST be thread-safe and handle provider-specific
 * errors by throwing the appropriate exception type (see subclass javadocs).
 */
public interface TextToSpeechProvider {

    /**
     * Synthesizes speech audio for the given text using this provider's
     * default voice/model.
     *
     * @param text the text to speak (already speech-normalized by the caller
     *             - see {@code SpeechTextNormalizer} - implementations should
     *             NOT re-normalize)
     * @return the synthesized audio bytes (format is provider-specific - MP3
     *         for ElevenLabs, WAV for Sarvam; callers that persist or stream
     *         this must account for that difference, same as the existing
     *         fallback chain already does)
     * @throws TextToSpeechSynthesisException if the text itself can't be
     *         synthesized (empty, unsupported)
     * @throws TextToSpeechProviderException if a provider-level error occurs
     *         (API unreachable, credentials invalid, rate limited, quota
     *         exceeded) - the caller should fall back or fail the turn
     */
    byte[] synthesize(String text) throws TextToSpeechException;

    /**
     * Synthesizes speech with an optional provider-specific voice/model
     * override. Default implementation ignores the override and delegates to
     * {@link #synthesize(String)}.
     *
     * @param text          the text to speak
     * @param model         provider-specific model name (e.g. {@code
     *                      "eleven_multilingual_v2"} for ElevenLabs, {@code
     *                      "bulbul:v3"} for Sarvam), or null for the
     *                      provider's default
     * @param voiceOrSpeaker provider-specific voice id (ElevenLabs) or
     *                      speaker name (Sarvam), or null for the provider's
     *                      default resolution (e.g. ElevenLabs resolves the
     *                      tenant's most recently cloned voice)
     * @throws TextToSpeechException on any synthesis or provider error
     */
    default byte[] synthesize(String text, @Nullable String model, @Nullable String voiceOrSpeaker) throws TextToSpeechException {
        return synthesize(text);
    }

    /**
     * Checks whether this provider is currently available on this
     * deployment/tenant. Used by validation logic ({@code AgentService}) to
     * reject agent creation/updates for providers that aren't configured -
     * mirrors {@link SpeechToTextProvider#isAvailable()}.
     */
    boolean isAvailable();

    /**
     * A human-readable reason why this provider is unavailable, if {@link
     * #isAvailable()} returns false. Used in validation error messages.
     */
    @Nullable String getUnavailabilityReason();

    /**
     * Returns the {@link TtsModelProvider} enum this implementation handles.
     */
    TtsModelProvider getProvider();
}
