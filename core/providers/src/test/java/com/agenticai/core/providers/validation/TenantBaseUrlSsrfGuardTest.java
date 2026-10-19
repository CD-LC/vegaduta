package com.agenticai.core.providers.validation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.AzureOpenAiCredential;
import com.agenticai.core.providers.OllamaCredential;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * SSRF regression tests for the TENANT-supplied base-URL validators.
 *
 * <p><b>What these pin.</b> {@link OllamaProviderValidator} and {@link
 * AzureOpenAIProviderValidator} are the only two validators whose target host
 * comes from the tenant rather than being a fixed vendor endpoint. Both make a
 * server-side request to that host from inside the platform's VNet, and both
 * used to fold the upstream response body into the error message handed back to
 * the caller. Together that is not a blind SSRF - it is a full read: any
 * signed-in customer could name an internal address and receive its response.
 *
 * <p>The guard already existed and had protected the PLATFORM-ADMIN hosted-pool
 * path since the P1 pentest; it was simply never applied to the lower-trust
 * tenant path. These tests exist so that gap cannot silently reopen - a
 * validator that stops calling the guard fails here, not in production.
 *
 * <p>No MockRestServiceServer is bound on purpose: the guard must reject BEFORE
 * any request is attempted, so a test that reached the transport would fail
 * with a connection error rather than the expected validation exception.
 */
class TenantBaseUrlSsrfGuardTest {

    private final OllamaProviderValidator ollama =
        new OllamaProviderValidator(RestClient.builder());
    private final AzureOpenAIProviderValidator azure =
        new AzureOpenAIProviderValidator(RestClient.builder());

    /** The addresses that actually matter on this deployment. */
    private static final String[] INTERNAL_TARGETS = {
        "http://169.254.169.254",            // cloud instance metadata
        "http://127.0.0.1:6432",             // the pgbouncer sidecar, same pod
        "http://localhost:8080",
        "http://10.0.0.5:8080",              // VNet RFC1918
        "http://192.168.1.10",
        "http://[::1]:8080",                 // IPv6 loopback
        "http://100.64.0.1",                 // CGNAT
    };

    @Test
    void ollamaRefusesEveryInternalTarget() {
        for (String target : INTERNAL_TARGETS) {
            String cred = new OllamaCredential(target, null).encode();
            ProviderValidationException e = assertThrows(
                ProviderValidationException.class,
                () -> ollama.validate(cred),
                "Ollama validator accepted internal target " + target);
            assertTrue(
                e.getMessage().toLowerCase().contains("private")
                    || e.getMessage().toLowerCase().contains("not allowed")
                    || e.getMessage().toLowerCase().contains("loopback"),
                "Rejected " + target + " but not as an SSRF refusal: " + e.getMessage());
        }
    }

    @Test
    void azureOpenAiRefusesEveryInternalTarget() {
        for (String target : INTERNAL_TARGETS) {
            String cred = new AzureOpenAiCredential(target, "k", "gpt-4o", "2024-02-01").encode();
            assertThrows(
                ProviderValidationException.class,
                () -> azure.validate(cred),
                "Azure OpenAI validator accepted internal target " + target);
        }
    }

    @Test
    void ollamaStillRefusesAHostnameThatResolvesInternally() {
        // DNS-name form, not a literal IP — the guard resolves before deciding,
        // which is the half a naive string check would miss.
        String cred = new OllamaCredential("http://localhost:11434", null).encode();
        assertThrows(ProviderValidationException.class, () -> ollama.validate(cred));
    }
}
