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
 * rationale. OpenRouter's own differentiator worth locking down: it hits a
 * purpose-built {@code GET /api/v1/auth/key} endpoint, not a generic models
 * listing - asserted here so a future refactor can't accidentally point this
 * validator at the wrong URL (e.g. copy-pasting {@code
 * OpenAIProviderValidator}'s {@code /v1/models}, which OpenRouter also has,
 * but which would validate the key against a different, non-purpose-built
 * endpoint than the one this class's own javadoc documents choosing).
 */
class OpenRouterProviderValidatorTest {

    private MockRestServiceServer mockServer;
    private OpenRouterProviderValidator validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        validator = new OpenRouterProviderValidator(builder);
    }

    @Test
    void supportedProviderIsOpenRouter() {
        assertEquals(ProviderName.OPENROUTER, validator.supportedProvider());
    }

    @Test
    void hitsThePurposeBuiltAuthKeyEndpointWithABearerHeader() {
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/auth/key"))
            .andExpect(header("Authorization", "Bearer sk-or-real"))
            .andRespond(withSuccess());

        assertDoesNotThrow(() -> validator.validate("sk-or-real"));
    }

    @Test
    void a401Or403RejectsTheKey() {
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/auth/key")).andRespond(withUnauthorizedRequest());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));

        assertTrue(ex.getMessage().contains("OpenRouter rejected this API key"));
    }

    @Test
    void a5xxIsTransientNotAKeyRejection() {
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/auth/key")).andRespond(withServerError());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Try again"));
    }

    @Test
    void aConnectionFailureIsWrappedWithACannotReachMessage() {
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/auth/key"))
            .andRespond(request -> { throw new java.io.IOException("dns failure"); });

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Could not reach OpenRouter"));
    }
}
