package com.agenticai.core.elevenlabs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link ElevenLabsClient} covering voice cloning, deletion,
 * text-to-speech and music composition with success and failure scenarios.
 *
 * <p>The client builds its {@link RestClient} from an injected
 * {@link RestClient.Builder}, so these tests bind a {@link MockRestServiceServer}
 * to a real builder and assert on the actual HTTP requests (URL, method,
 * {@code xi-api-key} header, JSON body) instead of mocking the fluent chain.
 * Credential service, quota service and tenant limits are plain mocks - no
 * Spring context.
 */
class ElevenLabsClientTest {

    private static final String API_KEY = "test-api-key-123";

    private MockRestServiceServer server;
    private ElevenLabsCredentialService credentialService;
    private VoiceQuotaService voiceQuotaService;
    private TenantLimits tenantLimits;
    private ElevenLabsClient client;

    @BeforeEach
    void setUp() {
        credentialService = mock(ElevenLabsCredentialService.class);
        voiceQuotaService = mock(VoiceQuotaService.class);
        tenantLimits = mock(TenantLimits.class);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        client = new ElevenLabsClient(builder, credentialService, voiceQuotaService, tenantLimits);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // cloneVoice - upload sample and get voice_id
    // ------------------------------------------------------------------

    @Test
    void cloneVoiceReturnsVoiceIdOnSuccess() {
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/voices/add"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("xi-api-key", API_KEY))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andRespond(withSuccess("{\"voice_id\":\"voice-xyz-123\"}", MediaType.APPLICATION_JSON));

        String voiceId = client.cloneVoice("test-voice", "audio-data".getBytes(), "audio/mpeg", "sample.mp3");

        assertEquals("voice-xyz-123", voiceId);
        verify(credentialService, times(1)).resolveActiveApiKey();
        server.verify();
    }

