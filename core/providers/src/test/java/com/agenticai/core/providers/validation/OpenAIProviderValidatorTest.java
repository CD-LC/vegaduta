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
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * See {@code AnthropicProviderValidatorTest}'s javadoc for the shared
 * rationale. OpenAI's own differentiator worth locking down: unlike Grok
 * (which folds "bad key" and "no credit" into one message because it can't
 * tell them apart), OpenAI's API distinguishes an invalid key (401) from an
 * out-of-quota/rate-limited one (429) - this validator surfaces those as two
 * separate, accurately-worded messages, asserted separately here.
 */
class OpenAIProviderValidatorTest {

    private MockRestServiceServer mockServer;
    private OpenAIProviderValidator validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        validator = new OpenAIProviderValidator(builder);
    }

    @Test
    void supportedProviderIsOpenAi() {
        assertEquals(ProviderName.OPENAI, validator.supportedProvider());
    }

    @Test
    void sendsABearerAuthorizationHeader() {
        mockServer.expect(requestTo("https://api.openai.com/v1/models"))
            .andExpect(header("Authorization", "Bearer sk-real"))
            .andRespond(withSuccess());

        assertDoesNotThrow(() -> validator.validate("sk-real"));
    }

    @Test
    void a401IsWordedAsAnInvalidRevokedKey() {
        mockServer.expect(requestTo("https://api.openai.com/v1/models")).andRespond(withUnauthorizedRequest());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));

        assertTrue(ex.getMessage().contains("OpenAI rejected this API key"));
        assertTrue(ex.getMessage().contains("revoked"));
    }

    @Test
    void a429IsWordedAsABillingOrRateLimitProblemNotAnInvalidKey() {
        // OpenAI's own distinction: the key's identity was accepted, the
        // request itself was rejected - a materially different message from
        // the 401 case above, not folded into the same generic branch.
        mockServer.expect(requestTo("https://api.openai.com/v1/models"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS));

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("accepted this key's identity"));
        assertTrue(ex.getMessage().contains("billing"));
        assertTrue(ex.getMessage().contains("429"));
    }

    @Test
    void a5xxIsTransientNotAKeyRejection() {
        mockServer.expect(requestTo("https://api.openai.com/v1/models")).andRespond(withServerError());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Try again"));
    }
}
