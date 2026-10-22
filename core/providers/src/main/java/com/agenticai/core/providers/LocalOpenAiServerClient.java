package com.agenticai.core.providers;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The one place that talks to a {@link ProviderName#LOCAL_OPENAI} server outside a chat turn: it
 * lists {@code GET {root}/v1/models}. Backs the save-time validation ({@code
 * LocalOpenAiProviderValidator}) and the live model dropdown ({@code ProviderModelListingService}),
 * so both see the same SSRF policy and the same parsing.
 *
 * <p><b>SSRF.</b> The base URL is tenant-supplied. It is checked against the process-wide {@link
 * SsrfGuard} before every call, and the HTTP client is the address-pinned, proxy-free,
 * redirect-free one from {@link SafeHttpClients} built on the same guard, so the host that was
 * checked is the host connected to and a 3xx cannot carry the bearer key elsewhere. A loopback or
 * private host (the normal case for a local server) is permitted only when the operator lists it in
 * {@code app.security.outbound.allowed-private-hosts}; the refusal says exactly which entry to add,
 * and mentions {@code host.docker.internal} for a platform running in a container. The upstream
 * response body is never echoed to the caller.
 *
 * <p>The body is read through a hard size cap, so a hostile "model server" cannot make the platform
 * buffer an unbounded response.
 */
@Component
public class LocalOpenAiServerClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final RestClient restClient;
    private final SsrfGuard ssrfGuard;
    private final ObjectMapper mapper = JsonMappers.lenient();

    /** One model a server reported; {@code contextWindow} is null when the server does not say. */
    public record ServerModel(String id, Integer contextWindow) {}

    /**
     * What {@code /v1/models} answered. {@code wellFormed} is false when a 2xx body was not an
     * OpenAI-style model list (a web page, a proxy error page): the URL points at something, but
     * not at a model server.
     */
    public record Listing(int status, boolean wellFormed, List<ServerModel> models) {
        public boolean ok() {
            return status >= 200 && status < 300 && wellFormed;
        }
    }

    @Autowired
    public LocalOpenAiServerClient(RestClient.Builder restClientBuilder, SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
        this.restClient =
                restClientBuilder
                        .clone()
                        .requestFactory(
                                SafeHttpClients.requestFactory(ssrfGuard, TIMEOUT, TIMEOUT, false))
                        .build();
    }

    /**
     * Refuses a base URL the SSRF guard does not allow.
     *
     * @throws ProviderValidationException (HTTP 400) with the guard's caller-safe reason and the
     *     operator allow-list entry that would permit the server
     */
    public void requireAllowed(LocalOpenAiCredential credential) {
        if (credential.baseUrl() == null || credential.baseUrl().isBlank()) {
            throw new ProviderValidationException(
                    "A base URL is required (for example http://localhost:8000/v1 for vLLM).");
        }
        try {
            ssrfGuard.validate(credential.apiRoot());
        } catch (SsrfBlockedException e) {
            throw new ProviderValidationException(refusalMessage(credential, e.getMessage()));
        }
    }

    /**
     * Lists the server's models.
     *
     * @throws ProviderValidationException when the SSRF guard refuses the URL
     * @throws org.springframework.web.client.RestClientException on a transport failure (callers
     *     decide how much of it to show; it is a port-scan oracle and must not reach a tenant)
     */
    public Listing listModels(LocalOpenAiCredential credential) {
        requireAllowed(credential);
        return restClient
                .get()
                .uri(URI.create(credential.modelsUrl()))
                .headers(
                        headers -> {
                            if (credential.hasApiKey()) {
                                headers.setBearerAuth(credential.apiKey().trim());
                            }
                        })
                .exchange(
                        (request, response) -> {
                            int status = response.getStatusCode().value();
                            if (status < 200 || status >= 300) {
                                return new Listing(status, false, List.of());
                            }
                            byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES);
                            return parse(status, body);
                        });
    }

    Listing parse(int status, byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            return new Listing(status, false, List.of());
        }
        // OpenAI shape is {"data":[...]}; some llama.cpp builds also/only send {"models":[...]}.
        JsonNode array =
                root == null
                        ? null
                        : (root.path("data").isArray() ? root.path("data") : root.path("models"));
        if (array == null || !array.isArray()) {
            return new Listing(status, false, List.of());
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ServerModel> models = new ArrayList<>();
        for (JsonNode node : array) {
            String id = firstText(node, "id", "model", "name");
            if (id == null || !seen.add(id)) {
                continue;
            }
            models.add(new ServerModel(id, contextWindow(node)));
        }
        return new Listing(status, true, models);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** vLLM reports {@code max_model_len}; llama.cpp {@code meta.n_ctx_train}; others nothing. */
    private static Integer contextWindow(JsonNode node) {
        for (JsonNode candidate :
                new JsonNode[] {
                    node.path("max_model_len"),
                    node.path("meta").path("n_ctx_train"),
                    node.path("context_length")
                }) {
            if (candidate.isIntegralNumber()
                    && candidate.asLong() > 0
                    && candidate.asLong() <= Integer.MAX_VALUE) {
                return candidate.asInt();
            }
        }
        return null;
    }

    static String refusalMessage(LocalOpenAiCredential credential, String reason) {
        StringBuilder message =
                new StringBuilder("This base URL is not allowed: ").append(reason).append(". ");
        String entry = credential.allowListEntry();
        message.append(
                "Local and private model servers are reachable only when the platform operator lists them");
        if (entry != null) {
            message.append(": add ").append(entry);
        }
        message.append(
                " to app.security.outbound.allowed-private-hosts (APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS).");
        if (credential.pointsAtLoopback()) {
            message.append(
                            " If the platform runs in Docker, localhost is the container itself: use the host's address, ")
                    .append(
                            "for example http://host.docker.internal:<port>/v1, and allow-list that host:port instead.");
        }
        return message.toString();
    }
}
