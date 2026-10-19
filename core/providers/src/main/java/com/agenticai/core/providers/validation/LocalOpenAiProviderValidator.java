package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.LocalOpenAiCredential;
import com.agenticai.core.providers.LocalOpenAiServerClient;
import com.agenticai.core.providers.ProviderName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Validates a {@link ProviderName#LOCAL_OPENAI} connection by decoding the {@link
 * LocalOpenAiCredential} blob and listing {@code GET {base}/v1/models} through {@link
 * LocalOpenAiServerClient}: free, non-generating, and implemented by every server this provider is
 * for (vLLM, llama.cpp, LM Studio, Jan, LocalAI, proxies).
 *
 * <p>An empty model list is accepted: LM Studio and LocalAI load models on demand and may list
 * nothing until the first request, and the server is demonstrably there. A 2xx that is not a model
 * list (a web UI at that address) is refused. As for Ollama, neither the upstream body nor transport
 * detail reaches the caller (a port-scan oracle); both are logged for the operator.
 */
@Component
public class LocalOpenAiProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(LocalOpenAiProviderValidator.class);

    private final LocalOpenAiServerClient serverClient;

    public LocalOpenAiProviderValidator(LocalOpenAiServerClient serverClient) {
        this.serverClient = serverClient;
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.LOCAL_OPENAI;
    }

    @Override
    public void validate(String apiKey) {
        LocalOpenAiCredential credential;
        try {
            credential = LocalOpenAiCredential.decode(apiKey);
        } catch (IllegalStateException e) {
            throw new ProviderValidationException(
                "Could not read the server connection details - this field should be filled in via the "
                    + "activation form, not pasted by hand.", e);
        }

        LocalOpenAiServerClient.Listing listing;
        try {
            listing = serverClient.listModels(credential);
        } catch (RestClientException e) {
            log.warn("Local OpenAI-compatible validation probe to {} failed", credential.modelsUrl(), e);
            throw new ProviderValidationException(
                "Could not reach the model server at " + credential.baseUrl() + " - check the base URL, that the "
                    + "server is running, and that it is reachable from this platform (from inside Docker, "
                    + "localhost is the container: use host.docker.internal).", e);
        }

        int status = listing.status();
        if (status == 401 || status == 403) {
            throw new ProviderValidationException(
                "The server at " + credential.baseUrl() + " rejected the request (HTTP " + status + "). "
                    + (credential.hasApiKey()
                        ? "Check the API key is the one the server was started with."
                        : "This server requires an API key - enter it in the optional key field."));
        }
        if (!listing.ok()) {
            log.warn("Local OpenAI-compatible validation probe to {} returned HTTP {} (well-formed list: {}) - body withheld",
                credential.modelsUrl(), status, listing.wellFormed());
            if (status >= 200 && status < 300) {
                throw new ProviderValidationException(
                    "The address " + credential.baseUrl() + " answered, but not like an OpenAI-compatible server "
                        + "(GET /v1/models did not return a model list). Check the port and the /v1 path.");
            }
            throw new ProviderValidationException(
                "The server at " + credential.baseUrl() + " responded with HTTP " + status
                    + " to GET /v1/models. Check the base URL, and that this is an OpenAI-compatible endpoint.");
        }
    }
}
