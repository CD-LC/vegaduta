package com.agenticai.core.sarvam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * {@link SarvamSpeechToTextClient} coverage - same {@link
 * MockRestServiceServer}-plus-Mockito harness as {@link
 * SarvamTranslateClientTest} (see that class's javadoc for the pattern
 * citation). The multipart request body is asserted at the header/content-
 * type level plus a raw-body substring check for the part names - Spring's
 * request matchers have no first-class multipart-part matcher for the mock
 * client-side request, so this is the honest level of precision available
 * without re-parsing the multipart encoding by hand.
 */
class SarvamSpeechToTextClientTest {

    private MockRestServiceServer server;
    private SarvamCredentialService credentialService;
    private SarvamCallLogService callLogService;
    private SarvamSpeechToTextClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        credentialService = mock(SarvamCredentialService.class);
        callLogService = mock(SarvamCallLogService.class);
        client = new SarvamSpeechToTextClient(builder, credentialService, callLogService);
        when(credentialService.resolveActiveApiKey()).thenReturn("test-key");
    }

    @Test
    void happyPathUploadsMultipartWithDefaultModelAndParsesTranscript() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/speech-to-text"))
            .andExpect(MockRestRequestMatchers.header("api-subscription-key", "test-key"))
            .andExpect(MockRestRequestMatchers.content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andExpect(request -> {
                String body = ((MockClientHttpRequest) request).getBodyAsString();
                assertTrue(body.contains("name=\"file\""), "file part missing");
                assertTrue(body.contains("filename=\"note.wav\""), "upload filename missing");
                assertTrue(body.contains("name=\"model\""), "model part missing");
                assertTrue(body.contains(SarvamSpeechToTextClient.DEFAULT_MODEL), "default model missing");
                // Null mode/language must omit those parts entirely.
                assertFalse(body.contains("name=\"mode\""), "mode part should be omitted when null");
                assertFalse(body.contains("name=\"language_code\""), "language_code part should be omitted when null");
            })
            .andRespond(MockRestResponseCreators.withSuccess(
                "{\"transcript\":\"नमस्ते दुनिया\",\"language_code\":\"hi-IN\",\"request_id\":\"req-1\"}",
                MediaType.APPLICATION_JSON));

        SarvamSpeechToTextClient.TranscriptionResult result =
            client.transcribe("fake-wav".getBytes(StandardCharsets.UTF_8), "note.wav");

        assertEquals("नमस्ते दुनिया", result.transcript());
        assertEquals("hi-IN", result.languageCode());
        assertEquals("req-1", result.requestId());
        verify(callLogService).record(
            eq(SarvamCallCapability.SPEECH_TO_TEXT), eq(SarvamSpeechToTextClient.DEFAULT_MODEL),
            eq(true), isNull(), anyLong(), any(Instant.class));
        server.verify();
    }

    @Test
    void explicitModelModeAndLanguageArePassedThrough() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/speech-to-text"))
            .andExpect(request -> {
                String body = ((MockClientHttpRequest) request).getBodyAsString();
                assertTrue(body.contains("saarika:v2.5"), "explicit model missing");
                assertTrue(body.contains("name=\"mode\""), "mode part missing");
                assertTrue(body.contains("translate"), "mode value missing");
                assertTrue(body.contains("name=\"language_code\""), "language_code part missing");
                assertTrue(body.contains("hi-IN"), "language value missing");
            })
            .andRespond(MockRestResponseCreators.withSuccess("{\"transcript\":\"x\"}", MediaType.APPLICATION_JSON));

        client.transcribe(new byte[] {1, 2}, "a.wav", "saarika:v2.5", "translate", "hi-IN");

        verify(callLogService).record(
            eq(SarvamCallCapability.SPEECH_TO_TEXT), eq("saarika:v2.5"),
            eq(true), isNull(), anyLong(), any(Instant.class));
    }

    @Test
    void httpErrorBecomesSarvamApiExceptionWithUpstreamStatusAndFailureIsLogged() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/speech-to-text"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"rate_limit_exceeded_error\"}}"));

        SarvamApiException ex = assertThrows(SarvamApiException.class,
            () -> client.transcribe(new byte[] {1}, "a.wav"));

        assertEquals(429, ex.getUpstreamStatus());
        assertTrue(ex.getMessage().contains("speech-to-text"));
        assertTrue(ex.getMessage().contains("rate_limit_exceeded_error"));
        verify(callLogService).record(
            eq(SarvamCallCapability.SPEECH_TO_TEXT), eq(SarvamSpeechToTextClient.DEFAULT_MODEL),
            eq(false), any(), anyLong(), any(Instant.class));
    }

    @Test
    void connectionFailureBecomesStatusZeroCouldNotReach() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/speech-to-text"))
            .andRespond(request -> { throw new java.io.IOException("connection reset"); });

        SarvamApiException ex = assertThrows(SarvamApiException.class,
            () -> client.transcribe(new byte[] {1}, "a.wav"));

        assertEquals(0, ex.getUpstreamStatus());
        assertTrue(ex.getMessage().contains("Could not reach"));
    }
}
