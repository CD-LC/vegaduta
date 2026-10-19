package com.agenticai.core.providers.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * See {@code AnthropicProviderValidatorTest}'s javadoc for the shared
 * rationale and {@code MockRestServiceServer} approach. Gemini's own
 * differentiator worth locking down: the key rides in the {@code
 * x-goog-api-key} header, never a {@code ?key=} query parameter (this
 * platform's privacy rule against sensitive data in URLs - see the
 * validator's own javadoc).
 */
class GeminiProviderValidatorTest {

    private MockRestServiceServer mockServer;
    private GeminiProviderValidator validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        validator = new GeminiProviderValidator(builder);
    }

    @Test
    void supportedProviderIsGemini() {
        assertEquals(ProviderName.GEMINI, validator.supportedProvider());
    }

    @Test
    void sendsTheKeyAsAHeaderNeverAsAQueryParameter() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models"))
            .andExpect(header("x-goog-api-key", "AIzaReal"))
            .andRespond(withSuccess());

        assertDoesNotThrow(() -> validator.validate("AIzaReal"));
    }

    @Test
    void a401RejectsTheKey() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models"))
            .andRespond(withUnauthorizedRequest());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));

        assertTrue(ex.getMessage().contains("Google rejected this API key"));
    }

    @Test
    void a5xxIsTransientNotAKeyRejection() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models")).andRespond(withServerError());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Try again"));
    }

    @Test
    void aConnectionFailureIsWrappedWithACannotReachMessage() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models"))
            .andRespond(request -> { throw new java.io.IOException("timeout"); });

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Could not reach Google's Gemini API"));
    }
}
