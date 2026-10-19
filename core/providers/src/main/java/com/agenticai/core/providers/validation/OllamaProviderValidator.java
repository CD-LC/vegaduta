package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.OllamaCredential;
import com.agenticai.core.providers.HostedLlmBaseUrlGuard;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Validates an Ollama connection by decoding the {@link OllamaCredential}
 * JSON blob (see that class's javadoc for the encode/decode pattern) and
 * hitting the tenant's own Ollama server's native {@code GET /api/tags}
 * endpoint - a free, non-generating "what models do you have pulled"
 * listing every Ollama install exposes, the same "lightweight, non-billed
 * probe" spirit {@link ProviderValidator}'s javadoc calls for.
 *
 * <p>Unlike every other provider validated here, this is a tenant-hosted
 * server that could plausibly be unreachable, mid-restart, or simply
 * misconfigured (wrong host/port, no network path from this platform) -
 * none of which means the credential itself is "wrong" the way a rejected
 * API key is. The network-failure branch below says so explicitly instead
 * of leaking a raw connection-refused/timeout stack trace to the tenant.
 */
@Component
public class OllamaProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(OllamaProviderValidator.class);

    private final RestClient restClient;
    private final SsrfGuard ssrfGuard;

    public OllamaProviderValidator(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, SsrfGuard.defaultGuard());
    }

    /**
     * SEC-023: the probe goes through an address-pinned, proxy-free,
     * redirect-free client built on {@code ssrfGuard}, so the base URL checked
     * below is the one connected to (no DNS rebinding) and a 3xx cannot bounce
     * the probe (or the optional Bearer key) into the internal network. A
     * self-hosted Ollama at a private address works only when the operator
     * lists it in {@code app.security.outbound.allowed-private-hosts}.
     */
    @Autowired
    public OllamaProviderValidator(RestClient.Builder restClientBuilder, SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
        this.restClient = restClientBuilder.clone()
            .requestFactory(SafeHttpClients.requestFactory(ssrfGuard, PROBE_TIMEOUT, PROBE_TIMEOUT, false))
            .build();
    }

    private static final java.time.Duration PROBE_TIMEOUT = java.time.Duration.ofSeconds(15);

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.OLLAMA;
    }

    @Override
    public void validate(String apiKey) {
        OllamaCredential credential;
        try {
            credential = OllamaCredential.decode(apiKey);
        } catch (IllegalStateException e) {
            throw new ProviderValidationException(
                "Could not read the Ollama connection details - this field should be filled in via the "
                    + "activation form, not pasted by hand.", e);
        }

        if (credential.baseUrl() == null || credential.baseUrl().isBlank()) {
            throw new ProviderValidationException(
                "An Ollama base URL is required (e.g. http://your-ollama-host:11434).");
        }

        // SSRF GUARD (added 2026-09-17). baseUrl is TENANT-SUPPLIED and this method
        // makes a server-side request to it from inside the platform's VNet. Without
        // this check any signed-in customer could point it at an internal address -
        // http://169.254.169.254/ (cloud metadata), http://keycloak:8080,
        // localhost:6432 (the pgbouncer sidecar), or any other Container App's
        // internal FQDN - and have core fetch it for them.
        //
        // The same guard already protected the PLATFORM-ADMIN hosted-pool path
        // (PlatformHostedLlmProviderService#create) since the P1 pentest. It was
        // never applied to the TENANT path, which is the lower-trust of the two.
        HostedLlmBaseUrlGuard.checkAllowed(credential.baseUrl(), ssrfGuard);

        String tagsUrl = normalizeBaseUrl(credential.baseUrl()) + "/api/tags";
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(tagsUrl);
            if (credential.apiKey() != null && !credential.apiKey().isBlank()) {
                request = request.header("Authorization", "Bearer " + credential.apiKey());
            }
            request.retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            // The upstream body is deliberately NOT echoed to the tenant. Combined
            // with a tenant-controlled baseUrl it turned a blind SSRF into a full
            // read: whatever the probed host returned came straight back in the
            // error message. The guard above blocks internal targets, but not
            // echoing is the second half of the fix - a public host can still be
            // one the tenant should not be reading through our credentials/network.
            // The body is logged server-side instead, where operators can see it.
            log.warn("Ollama validation probe to {} returned HTTP {} - body withheld from the caller",
                tagsUrl, status.value());
            throw new ProviderValidationException(
                "The Ollama server at " + credential.baseUrl() + " responded with HTTP " + status.value()
                    + ". Check the base URL and, if this server sits behind an auth proxy, the optional API key.");
        } catch (RestClientException e) {
            log.warn("Ollama validation call to {} failed", tagsUrl, e);
            throw new ProviderValidationException(
                // SEC-023: no transport detail (refused / timeout / reset) - that text is a
                // port-scan oracle. Operators get it from the log line above.
                "Could not reach the Ollama server at " + credential.baseUrl()
                    + " - check the base URL is correct and reachable from this platform (a tenant-hosted "
                    + "Ollama server behind a firewall/NAT won't be reachable unless it's exposed to this "
                    + "platform's network).", e);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.replaceAll("/+$", "");
    }
}
