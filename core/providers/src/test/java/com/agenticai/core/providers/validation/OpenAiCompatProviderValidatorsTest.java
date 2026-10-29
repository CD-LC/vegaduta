package com.agenticai.core.providers.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The three OpenAI-compatible single-key validators added in the July 2026
 * provider fan-out share one shape - a free {@code GET {vendor}/v1/models}
 * probe with the key as a Bearer token, a 401/403 worded as "the provider
 * rejected this key," and anything else worded as transient. See {@code
 * OpenAIProviderValidatorTest} for the original of this pattern.
 */
class OpenAiCompatProviderValidatorsTest {

    private static MockRestServiceServer bind(RestClient.Builder builder) {
        return MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void groqReportsItsProviderAndValidatesAgainstItsModelsEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        GroqProviderValidator validator = new GroqProviderValidator(builder);

        assertEquals(ProviderName.GROQ, validator.supportedProvider());
        server.expect(requestTo("https://api.groq.com/openai/v1/models"))
            .andExpect(header("Authorization", "Bearer groq-key"))
            .andRespond(withSuccess());
        assertDoesNotThrow(() -> validator.validate("groq-key"));
    }

    @Test
    void groqWordsA401AsARejectedKeyAndA5xxAsTransient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        GroqProviderValidator validator = new GroqProviderValidator(builder);

        server.expect(requestTo("https://api.groq.com/openai/v1/models")).andRespond(withUnauthorizedRequest());
        server.expect(requestTo("https://api.groq.com/openai/v1/models")).andRespond(withServerError());
        ProviderValidationException rejected =
            assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));
        assertTrue(rejected.getMessage().contains("Groq rejected this API key"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("console.groq.com"), rejected.getMessage());

        ProviderValidationException transientFailure =
            assertThrows(ProviderValidationException.class, () -> validator.validate("key"));
        assertTrue(transientFailure.getMessage().contains("Try again"), transientFailure.getMessage());
    }

    @Test
    void mistralReportsItsProviderAndValidatesAgainstItsModelsEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        MistralProviderValidator validator = new MistralProviderValidator(builder);

        assertEquals(ProviderName.MISTRAL, validator.supportedProvider());
        server.expect(requestTo("https://api.mistral.ai/v1/models"))
            .andExpect(header("Authorization", "Bearer mistral-key"))
            .andRespond(withSuccess());
        assertDoesNotThrow(() -> validator.validate("mistral-key"));
    }

    @Test
    void mistralWordsA403AsARejectedKeyAndAnUnexpectedStatusAsTransient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        MistralProviderValidator validator = new MistralProviderValidator(builder);

        server.expect(requestTo("https://api.mistral.ai/v1/models"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo("https://api.mistral.ai/v1/models"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        ProviderValidationException rejected =
            assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));
        assertTrue(rejected.getMessage().contains("Mistral AI rejected this API key"), rejected.getMessage());

        ProviderValidationException transientFailure =
            assertThrows(ProviderValidationException.class, () -> validator.validate("key"));
        assertTrue(transientFailure.getMessage().contains("Try again"), transientFailure.getMessage());
    }

    @Test
    void deepseekReportsItsProviderAndValidatesAgainstItsModelsEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        DeepSeekProviderValidator validator = new DeepSeekProviderValidator(builder);

        assertEquals(ProviderName.DEEPSEEK, validator.supportedProvider());
        server.expect(requestTo("https://api.deepseek.com/v1/models"))
            .andExpect(header("Authorization", "Bearer deepseek-key"))
            .andRespond(withSuccess());
        assertDoesNotThrow(() -> validator.validate("deepseek-key"));
    }

    @Test
    void deepseekWordsA401AsARejectedKeyAndA5xxAsTransient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        DeepSeekProviderValidator validator = new DeepSeekProviderValidator(builder);

        server.expect(requestTo("https://api.deepseek.com/v1/models")).andRespond(withUnauthorizedRequest());
        server.expect(requestTo("https://api.deepseek.com/v1/models")).andRespond(withServerError());
        ProviderValidationException rejected =
            assertThrows(ProviderValidationException.class, () -> validator.validate("bad"));
        assertTrue(rejected.getMessage().contains("DeepSeek rejected this API key"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("platform.deepseek.com"), rejected.getMessage());

        ProviderValidationException transientFailure =
            assertThrows(ProviderValidationException.class, () -> validator.validate("key"));
        assertTrue(transientFailure.getMessage().contains("Try again"), transientFailure.getMessage());
    }
}
