package com.agenticai.core.sarvam;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * {@link SarvamTextToSpeechClient} coverage - same harness as {@link
 * SarvamTranslateClientTest}. Beyond the shared request-mapping/error-
 * translation contract, this client has two behaviors of its own worth
 * pinning: the base64 {@code audios[0]} -> raw WAV bytes decode (the build
 * plan's "callers never handle base64" promise), and the "response had no
 * audio" case, which must surface as a {@link SarvamApiException} rather
 * than a NullPointerException or an empty byte array handed to a caller
 * that will store it as a broken clip.
 *
 * <p>Note the text sent upstream passes through {@code
 * SpeechTextNormalizer.normalizeForSpeech} (a real static call, not mocked -
 * it is a pure function from core/speech with its own suite); test inputs
 * here are plain sentences the normalizer leaves alone, so these tests do
 * not double-cover its stripping rules.
 */
class SarvamTextToSpeechClientTest {

    private static final byte[] WAV_BYTES = "RIFF-fake-wav".getBytes(StandardCharsets.UTF_8);
    private static final String WAV_BASE64 = Base64.getEncoder().encodeToString(WAV_BYTES);

    private MockRestServiceServer server;
    private SarvamCredentialService credentialService;
    private SarvamCallLogService callLogService;
    private SarvamTextToSpeechClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        credentialService = mock(SarvamCredentialService.class);
        callLogService = mock(SarvamCallLogService.class);
        client = new SarvamTextToSpeechClient(builder, credentialService, callLogService);
        when(credentialService.resolveActiveApiKey()).thenReturn("test-key");
    }

    @Test
    void happyPathSendsSnakeCaseBodyAndReturnsDecodedWavBytes() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/text-to-speech"))
            .andExpect(MockRestRequestMatchers.header("api-subscription-key", "test-key"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.text").value("Hello there."))
            .andExpect(MockRestRequestMatchers.jsonPath("$.target_language_code").value("hi-IN"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.model").value(SarvamTextToSpeechClient.DEFAULT_MODEL))
            .andExpect(MockRestRequestMatchers.jsonPath("$.speaker").doesNotExist())
            .andRespond(MockRestResponseCreators.withSuccess(
                "{\"audios\":[\"" + WAV_BASE64 + "\"],\"request_id\":\"req-9\"}",
                MediaType.APPLICATION_JSON));

        SarvamTextToSpeechClient.SynthesisResult result = client.synthesize("Hello there.", "hi-IN");

        assertArrayEquals(WAV_BYTES, result.wavBytes());
        assertEquals("req-9", result.requestId());
        verify(callLogService).record(
            eq(SarvamCallCapability.TEXT_TO_SPEECH), eq(SarvamTextToSpeechClient.DEFAULT_MODEL),
            eq(true), isNull(), anyLong(), any(Instant.class));
        server.verify();
    }

    @Test
    void explicitModelAndSpeakerArePassedThrough() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/text-to-speech"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.model").value("bulbul:v2"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.speaker").value("anushka"))
            .andRespond(MockRestResponseCreators.withSuccess(
                "{\"audios\":[\"" + WAV_BASE64 + "\"]}", MediaType.APPLICATION_JSON));

        client.synthesize("Hi.", "hi-IN", "bulbul:v2", "anushka");

        verify(callLogService).record(
            eq(SarvamCallCapability.TEXT_TO_SPEECH), eq("bulbul:v2"), eq(true), isNull(), anyLong(), any(Instant.class));
    }

    @Test
    void apiKeyOverrideBypassesTenantCredentialResolution() {
        // The platform-subsidised path: PlatformSarvamSpeakBackend speaks for
        // tenants with no BYOK key - resolving the tenant credential here
        // would throw SarvamNotConfiguredException and defeat the feature.
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/text-to-speech"))
            .andExpect(MockRestRequestMatchers.header("api-subscription-key", "operator-key"))
            .andRespond(MockRestResponseCreators.withSuccess(
                "{\"audios\":[\"" + WAV_BASE64 + "\"]}", MediaType.APPLICATION_JSON));

        client.synthesize("Hi.", "hi-IN", null, null, "operator-key");

        verifyNoInteractions(credentialService);
    }

    @Test
    void emptyAudiosArrayIsAnApiErrorNotANullPointer() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/text-to-speech"))
            .andRespond(MockRestResponseCreators.withSuccess("{\"audios\":[]}", MediaType.APPLICATION_JSON));

        SarvamApiException ex = assertThrows(SarvamApiException.class, () -> client.synthesize("Hi.", "hi-IN"));

        assertEquals(0, ex.getUpstreamStatus());
        assertTrue(ex.getMessage().contains("no audio"));
        // A 2xx-with-no-audio still logs as a FAILED call, not a success.
        verify(callLogService).record(
            eq(SarvamCallCapability.TEXT_TO_SPEECH), eq(SarvamTextToSpeechClient.DEFAULT_MODEL),
            eq(false), isNull(), anyLong(), any(Instant.class));
    }

    @Test
    void httpErrorBecomesSarvamApiExceptionWithUpstreamStatus() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/text-to-speech"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"internal_server_error\"}}"));

        SarvamApiException ex = assertThrows(SarvamApiException.class, () -> client.synthesize("Hi.", "hi-IN"));

        assertEquals(500, ex.getUpstreamStatus());
        assertTrue(ex.getMessage().contains("text-to-speech"));
        verify(callLogService).record(
            eq(SarvamCallCapability.TEXT_TO_SPEECH), eq(SarvamTextToSpeechClient.DEFAULT_MODEL),
            eq(false), any(), anyLong(), any(Instant.class));
    }
}
