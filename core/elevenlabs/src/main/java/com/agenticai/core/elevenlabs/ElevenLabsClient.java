package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.speech.SpeechTextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin wrapper around ElevenLabs' REST API - Master-Roadmap-2026.md item #4 (voice cloning). Three
 * operations, mirroring the shape of {@code SarvamTextToSpeechClient}/{@code
 * OpenAiImageGenerationClient}: resolve the tenant's active key via {@link
 * ElevenLabsCredentialService}, make the call, translate any failure into {@link
 * ElevenLabsApiException} (never let a raw {@code RestClientException} escape this class).
 *
 * <p>Auth: {@code xi-api-key} header on every call - ElevenLabs' documented scheme (not bearer
 * auth, not basic auth).
 *
 * <p>{@link #textToSpeech} is plan-gated via {@link VoiceQuotaService} (2026-07-27 billing audit:
 * this module had zero plan-awareness - every tenant, FREE included, got unlimited voice
 * generation). Voice cloning itself ({@link #cloneVoice}/{@link #deleteVoice}) is left ungated
 * here, same posture as before this change - the audit and the quota service both scope strictly to
 * generation volume, not the cloning setup step.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class ElevenLabsClient {

    private static final String API_KEY_HEADER = "xi-api-key";
    private static final String VOICES_ADD_URL = "https://api.elevenlabs.io/v1/voices/add";
    private static final String VOICE_DELETE_URL = "https://api.elevenlabs.io/v1/voices/{voiceId}";
    private static final String TEXT_TO_SPEECH_URL =
            "https://api.elevenlabs.io/v1/text-to-speech/{voiceId}";
    private static final String MUSIC_COMPOSE_URL =
            "https://api.elevenlabs.io/v1/music?output_format=mp3_44100_128";

    /** ElevenLabs' documented bounds for {@code music_length_ms}. */
    public static final int MUSIC_MIN_LENGTH_MS = 3_000;

    public static final int MUSIC_MAX_LENGTH_MS = 600_000;

    /**
     * {@code eleven_multilingual_v2} - broadest language coverage, the sensible default for a
     * platform whose Sarvam integration already targets 22 Indian languages plus English.
     */
    public static final String DEFAULT_MODEL = "eleven_multilingual_v2";

    private final RestClient restClient;
    private final ElevenLabsCredentialService credentialService;
    private final VoiceQuotaService voiceQuotaService;
    private final TenantLimits tenantLimits;

    public ElevenLabsClient(
            RestClient.Builder restClientBuilder,
            ElevenLabsCredentialService credentialService,
            VoiceQuotaService voiceQuotaService,
            TenantLimits tenantLimits) {
        this.restClient = restClientBuilder.build();
        this.credentialService = credentialService;
        this.voiceQuotaService = voiceQuotaService;
        this.tenantLimits = tenantLimits;
    }

    /**
     * Uploads a sample clip to {@code POST /v1/voices/add} (instant voice cloning) and returns the
     * {@code voice_id} ElevenLabs assigns.
     *
     * @param sampleBytes the recorded sample audio (any format ElevenLabs accepts - mp3/wav/m4a
     *     etc.; this client does not transcode)
     */
    public String cloneVoice(String name, byte[] sampleBytes, String contentType, String filename) {
        try {
            String apiKey = credentialService.resolveActiveApiKey();
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("name", name);
            ByteArrayResource sampleResource =
                    new ByteArrayResource(sampleBytes) {
                        @Override
                        public String getFilename() {
                            return (filename == null || filename.isBlank())
                                    ? "sample.mp3"
                                    : filename;
                        }
                    };
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(
                    contentType != null && !contentType.isBlank()
                            ? MediaType.parseMediaType(contentType)
                            : MediaType.APPLICATION_OCTET_STREAM);
            org.springframework.http.HttpEntity<ByteArrayResource> filePart =
                    new org.springframework.http.HttpEntity<>(sampleResource, fileHeaders);
            body.add("files", filePart);

            JsonNode response =
                    restClient
                            .post()
                            .uri(VOICES_ADD_URL)
                            .header(API_KEY_HEADER, apiKey)
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
            String voiceId = response == null ? null : response.path("voice_id").asText(null);
            if (voiceId == null || voiceId.isBlank()) {
                throw new ElevenLabsApiException(
                        0, "ElevenLabs voice clone returned no voice_id in the response.");
            }
            return voiceId;
        } catch (RestClientResponseException e) {
            throw new ElevenLabsApiException(
                    e.getStatusCode().value(),
                    "ElevenLabs voice clone failed: " + describeError(e));
        } catch (RestClientException e) {
            throw new ElevenLabsApiException(
                    0, "Could not reach ElevenLabs' voice clone endpoint: " + e.getMessage());
        }
    }

    public void deleteVoice(String voiceId) {
        try {
            String apiKey = credentialService.resolveActiveApiKey();
            restClient
                    .delete()
                    .uri(VOICE_DELETE_URL, voiceId)
                    .header(API_KEY_HEADER, apiKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new ElevenLabsApiException(
                    e.getStatusCode().value(),
                    "ElevenLabs voice delete failed: " + describeError(e));
        } catch (RestClientException e) {
            throw new ElevenLabsApiException(
                    0, "Could not reach ElevenLabs' voice delete endpoint: " + e.getMessage());
        }
    }

    /**
     * @return raw MP3 bytes - ElevenLabs' text-to-speech endpoint returns audio directly (not
     *     JSON+base64, unlike Sarvam's TTS), so no decoding step is needed here.
     * @throws VoiceQuotaExceededException before any ElevenLabs call is made, if this request would
     *     exceed the calling tenant's plan tier - see {@link VoiceQuotaService}. Every caller of
     *     this method (the {@code speak_with_cloned_voice} agent tool, {@code core.voice}'s
     *     real-time voice calls) goes through this single client, so the gate sits here rather than
     *     being duplicated at each call site.
     * @implNote {@code text} is run through {@link SpeechTextNormalizer#normalizeForSpeech} before
     *     it is sent or quota-checked. Every caller of this method is handing over LLM output,
     *     which routinely contains markdown, HTML, emoji and bare URLs - ElevenLabs pronounces all
     *     of it literally ("asterisk asterisk bold asterisk asterisk"), which is the 2026-07-29
     *     live bug this normalization fixes. Doing it HERE rather than at each call site is the
     *     same "one client, one gate" reasoning the quota check above already follows: {@code
     *     core.voice}'s real-time turns and the {@code speak_with_cloned_voice} agent tool both get
     *     it, and a future third caller cannot forget it. The quota check deliberately runs on the
     *     NORMALIZED string, because that is the character count ElevenLabs actually bills for.
     */
    public byte[] textToSpeech(String voiceId, String text, String modelId) {
        String resolvedModel = (modelId == null || modelId.isBlank()) ? DEFAULT_MODEL : modelId;
        String spokenText = SpeechTextNormalizer.normalizeForSpeech(text);
        TenantPlan plan =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .map(tenantLimits::plan)
                        .orElse(TenantPlan.UNLIMITED);
        voiceQuotaService.checkAllowed(plan, spokenText);
        return callTextToSpeech(
                voiceId, spokenText, resolvedModel, credentialService.resolveActiveApiKey());
    }

    /**
     * Platform-subsidised path (2026-08-03) - VegaDūta's OWN operator-configured key, not the
     * tenant's. Deliberately skips {@link VoiceQuotaService}: that service caps a TENANT's own BYOK
     * spend and does not apply here - the caller ({@code PlatformElevenLabsSpeakBackend}) is
     * responsible for calling its own platform-spend quota check BEFORE this method, same "cap
     * first, never after" discipline {@code PlatformSarvamSpeakBackend} already establishes for the
     * Sarvam platform-fallback path.
     */
    public byte[] textToSpeechWithKey(
            String voiceId, String text, String modelId, String apiKeyOverride) {
        String resolvedModel = (modelId == null || modelId.isBlank()) ? DEFAULT_MODEL : modelId;
        String spokenText = SpeechTextNormalizer.normalizeForSpeech(text);
        return callTextToSpeech(voiceId, spokenText, resolvedModel, apiKeyOverride);
    }

    private byte[] callTextToSpeech(
            String voiceId, String spokenText, String resolvedModel, String apiKey) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", spokenText);
            body.put("model_id", resolvedModel);
            byte[] audio =
                    restClient
                            .post()
                            .uri(TEXT_TO_SPEECH_URL, voiceId)
                            .header(API_KEY_HEADER, apiKey)
                            .header(HttpHeaders.ACCEPT, "audio/mpeg")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(byte[].class);
            if (audio == null || audio.length == 0) {
                throw new ElevenLabsApiException(0, "ElevenLabs text-to-speech returned no audio.");
            }
            return audio;
        } catch (RestClientResponseException e) {
            throw new ElevenLabsApiException(
                    e.getStatusCode().value(),
                    "ElevenLabs text-to-speech call failed: " + describeError(e));
        } catch (RestClientException e) {
            throw new ElevenLabsApiException(
                    0, "Could not reach ElevenLabs' text-to-speech endpoint: " + e.getMessage());
        }
    }

    /**
     * Eleven Music - {@code POST /v1/music} (verified against ElevenLabs' API reference
     * 2026-09-20): JSON body {@code prompt} + {@code music_length_ms} (3,000-600,000) + {@code
     * model_id} + {@code force_instrumental}, raw audio bytes back. Billed per generated MINUTE, so
     * {@code lengthMs} is the cost lever and is clamped here to the API's own bounds rather than
     * trusted from the caller.
     *
     * <p>Takes the key explicitly (BYOK and platform callers both resolve their own) and is NOT
     * quota-gated here: {@link VoiceQuotaService} counts characters of speech, which means nothing
     * for music - the caller ({@code MusicGenerationService}, {@code DemoMusicController}) caps
     * first.
     *
     * @throws ElevenLabsApiException on any failure; {@link
     *     ElevenLabsApiException#getUpstreamStatus()} lets the caller tell a rejected model/prompt
     *     (4xx) from an outage.
     */
    public byte[] composeMusic(
            String prompt, int lengthMs, String modelId, boolean forceInstrumental, String apiKey) {
        int clampedLengthMs =
                Math.min(MUSIC_MAX_LENGTH_MS, Math.max(MUSIC_MIN_LENGTH_MS, lengthMs));
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", prompt);
            body.put("music_length_ms", clampedLengthMs);
            if (modelId != null && !modelId.isBlank()) {
                body.put("model_id", modelId);
            }
            body.put("force_instrumental", forceInstrumental);
            byte[] audio =
                    restClient
                            .post()
                            .uri(MUSIC_COMPOSE_URL)
                            .header(API_KEY_HEADER, apiKey)
                            .header(HttpHeaders.ACCEPT, "audio/mpeg")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(byte[].class);
            if (audio == null || audio.length == 0) {
                throw new ElevenLabsApiException(0, "ElevenLabs music returned no audio.");
            }
            return audio;
        } catch (RestClientResponseException e) {
            throw new ElevenLabsApiException(
                    e.getStatusCode().value(), "ElevenLabs music call failed: " + describeError(e));
        } catch (RestClientException e) {
            throw new ElevenLabsApiException(
                    0, "Could not reach ElevenLabs' music endpoint: " + e.getMessage());
        }
    }

    private static String describeError(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "HTTP " + e.getStatusCode().value();
        }
        return "HTTP " + e.getStatusCode().value() + ": " + body.trim();
    }
}
