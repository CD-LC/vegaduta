package com.agenticai.core.providers.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.VertexAccessTokenProvider;
import com.agenticai.core.providers.VertexCredential;
import com.agenticai.core.providers.VertexCredentialException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The Vertex BYOK validator is a two-step probe: decode the project/location/
 * service-account blob, exchange it for an OAuth token (mocked - no real
 * Google auth), then a fixed {@code GET {location}-aiplatform.googleapis.com/
 * v1/publishers/google/models/gemini-2.5-flash} call, which {@code
 * MockRestServiceServer} serves.
 */
class GoogleVertexProviderValidatorTest {

    private static final String PROBE_URL =
        "https://us-central1-aiplatform.googleapis.com/v1/publishers/google/models/gemini-2.5-flash";

    private VertexAccessTokenProvider tokenProvider;
    private MockRestServiceServer mockServer;
    private GoogleVertexProviderValidator validator;

    @BeforeEach
    void setUp() {
        tokenProvider = mock(VertexAccessTokenProvider.class);
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        validator = new GoogleVertexProviderValidator(tokenProvider, builder);
    }

    private static String encodedCredential() {
        return new VertexCredential("project-1", "us-central1", "{\"type\":\"service_account\"}").encode();
    }

    @Test
    void supportedProviderIsGoogleVertex() {
        assertEquals(ProviderName.GOOGLE_VERTEX, validator.supportedProvider());
    }

    @Test
    void aBlobThatIsNotAVertexCredentialIsRejected() {
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate("a-plain-api-key"));
        assertTrue(e.getMessage().contains("isn't a valid Vertex AI credential"), e.getMessage());
    }

    @Test
    void aServiceAccountGoogleCannotExchangeIsWordedAsRejection() {
        when(tokenProvider.accessTokenForCredential(any(VertexCredential.class)))
            .thenThrow(new VertexCredentialException("invalid_grant"));
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate(encodedCredential()));
        assertTrue(e.getMessage().contains("Google rejected this service-account key"), e.getMessage());
        assertTrue(e.getMessage().contains("invalid_grant"), e.getMessage());
    }

    @Test
    void aSuccessfulProbeSendsTheExchangedTokenAsBearer() {
        when(tokenProvider.accessTokenForCredential(any(VertexCredential.class))).thenReturn("ya29.token");
        mockServer.expect(requestTo(PROBE_URL))
            .andExpect(header("Authorization", "Bearer ya29.token"))
            .andRespond(withSuccess());
        assertDoesNotThrow(() -> validator.validate(encodedCredential()));
    }

    @Test
    void a401FromVertexIsARejectedCredential() {
        when(tokenProvider.accessTokenForCredential(any(VertexCredential.class))).thenReturn("ya29.token");
        mockServer.expect(requestTo(PROBE_URL)).andRespond(withUnauthorizedRequest());
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate(encodedCredential()));
        assertTrue(e.getMessage().contains("Vertex AI rejected this credential"), e.getMessage());
    }

    @Test
    void anUnexpectedStatusIsWordedAsTransient() {
        when(tokenProvider.accessTokenForCredential(any(VertexCredential.class))).thenReturn("ya29.token");
        mockServer.expect(requestTo(PROBE_URL)).andRespond(withServerError());
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate(encodedCredential()));
        assertTrue(e.getMessage().contains("Try again"), e.getMessage());
    }
}
