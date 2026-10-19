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
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * {@link AnthropicProviderValidator} gates every "activate a provider"
 * request - a wrong status-code branch here either lets an actually-invalid
 * key through as ACTIVE, or rejects a genuinely fine key with a misleading
 * "try again" message instead of "your key is wrong." Had zero coverage
 * before this suite. Uses {@link MockRestServiceServer} - see {@code
 * ProviderModelCatalogServiceTest}'s javadoc for why that's this codebase's
 * chosen way to test {@code RestClient}-based code.
 */
class AnthropicProviderValidatorTest {

    private MockRestServiceServer mockServer;
    private AnthropicProviderValidator validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        validator = new AnthropicProviderValidator(builder);
    }

    @Test
    void supportedProviderIsAnthropic() {
        assertEquals(ProviderName.ANTHROPIC, validator.supportedProvider());
    }

    @Test
    void a2xxResponseValidatesSuccessfully() {
        mockServer.expect(requestTo("https://api.anthropic.com/v1/models"))
            .andExpect(header("x-api-key", "sk-ant-real"))
            .andExpect(header("anthropic-version", "2023-06-01"))
            .andRespond(withSuccess());

        assertDoesNotThrow(() -> validator.validate("sk-ant-real"));
    }

    @Test
    void a401RejectsTheKeyWithAClearMessage() {
        mockServer.expect(requestTo("https://api.anthropic.com/v1/models")).andRespond(withUnauthorizedRequest());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("bad-key"));

        assertTrue(ex.getMessage().contains("Anthropic rejected this API key"));
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void a403IsTreatedTheSameAs401() {
        mockServer.expect(requestTo("https://api.anthropic.com/v1/models"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("bad-key"));

        assertTrue(ex.getMessage().contains("rejected"));
    }

    @Test
    void a5xxIsSurfacedAsATransientTryAgainFailureNotAKeyRejection() {
        mockServer.expect(requestTo("https://api.anthropic.com/v1/models")).andRespond(withServerError());

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("maybe-fine-key"));

        assertTrue(ex.getMessage().contains("Try again"));
        assertTrue(ex.getMessage().toLowerCase().contains("could not validate"));
    }

    @Test
    void a429IsAlsoTreatedAsTransientNotAKeyRejection() {
        mockServer.expect(requestTo("https://api.anthropic.com/v1/models"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS));

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("429"));
        assertTrue(ex.getMessage().contains("Try again"));
    }

    @Test
    void aConnectionFailureIsWrappedWithACannotReachMessage() {
        mockServer.expect(requestTo("https://api.anthropic.com/v1/models"))
            .andRespond(request -> { throw new java.io.IOException("connection refused"); });

        ProviderValidationException ex = assertThrows(ProviderValidationException.class, () -> validator.validate("key"));

        assertTrue(ex.getMessage().contains("Could not reach Anthropic"));
    }
}
