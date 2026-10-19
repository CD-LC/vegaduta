package com.agenticai.core.providers.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * See {@code AnthropicProviderValidatorTest}'s javadoc for the shared
 * rationale. xAI's real differentiator, and the whole reason this validator
 * has a documented production incident in its own javadoc, is that it
 * treats HTTP 400 - not just 401/403 - as "key rejected" (xAI's own
 * debugging docs list 400 as a possible "incorrect API key" cause on every
 * endpoint), and folds in the response body as extra detail since a 400 can
 * also mean something other than a bad key. Both are asserted directly here
 * as regression tests for that incident.
 */
class GrokProviderValidatorTest {

    private MockRestServiceServer mockServer;
    private GrokProviderValidator validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        validator = new GrokProviderValidator(builder);
    }

    @Test
    void supportedProviderIsGrok() {
        assertEquals(ProviderName.GROK, validator.supportedProvider());
    }

    @Test
    void sendsABearerAuthorizationHeader() {
        mockServer.expect(requestTo("https://api.x.ai/v1/models"))
            .andExpect(header("Authorization", "Bearer xai-real-key"))
            .andRespond(withSuccess());

        assertDoesNotThrow(() -> validator.validate("xai-real-key"));
    }

    @Test
    void a400IsTreatedAsAKeyRejectionNotAGenericTryAgainFailure() {
        // The production incident this validator's javadoc documents: xAI
        // returns 400, not 401/403, for "an incorrect API key was supplied".
        mockServer.expect(requestTo("https://api.x.ai/v1/models"))
            .andRespond(MockRestResponseCreators.withBadRequest()
                .body("{\"code\":\"invalid_api_key\",\"error\":\"Incorrect API key provided\"}")
                .contentType(MediaType.APPLICATION_JSON));

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));

        assertTrue(ex.getMessage().contains("xAI rejected this API key"));
        assertTrue(ex.getMessage().contains("400"));
    }

    @Test
    void a400ResponseBodyIsIncludedSinceItCanAlsoMeanNoCreditNotJustAWrongKey() {
        mockServer.expect(requestTo("https://api.x.ai/v1/models"))
            .andRespond(MockRestResponseCreators.withBadRequest()
                .body("Incorrect API key provided")
                .contentType(MediaType.TEXT_PLAIN));

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Incorrect API key provided"));
        assertTrue(ex.getMessage().contains("no billing method"));
    }

    @Test
    void a401And403AreAlsoTreatedAsKeyRejections() {
        mockServer.expect(requestTo("https://api.x.ai/v1/models"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));
    }

    @Test
    void a5xxIsTransientNotAKeyRejection() {
        mockServer.expect(requestTo("https://api.x.ai/v1/models")).andRespond(withServerError());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Try again"));
    }
}
