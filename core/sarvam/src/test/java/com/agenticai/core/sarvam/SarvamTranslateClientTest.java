package com.agenticai.core.sarvam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * {@link SarvamTranslateClient} request-mapping and error-translation
 * coverage - the first tests in this module. Uses {@link
 * MockRestServiceServer} bound to the {@code RestClient.Builder}, the same
 * pattern {@code AnthropicProviderValidatorTest} (core/providers) documents
 * as this codebase's way to test {@code RestClient} code; the credential and
 * call-log collaborators are plain Mockito mocks so nothing touches a DB.
 *
 * <p>The regressions worth catching: a renamed/dropped snake_case request
 * field (Sarvam would 400 or silently mistranslate), a lost {@code
 * api-subscription-key} header, an HTTP failure surfacing as something other
 * than {@link SarvamApiException} with the real upstream status, and a call
 * that skips the {@link SarvamCallLogService} paper trail.
 */
class SarvamTranslateClientTest {

    private MockRestServiceServer server;
    private SarvamCredentialService credentialService;
    private SarvamCallLogService callLogService;
    private SarvamTranslateClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        credentialService = mock(SarvamCredentialService.class);
        callLogService = mock(SarvamCallLogService.class);
        client = new SarvamTranslateClient(builder, credentialService, callLogService);
        when(credentialService.resolveActiveApiKey()).thenReturn("test-key");
    }

    @Test
    void happyPathSendsSnakeCaseBodyWithAuthHeaderAndParsesTheResponse() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/translate"))
            .andExpect(MockRestRequestMatchers.header("api-subscription-key", "test-key"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.input").value("Hello"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.source_language_code").value("en-IN"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.target_language_code").value("hi-IN"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.model").value(SarvamTranslateClient.DEFAULT_MODEL))
            // mode=null must OMIT the field entirely, not send "mode": null.
            .andExpect(MockRestRequestMatchers.jsonPath("$.mode").doesNotExist())
            .andRespond(MockRestResponseCreators.withSuccess(
                "{\"translated_text\":\"नमस्ते\",\"source_language_code\":\"en-IN\"}",
                MediaType.APPLICATION_JSON));

        SarvamTranslateClient.TranslationResult result = client.translate("Hello", "en-IN", "hi-IN");

        assertEquals("नमस्ते", result.translatedText());
        assertEquals("en-IN", result.sourceLanguageCode());
        verify(callLogService).record(
            eq(SarvamCallCapability.TRANSLATE), eq(SarvamTranslateClient.DEFAULT_MODEL),
            eq(true), isNull(), anyLong(), any(Instant.class));
        server.verify();
    }

    @Test
    void explicitModelAndModeArePassedThrough() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/translate"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.model").value("mayura:v1"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.mode").value("modern-colloquial"))
            .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));

        client.translate("Hi", "en-IN", "kn-IN", "mayura:v1", "modern-colloquial");

        verify(callLogService).record(
            eq(SarvamCallCapability.TRANSLATE), eq("mayura:v1"), eq(true), isNull(), anyLong(), any(Instant.class));
    }

    @Test
    void httpErrorBecomesSarvamApiExceptionCarryingTheRealUpstreamStatus() {
        // Sarvam's auth failures are 403, not 401 (see SarvamApiException's
        // javadoc) - the status must pass through untranslated.
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/translate"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"invalid_api_key_error\"}}"));

        SarvamApiException ex = assertThrows(SarvamApiException.class,
            () -> client.translate("Hello", "en-IN", "hi-IN"));

        assertEquals(403, ex.getUpstreamStatus());
        assertTrue(ex.getMessage().contains("HTTP 403"));
        // The upstream error body must survive into the message - "translate
        // the upstream error, don't swallow it."
        assertTrue(ex.getMessage().contains("invalid_api_key_error"));
        verify(callLogService).record(
            eq(SarvamCallCapability.TRANSLATE), eq(SarvamTranslateClient.DEFAULT_MODEL),
            eq(false), contains("invalid_api_key_error"), anyLong(), any(Instant.class));
    }

    @Test
    void connectionFailureBecomesStatusZeroCouldNotReach() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/translate"))
            .andRespond(request -> { throw new IOException("connection refused"); });

        SarvamApiException ex = assertThrows(SarvamApiException.class,
            () -> client.translate("Hello", "en-IN", "hi-IN"));

        assertEquals(0, ex.getUpstreamStatus());
        assertTrue(ex.getMessage().contains("Could not reach"));
        verify(callLogService).record(
            eq(SarvamCallCapability.TRANSLATE), eq(SarvamTranslateClient.DEFAULT_MODEL),
            eq(false), any(), anyLong(), any(Instant.class));
    }

    @Test
    void missingCredentialSurfacesAsNotConfiguredBeforeAnyHttpCall() {
        when(credentialService.resolveActiveApiKey()).thenThrow(new SarvamNotConfiguredException());

        assertThrows(SarvamNotConfiguredException.class, () -> client.translate("Hello", "en-IN", "hi-IN"));

        // No HTTP expectation was registered, so this proves nothing was sent.
        server.verify();
        // The finally block still records the attempt - failures are part of the paper trail too.
        verify(callLogService).record(
            eq(SarvamCallCapability.TRANSLATE), eq(SarvamTranslateClient.DEFAULT_MODEL),
            eq(false), isNull(), anyLong(), any(Instant.class));
    }
}
