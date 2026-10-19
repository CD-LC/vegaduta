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
 * rationale. {@link MetaProviderValidator} is a clone of the Grok validator
 * (Meta Model API is OpenAI-compatible with plain bearer auth), so the same
 * contracts are pinned: the probe hits {@code https://api.meta.ai/v1/models}
 * with the key as a bearer token (pipes and all - Meta keys look like
 * {@code LLM|...|...}), 400/401/403 read as "key rejected" with the body
 * folded in, 5xx reads as transient, and the rejection copy points a
 * region-blocked tenant at the OpenRouter alternative.
 */
class MetaProviderValidatorTest {

    private static final String KEY = "LLM|607358788850350|nx9abcLJY";

    private MockRestServiceServer mockServer;
    private MetaProviderValidator validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        validator = new MetaProviderValidator(builder);
    }

    @Test
    void supportedProviderIsMeta() {
        assertEquals(ProviderName.META, validator.supportedProvider());
    }

    @Test
    void sendsABearerAuthorizationHeaderToTheV1ModelsEndpoint() {
        mockServer.expect(requestTo("https://api.meta.ai/v1/models"))
            .andExpect(header("Authorization", "Bearer " + KEY))
            .andRespond(withSuccess());

        assertDoesNotThrow(() -> validator.validate(KEY));
    }

    @Test
    void a400IsTreatedAsAKeyRejectionNotAGenericTryAgainFailure() {
        mockServer.expect(requestTo("https://api.meta.ai/v1/models"))
            .andRespond(MockRestResponseCreators.withBadRequest()
                .body("{\"error\":{\"message\":\"Invalid API key\"}}")
                .contentType(MediaType.APPLICATION_JSON));

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));

        assertTrue(ex.getMessage().contains("Meta rejected this API key"));
        assertTrue(ex.getMessage().contains("400"));
    }

    @Test
    void rejectionBodyIsIncludedAndTheCopyNamesDevMetaAiAndTheOpenRouterFallback() {
        mockServer.expect(requestTo("https://api.meta.ai/v1/models"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.UNAUTHORIZED)
                .body("Unauthorized: invalid key")
                .contentType(MediaType.TEXT_PLAIN));

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Unauthorized: invalid key"));
        assertTrue(ex.getMessage().contains("dev.meta.ai"));
        assertTrue(ex.getMessage().contains("meta/muse-spark-1.3"));
        assertTrue(ex.getMessage().contains("not yet available in every country"));
    }

    @Test
    void a403IsAlsoTreatedAsAKeyRejection() {
        mockServer.expect(requestTo("https://api.meta.ai/v1/models"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));

        assertTrue(ex.getMessage().contains("Meta rejected this API key"));
    }

    @Test
    void a5xxIsTransientNotAKeyRejection() {
        mockServer.expect(requestTo("https://api.meta.ai/v1/models")).andRespond(withServerError());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Try again"));
    }
}