    @Test
    void cloneVoiceThrowsWhenResponseHasNoVoiceId() {
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/voices/add"))
            .andRespond(withSuccess("{\"requires_verification\":false}", MediaType.APPLICATION_JSON));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> client.cloneVoice("test-voice", "audio-data".getBytes(), "audio/mpeg", "sample.mp3"));
        assertEquals(0, thrown.getUpstreamStatus());
        assertTrue(thrown.getMessage().contains("voice_id"));
        server.verify();
    }

    @Test
    void cloneVoiceThrowsApiExceptionOn401WithUpstreamBody() {
        when(credentialService.resolveActiveApiKey()).thenReturn("invalid-key");
        server.expect(requestTo("https://api.elevenlabs.io/v1/voices/add"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"invalid_api_key\"}"));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> client.cloneVoice("test-voice", "audio-data".getBytes(), "audio/mpeg", "sample.mp3"));
        assertEquals(401, thrown.getUpstreamStatus());
        assertTrue(thrown.getMessage().contains("HTTP 401"));
        assertTrue(thrown.getMessage().contains("invalid_api_key"));
        server.verify();
    }

    @Test
    void cloneVoiceTranslatesTransportFailureToStatusZero() {
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/voices/add"))
            .andRespond(withException(new IOException("connection refused")));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> client.cloneVoice("test-voice", "audio-data".getBytes(), "audio/mpeg", "sample.mp3"));
        assertEquals(0, thrown.getUpstreamStatus());
        assertTrue(thrown.getMessage().contains("Could not reach"));
    }

    // ------------------------------------------------------------------
    // deleteVoice - delete a voice
    // ------------------------------------------------------------------

    @Test
    void deleteVoiceSucceedsOnNoContent() {
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/voices/voice-xyz-123"))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header("xi-api-key", API_KEY))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.deleteVoice("voice-xyz-123"); // should not throw

        verify(credentialService, times(1)).resolveActiveApiKey();
        server.verify();
    }

    @Test
    void deleteVoiceThrowsApiExceptionOn404() {
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/voices/voice-not-found"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> client.deleteVoice("voice-not-found"));
        assertEquals(404, thrown.getUpstreamStatus());
        assertTrue(thrown.getMessage().contains("HTTP 404"));
        server.verify();
    }

    // ------------------------------------------------------------------
    // textToSpeech - generate audio from text (quota-gated)
    // ------------------------------------------------------------------

    @Test
    void textToSpeechReturnsAudioBytesOnSuccess() {
        byte[] audioBytes = "mp3-data".getBytes();
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/text-to-speech/voice-xyz-123"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("xi-api-key", API_KEY))
            .andExpect(header("Accept", "audio/mpeg"))
            .andExpect(jsonPath("$.text").value("Hello, world!"))
            .andExpect(jsonPath("$.model_id").value("eleven_turbo_v2_5"))
            .andRespond(withSuccess(audioBytes, MediaType.valueOf("audio/mpeg")));

        byte[] result = client.textToSpeech("voice-xyz-123", "Hello, world!", "eleven_turbo_v2_5");

        assertArrayEquals(audioBytes, result);
        verify(voiceQuotaService, times(1)).checkAllowed(any(), anyString());
        server.verify();
    }

    @Test
    void textToSpeechWithoutTenantContextChecksQuotaAsUnlimited() {
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/text-to-speech/voice-xyz-123"))
            .andRespond(withSuccess("mp3".getBytes(), MediaType.valueOf("audio/mpeg")));

        client.textToSpeech("voice-xyz-123", "Hello, world!", null);

        verify(voiceQuotaService).checkAllowed(eq(TenantPlan.UNLIMITED), anyString());
        verify(tenantLimits, never()).plan(anyString());
        server.verify();
    }

    @Test
    void textToSpeechThrowsVoiceQuotaExceededBeforeCallingAPI() {
        TenantContext.set(TenantContext.of("tenant-free", "user-1", Set.of()));
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        when(tenantLimits.plan("tenant-free")).thenReturn(TenantPlan.FREE);
        doThrow(new VoiceQuotaExceededException("Quota exceeded"))
            .when(voiceQuotaService).checkAllowed(eq(TenantPlan.FREE), anyString());
        // No expectations registered: any HTTP call would fail the verify below.

        VoiceQuotaExceededException thrown = assertThrows(VoiceQuotaExceededException.class,
            () -> client.textToSpeech("voice-xyz-123", "x".repeat(50_000), "eleven_multilingual_v2"));

        assertTrue(thrown.getMessage().contains("Quota"));
        verify(credentialService, never()).resolveActiveApiKey();
        server.verify();
    }

    @Test
    void textToSpeechThrowsApiExceptionOn401() {
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/text-to-speech/voice-xyz-123"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> client.textToSpeech("voice-xyz-123", "Hello, world!", "eleven_multilingual_v2"));
        assertEquals(401, thrown.getUpstreamStatus());
        server.verify();
    }

    @Test
    void textToSpeechUsesDefaultModelWhenNoneProvided() {
        byte[] audioBytes = "mp3-data".getBytes();
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/text-to-speech/voice-xyz-123"))
            .andExpect(jsonPath("$.model_id").value(ElevenLabsClient.DEFAULT_MODEL))
            .andRespond(withSuccess(audioBytes, MediaType.valueOf("audio/mpeg")));

        byte[] result = client.textToSpeech("voice-xyz-123", "Hello, world!", null);

        assertArrayEquals(audioBytes, result);
        server.verify();
    }

    @Test
    void textToSpeechThrowsWhenResponseHasNoAudio() {
        when(credentialService.resolveActiveApiKey()).thenReturn(API_KEY);
        server.expect(requestTo("https://api.elevenlabs.io/v1/text-to-speech/voice-xyz-123"))
            .andRespond(withSuccess(new byte[0], MediaType.valueOf("audio/mpeg")));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> client.textToSpeech("voice-xyz-123", "Hello, world!", null));
        assertEquals(0, thrown.getUpstreamStatus());
        assertTrue(thrown.getMessage().contains("no audio"));
        server.verify();
    }

    // ------------------------------------------------------------------
    // textToSpeechWithKey - platform-subsidized path
    // ------------------------------------------------------------------

    @Test
    void textToSpeechWithKeyUsesOverrideKeyAndSkipsTenantQuota() {
        byte[] audioBytes = "mp3-data".getBytes();
        server.expect(requestTo("https://api.elevenlabs.io/v1/text-to-speech/voice-xyz-123"))
            .andExpect(header("xi-api-key", "platform-key-789"))
            .andExpect(jsonPath("$.model_id").value("eleven_multilingual_v2"))
            .andRespond(withSuccess(audioBytes, MediaType.valueOf("audio/mpeg")));

        byte[] result = client.textToSpeechWithKey("voice-xyz-123", "Hello, world!", "eleven_multilingual_v2", "platform-key-789");

        assertArrayEquals(audioBytes, result);
        verify(credentialService, never()).resolveActiveApiKey();
        verify(voiceQuotaService, never()).checkAllowed(any(), anyString());
        server.verify();
    }

    // ------------------------------------------------------------------
    // composeMusic - generate music from prompt
    // ------------------------------------------------------------------

    private static final String MUSIC_URL = "https://api.elevenlabs.io/v1/music?output_format=mp3_44100_128";

    @Test
    void composeMusicReturnsAudioBytesOnSuccess() {
        byte[] audioBytes = "mp3-music".getBytes();
        server.expect(requestTo(MUSIC_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("xi-api-key", API_KEY))
            .andExpect(jsonPath("$.prompt").value("upbeat electronic music"))
            .andExpect(jsonPath("$.music_length_ms").value(10_000))
            .andExpect(jsonPath("$.model_id").value("music_v1"))
            .andExpect(jsonPath("$.force_instrumental").value(true))
            .andRespond(withSuccess(audioBytes, MediaType.valueOf("audio/mpeg")));

        byte[] result = client.composeMusic("upbeat electronic music", 10_000, "music_v1", true, API_KEY);

        assertArrayEquals(audioBytes, result);
        server.verify();
    }

    @Test
    void composeMusicOmitsModelIdWhenBlank() {
        server.expect(requestTo(MUSIC_URL))
            .andExpect(jsonPath("$.model_id").doesNotExist())
            .andRespond(withSuccess("mp3".getBytes(), MediaType.valueOf("audio/mpeg")));

        client.composeMusic("test music", 10_000, " ", false, API_KEY);

        server.verify();
    }

    @Test
    void composeMusicClampsLengthToMinBound() {
        server.expect(requestTo(MUSIC_URL))
            .andExpect(jsonPath("$.music_length_ms").value(ElevenLabsClient.MUSIC_MIN_LENGTH_MS))
            .andRespond(withSuccess("mp3".getBytes(), MediaType.valueOf("audio/mpeg")));

        client.composeMusic("test music", 1_000, "model", false, API_KEY);

        server.verify();
    }

    @Test
    void composeMusicClampsLengthToMaxBound() {
        server.expect(requestTo(MUSIC_URL))
            .andExpect(jsonPath("$.music_length_ms").value(ElevenLabsClient.MUSIC_MAX_LENGTH_MS))
            .andRespond(withSuccess("mp3".getBytes(), MediaType.valueOf("audio/mpeg")));

        client.composeMusic("test music", 1_000_000, "model", false, API_KEY);

        server.verify();
    }

    @Test
    void composeMusicThrowsApiExceptionOnError() {
        server.expect(requestTo(MUSIC_URL))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("rate limited"));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> client.composeMusic("test music", 10_000, "model", false, API_KEY));
        assertEquals(429, thrown.getUpstreamStatus());
        assertTrue(thrown.getMessage().contains("rate limited"));
        server.verify();
    }

    @Test
    void composeMusicThrowsWhenResponseHasNoAudio() {
        server.expect(requestTo(MUSIC_URL))
            .andRespond(withSuccess(new byte[0], MediaType.valueOf("audio/mpeg")));

        ElevenLabsApiException thrown = assertThrows(ElevenLabsApiException.class,
            () -> client.composeMusic("test music", 10_000, "model", false, API_KEY));
        assertEquals(0, thrown.getUpstreamStatus());
        assertTrue(thrown.getMessage().contains("audio"));
        server.verify();
    }
}
