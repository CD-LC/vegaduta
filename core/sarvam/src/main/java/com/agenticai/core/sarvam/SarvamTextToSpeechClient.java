package com.agenticai.core.sarvam;

import com.agenticai.core.speech.SpeechTextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Wraps {@code POST /text-to-speech}. Sarvam's response is a base64-encoded WAV string inside an
 * {@code audios} array (one entry per input text - this client only ever sends one); {@link
 * #synthesize} decodes it so callers never handle base64 directly, per the build plan's explicit
 * instruction.
 *
 * <p>Default model {@link #DEFAULT_MODEL} ({@code bulbul:v3}: 30+ voices, temperature control, no
 * pitch/loudness support), with {@code bulbul:v2} available as a fallback {@code model} parameter
 * for callers that need pitch/loudness control instead (not exposed as separate method parameters
 * here - {@code bulbul:v2}-only knobs are a Stage 3-deferred refinement the build plan doesn't ask
 * for yet; this client covers the common path both models share: text, target language, speaker,
 * model).
 *
 * <p>Same credential-resolution/error-translation/call-logging contract as {@link
 * SarvamTranslateClient} - see that class's javadoc for the {@link SarvamNotConfiguredException} vs
 * {@link SarvamApiException} note.
 */
@Component
public class SarvamTextToSpeechClient {

    private static final String URL = "https://api.sarvam.ai/text-to-speech";
    private static final String API_SUBSCRIPTION_KEY_HEADER = "api-subscription-key";

    /** {@code bulbul:v3} - 30+ voices, temperature control, no pitch/loudness support. */
    public static final String DEFAULT_MODEL = "bulbul:v3";

    private final RestClient restClient;
    private final SarvamCredentialService credentialService;
    private final SarvamCallLogService callLogService;

    public SarvamTextToSpeechClient(
            RestClient.Builder restClientBuilder,
            SarvamCredentialService credentialService,
            SarvamCallLogService callLogService) {
        this.restClient = restClientBuilder.build();
        this.credentialService = credentialService;
        this.callLogService = callLogService;
    }

    public SynthesisResult synthesize(String text, String targetLanguageCode) {
        return synthesize(text, targetLanguageCode, null, null);
    }

    /**
     * @param model {@code bulbul:v3} or {@code bulbul:v2} - null/blank defaults to {@link
     *     #DEFAULT_MODEL}.
     * @param speaker voice name (must match the chosen model - e.g. {@code shubh} for bulbul:v3,
     *     {@code anushka} for bulbul:v2). Null omits the field (Sarvam applies its own per-model
     *     default).
     * @return the decoded WAV bytes - never base64 text.
     * @implNote {@code text} is run through {@link SpeechTextNormalizer#normalizeForSpeech} before
     *     it is sent. Callers hand over LLM output, which routinely carries markdown, HTML, emoji
     *     and bare URLs; bulbul reads all of it out literally (the 2026-07-29 "voice reads all the
     *     special characters" bug). Normalizing here rather than at each call site means the {@code
     *     sarvam_speak} agent tool and any future caller get it for free. The normalizer is
     *     explicitly Unicode/Indic-safe - it strips only code points at U+2190 and above, so no
     *     Devanagari/Tamil/Telugu/ Kannada/Malayalam/Bengali/Gujarati/Gurmukhi/Odia character (all
     *     below U+0E00) can be touched, and it treats the danda (U+0964) as sentence-ending
     *     punctuation. See that class's javadoc.
     */
    public SynthesisResult synthesize(
            String text, String targetLanguageCode, String model, String speaker) {
        return synthesize(text, targetLanguageCode, model, speaker, null);
    }

    /**
     * Platform-subsidised variant: when {@code apiKeyOverride} is non-blank it is used (VegaDūta's
     * own operator Sarvam key) instead of the tenant's BYOK credential - lets {@code
     * PlatformSarvamSpeakBackend} speak for a tenant that has no Sarvam key of its own. Otherwise
     * identical to the 4-arg form.
     */
    public SynthesisResult synthesize(
            String text,
            String targetLanguageCode,
            String model,
            String speaker,
            String apiKeyOverride) {
        return synthesize(text, targetLanguageCode, model, speaker, apiKeyOverride, null);
    }

    /**
     * Same as the five-argument form plus an explicit {@code output_audio_codec} (Sarvam accepts
     * {@code mp3, linear16, mulaw, alaw, opus, flac, aac, wav}; default {@code wav} when omitted).
     * Exists for the WhatsApp voice-note reply, because WhatsApp rejects WAV: it accepts only AAC,
     * AMR, MP3, MP4 audio and OGG/Opus
     * (https://developers.facebook.com/documentation/business-messaging/whatsapp/messages/audio-messages.md).
     *
     * <p>The returned bytes are whatever Sarvam sent back, decoded from base64 - Sarvam's docs call
     * the {@code audios} field "WAV format" without saying whether a non-default codec changes the
     * container, so a caller that needs a specific format must sniff the bytes (see the omnichannel
     * {@code AudioFormatSniffer}) instead of trusting the codec it asked for.
     *
     * @param outputAudioCodec null/blank omits the field (Sarvam's WAV default).
     */
    public SynthesisResult synthesize(
            String text,
            String targetLanguageCode,
            String model,
            String speaker,
            String apiKeyOverride,
            String outputAudioCodec) {
        String resolvedModel = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        String spokenText = SpeechTextNormalizer.normalizeForSpeech(text);
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        boolean success = false;
        String errorMessage = null;
        try {
            String apiKey =
                    (apiKeyOverride != null && !apiKeyOverride.isBlank())
                            ? apiKeyOverride
                            : credentialService.resolveActiveApiKey();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", spokenText);
            body.put("target_language_code", targetLanguageCode);
            body.put("model", resolvedModel);
            if (speaker != null && !speaker.isBlank()) {
                body.put("speaker", speaker);
            }
            if (outputAudioCodec != null && !outputAudioCodec.isBlank()) {
                body.put("output_audio_codec", outputAudioCodec);
            }
            JsonNode response =
                    restClient
                            .post()
                            .uri(URL)
                            .header(API_SUBSCRIPTION_KEY_HEADER, apiKey)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
            JsonNode audios = response.path("audios");
            String base64Audio =
                    (audios.isArray() && !audios.isEmpty()) ? audios.get(0).asText(null) : null;
            if (base64Audio == null || base64Audio.isBlank()) {
                throw new SarvamApiException(
                        0, "Sarvam text-to-speech returned no audio in the response.");
            }
            byte[] wavBytes = Base64.getDecoder().decode(base64Audio);
            success = true;
            return new SynthesisResult(wavBytes, response.path("request_id").asText(null));
        } catch (RestClientResponseException e) {
            errorMessage = SarvamTranslateClient.describeError(e);
            throw new SarvamApiException(
                    e.getStatusCode().value(),
                    "Sarvam text-to-speech call failed: " + errorMessage);
        } catch (RestClientException e) {
            errorMessage = e.getMessage();
            throw new SarvamApiException(
                    0, "Could not reach Sarvam's text-to-speech endpoint: " + e.getMessage());
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            callLogService.record(
                    SarvamCallCapability.TEXT_TO_SPEECH,
                    resolvedModel,
                    success,
                    errorMessage,
                    latencyMs,
                    startedAt);
        }
    }

    public record SynthesisResult(byte[] wavBytes, String requestId) {}
}
