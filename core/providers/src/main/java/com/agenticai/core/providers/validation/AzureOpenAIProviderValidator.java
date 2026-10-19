package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.AzureOpenAiCredential;
import com.agenticai.core.providers.HostedLlmBaseUrlGuard;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Validates an Azure OpenAI connection by decoding the {@link
 * AzureOpenAiCredential} JSON blob and calling Azure's data-plane {@code GET
 * {endpoint}/openai/deployments?api-version=...} "list deployments"
 * endpoint - a free, non-generating call authenticated the same way a real
 * chat completion is ({@code api-key} header, not {@code Authorization:
 * Bearer}), matching {@link ProviderValidator}'s "lightweight, non-billed
 * probe" contract.
 *
 * <p>Goes one step further than a plain "is this key valid" check, the same
 * "real API-probe validation" spirit the platform's other multi-field
 * providers use: the response's deployment list is scanned for {@code
 * deploymentName}, since an endpoint+key can be entirely valid while the
 * deployment name itself is typo'd or was deleted - that's a distinct,
 * clearly-actionable failure from "the key is wrong," and this reports it as
 * such instead of only confirming auth succeeded.
 */
@Component
public class AzureOpenAIProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAIProviderValidator.class);
    private static final String API_KEY_HEADER = "api-key";

    private final RestClient restClient;
    private final SsrfGuard ssrfGuard;

    public AzureOpenAIProviderValidator(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, SsrfGuard.defaultGuard());
    }

    /**
     * SEC-023: the probe goes through an address-pinned, proxy-free,
     * redirect-free client built on {@code ssrfGuard}, so the endpoint checked
     * below is the one connected to (no DNS rebinding) and a 3xx cannot carry
     * the api-key header to another host.
     */
    @Autowired
    public AzureOpenAIProviderValidator(RestClient.Builder restClientBuilder, SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
        this.restClient = restClientBuilder.clone()
            .requestFactory(SafeHttpClients.requestFactory(ssrfGuard, PROBE_TIMEOUT, PROBE_TIMEOUT, false))
            .build();
    }

    private static final java.time.Duration PROBE_TIMEOUT = java.time.Duration.ofSeconds(15);

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.AZURE_OPENAI;
    }

    @Override
    public void validate(String apiKey) {
        AzureOpenAiCredential credential;
        try {
            credential = AzureOpenAiCredential.decode(apiKey);
        } catch (IllegalStateException e) {
            throw new ProviderValidationException(
                "Could not read the Azure OpenAI connection details - this field should be filled in via the "
                    + "activation form, not pasted by hand.", e);
        }

        if (credential.endpoint() == null || credential.endpoint().isBlank()) {
            throw new ProviderValidationException(
                "An Azure OpenAI resource endpoint is required (e.g. https://your-resource.openai.azure.com).");
        }
        if (credential.apiKey() == null || credential.apiKey().isBlank()) {
            throw new ProviderValidationException("An Azure OpenAI API key is required.");
        }
        if (credential.deploymentName() == null || credential.deploymentName().isBlank()) {
            throw new ProviderValidationException(
                "An Azure OpenAI deployment name is required - this is the deployment you created in Azure AI "
                    + "Foundry / the Azure Portal, not the underlying model id.");
        }

        // SSRF GUARD (added 2026-09-17), same reasoning as OllamaProviderValidator:
        // `endpoint` is TENANT-SUPPLIED and this method makes a server-side request
        // to it from inside the platform's VNet. An Azure OpenAI resource URL looks
        // like https://<resource>.openai.azure.com, but nothing forced it to be one -
        // a tenant could name any internal address and have core fetch it.
        HostedLlmBaseUrlGuard.checkAllowed(credential.endpoint(), ssrfGuard);

        String listDeploymentsUrl = credential.endpoint() + "/openai/deployments?api-version=" + credential.apiVersion();
        JsonNode response;
        try {
            response = restClient.get()
                .uri(listDeploymentsUrl)
                .header(API_KEY_HEADER, credential.apiKey())
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            // Upstream body deliberately NOT echoed — with a tenant-controlled
            // endpoint it turns a blind SSRF into a full read. Logged server-side
            // instead. See OllamaProviderValidator for the full reasoning.
            log.warn("Azure OpenAI validation probe returned HTTP {} - body withheld from the caller",
                status.value());
            if (status.value() == 401 || status.value() == 403) {
                throw new ProviderValidationException(
                    "Azure rejected this API key for the given endpoint (HTTP " + status.value() + ")."
                        + " Double-check the key and the endpoint URL.");
            }
            if (status.value() == 404) {
                throw new ProviderValidationException(
                    "Azure returned HTTP 404 for " + credential.endpoint() + " - check the endpoint URL and the "
                        + "api-version (" + credential.apiVersion() + "; try a more recent one if this resource "
                        + "was created after this platform's default).");
            }
            log.warn("Azure OpenAI validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this connection against Azure right now (HTTP " + status.value() + ")."
                    + " Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("Azure OpenAI validation call to {} failed", listDeploymentsUrl, e);
            throw new ProviderValidationException(
                // SEC-023: no transport detail (refused / timeout / reset) - that text is a
                // port-scan oracle. Operators get it from the log line above.
                "Could not reach Azure OpenAI at " + credential.endpoint()
                    + " - check the endpoint URL is correct.", e);
        }

        if (response != null && response.has("data") && response.get("data").isArray()) {
            boolean deploymentFound = false;
            for (JsonNode deployment : response.get("data")) {
                String id = deployment.path("id").asText(null);
                if (credential.deploymentName().equals(id)) {
                    deploymentFound = true;
                    break;
                }
            }
            if (!deploymentFound) {
                throw new ProviderValidationException(
                    "The endpoint and API key are valid, but no deployment named \"" + credential.deploymentName()
                        + "\" exists on this Azure OpenAI resource. Check Azure AI Foundry / the Azure Portal for "
                        + "the exact deployment name (case-sensitive).");
            }
        }
        // If the response didn't carry a recognizable "data" array (a
        // possible future/older Azure API-version response shape this
        // platform hasn't seen), fail open on the deployment-existence
        // check rather than reject a connection that otherwise
        // authenticated successfully - the 401/403 branch above already
        // covers the case that actually matters most (wrong key/endpoint).
    }
}
