package com.agenticai.core.sdlc;

import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.DependencyUnavailableException;
import com.agenticai.core.resilience.ResilienceGuard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Wraps E2B's platform REST API (base {@code https://api.e2b.app}), confirmed live against E2B's
 * own published OpenAPI spec ({@code https://e2b.dev/docs/api-reference/...}) at the time this
 * class was written - not guessed from memory. Auth is a single {@code X-API-Key} header on every
 * call, resolved per-tenant through {@link E2bCredentialService#resolveActiveApiKey()} (never
 * cached across calls, so a rotated/deactivated key takes effect on the very next request - same
 * posture as {@code SarvamTranslateClient}).
 *
 * <p><b>What is implemented here (confirmed simple REST, verified against the live OpenAPI
 * spec):</b>
 *
 * <ul>
 *   <li>{@link #createSandbox} - {@code POST /sandboxes}, creates a sandbox from a template (with
 *       public traffic to its URLs restricted unless {@code app.sdlc.e2b.restrict-public-traffic}
 *       is false) and returns its {@code sandboxID}, the {@code trafficAccessToken} that gates
 *       those URLs, plus (for secure sandboxes) an {@code envdAccessToken}.
 *   <li>{@link #killSandbox} - {@code DELETE /sandboxes/{sandboxID}}, tears a running sandbox down;
 *       {@code 204} on success.
 *   <li>{@link #listSandboxes} - {@code GET /sandboxes} (E2B marks this endpoint deprecated in
 *       favor of {@code GET /v2/sandboxes}, but it is still live and is the cheapest available
 *       auth-check: a bad API key fails this call with {@code 401} before anything is created).
 * </ul>
 *
 * <p><b>What is deliberately NOT implemented here: command/process execution inside a running
 * sandbox.</b> E2B's own API reference groups its endpoints under {@code Sandboxes}, {@code
 * Templates}, {@code Tags}, {@code Volumes}, {@code Envd}, {@code Filesystem}, {@code Process}, and
 * {@code Teams} - the first four are plain REST against {@code api.e2b.app}, served by the
 * platform. {@code Envd}/{@code Filesystem}/ {@code Process}, however, are served by <b>envd</b>,
 * the E2B agent that runs inside each individual sandbox, reached via a per-sandbox host (targeted
 * with {@code E2b-Sandbox-Id}/{@code E2b-Sandbox-Port} headers per the spec's own {@code
 * info.description}) rather than a documented plain-JSON REST body on the public OpenAPI page -
 * E2B's official SDKs talk to it over a Connect-RPC/protobuf wire format, not something this class
 * can respond to with any confidence from the API reference pages alone. Two documentation fetches
 * for the Process service's exact request/ response shape both came back empty in this environment
 * (one direct page fetch, one full-index fetch that timed out), so rather than guess at a shape
 * that would silently fail or - worse - silently succeed against the wrong contract, command
 * execution is left unimplemented. A future stage should either vendor E2B's official Java/Kotlin
 * SDK (if one exists) or capture envd's real wire format with a network trace against a live
 * sandbox before writing this method - not something to fake here.
 */
@Component
public class E2bSandboxClient {

    private static final String BASE_URL = "https://api.e2b.app";
    private static final String API_KEY_HEADER = "X-API-Key";

    /**
     * Inbound-traffic restriction, confirmed against E2B's live OpenAPI spec ({@code
     * https://api.e2b.app/openapi.json}): {@code NewSandbox.network.allowPublicTraffic} (boolean,
     * default true, "Specify if the sandbox URLs should be accessible only with authentication")
     * and {@code Sandbox.trafficAccessToken} (nullable string, "Token required for accessing
     * sandbox via proxy"). Per {@code https://docs.e2b.dev/network/restrict-public-access} the
     * token is sent to the sandbox host as the {@value #TRAFFIC_ACCESS_TOKEN_HEADER} header; a
     * request without it gets {@code 403}. Without the restriction, {@code
     * https://{port}-{sandboxId}.e2b.app} is reachable by anyone who learns the sandbox id.
     */
    private static final String NETWORK_FIELD = "network";

    private static final String ALLOW_PUBLIC_TRAFFIC_FIELD = "allowPublicTraffic";
    private static final String TRAFFIC_ACCESS_TOKEN_FIELD = "trafficAccessToken";
    public static final String TRAFFIC_ACCESS_TOKEN_HEADER = "e2b-traffic-access-token";

    private final RestClient restClient;
    private final E2bCredentialService credentialService;
    private final boolean restrictPublicTraffic;

    /**
     * Optional circuit breaker around sandbox CREATION ({@code
     * app.resilience.circuit-breaker.enabled}, default true; set false for {@link
     * ResilienceGuard#NOOP}, nothing changes). Setter injection so the constructor and every
     * hand-built instance in tests stay as they were.
     */
    private volatile CircuitBreakerRegistry breakerRegistry;

    /**
     * Restricted public traffic (the production default); kept for hand-built instances in tests.
     */
    public E2bSandboxClient(
            RestClient.Builder restClientBuilder, E2bCredentialService credentialService) {
        this(restClientBuilder, credentialService, true);
    }

    /**
     * @param restrictPublicTraffic {@code app.sdlc.e2b.restrict-public-traffic} (env {@code
     *     E2B_RESTRICT_PUBLIC_TRAFFIC}), default true: every sandbox is created with {@code
     *     network.allowPublicTraffic=false} and its URLs answer only with the traffic access token.
     *     {@code false} is the rollback switch and recreates the pre-restriction behaviour (open
     *     sandbox URLs, no token).
     */
    @Autowired
    public E2bSandboxClient(
            RestClient.Builder restClientBuilder,
            E2bCredentialService credentialService,
            @Value("${app.sdlc.e2b.restrict-public-traffic:true}") boolean restrictPublicTraffic) {
        this.restClient = restClientBuilder.build();
        this.credentialService = credentialService;
        this.restrictPublicTraffic = restrictPublicTraffic;
    }

    @Autowired(required = false)
    public void setCircuitBreakerRegistry(CircuitBreakerRegistry registry) {
        this.breakerRegistry = registry;
    }

    private ResilienceGuard guard() {
        CircuitBreakerRegistry registry = breakerRegistry;
        return registry == null ? ResilienceGuard.NOOP : registry.guard("e2b");
    }

    /**
     * @param templateId required - identifier of the E2B template to boot the sandbox from.
     * @param timeoutSeconds time-to-live in seconds before E2B reaps the sandbox; null omits the
     *     field (E2B defaults to 15s - very short, callers should almost always pass an explicit
     *     value).
     * @param envVars environment variables injected into the sandbox process; null/empty omits the
     *     field.
     * @param metadata free-form tags for later {@link #listSandboxes} filtering; null/empty omits
     *     the field.
     */
    public SandboxHandle createSandbox(
            String templateId,
            Integer timeoutSeconds,
            Map<String, String> envVars,
            Map<String, String> metadata) {
        return createSandbox(
                credentialService.resolveActiveApiKey(),
                templateId,
                timeoutSeconds,
                envVars,
                metadata);
    }

    /**
     * Explicit-key form of {@link #createSandbox(String, Integer, Map, Map)}: runs on {@code
     * apiKey} instead of the tenant's stored credential. Used only by the {@code run_code} path,
     * which may run on the platform-managed key (see {@link E2bAccessResolver}).
     */
    public SandboxHandle createSandbox(
            String apiKey,
            String templateId,
            Integer timeoutSeconds,
            Map<String, String> envVars,
            Map<String, String> metadata) {
        ResilienceGuard.Call call;
        try {
            call = guard().begin();
        } catch (DependencyUnavailableException shed) {
            // Same shape as "could not reach E2B" (status 0): callers already map it, the run is
            // given
            // back (E2bSandboxCreateException) and no HTTP call - and no timeout wait - happens.
            throw new E2bApiException(
                    0,
                    "E2B sandbox creation is temporarily unavailable (circuit open after "
                            + "repeated failures) - try again shortly");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("templateID", templateId);
            if (timeoutSeconds != null) {
                body.put("timeout", timeoutSeconds);
            }
            if (envVars != null && !envVars.isEmpty()) {
                body.put("envVars", envVars);
            }
            if (metadata != null && !metadata.isEmpty()) {
                body.put("metadata", metadata);
            }
            if (restrictPublicTraffic) {
                body.put(NETWORK_FIELD, Map.of(ALLOW_PUBLIC_TRAFFIC_FIELD, false));
            }

            JsonNode response =
                    restClient
                            .post()
                            .uri(BASE_URL + "/sandboxes")
                            .header(API_KEY_HEADER, apiKey)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
            call.success();
            String sandboxId = response.path("sandboxID").asString(null);
            String trafficAccessToken = optionalText(response.path(TRAFFIC_ACCESS_TOKEN_FIELD));
            if (restrictPublicTraffic && trafficAccessToken == null) {
                // E2B accepted the restriction but handed back no token that gates the sandbox
                // URLs, so there is no way to tell whether this sandbox is open to anyone who
                // learns its id. Fail closed: tear it down instead of running it. Not a breaker
                // failure - E2B answered, the contract did.
                killQuietly(apiKey, sandboxId);
                throw new E2bApiException(
                        0,
                        "E2B created sandbox "
                                + sandboxId
                                + " with network.allowPublicTraffic=false but returned no "
                                + TRAFFIC_ACCESS_TOKEN_FIELD
                                + "; the sandbox was killed rather than run open. Set "
                                + "app.sdlc.e2b.restrict-public-traffic=false "
                                + "(E2B_RESTRICT_PUBLIC_TRAFFIC) only to roll the restriction "
                                + "back deliberately.");
            }

            return new SandboxHandle(
                    sandboxId,
                    response.path("templateID").asString(null),
                    response.path("envdVersion").asString(null),
                    optionalText(response.path("envdAccessToken")),
                    trafficAccessToken);
        } catch (RestClientResponseException e) {
            // E2B answered. Only its own trouble (5xx) says it is unhealthy. Every 4xx, 408 and 429
            // included, may be this tenant's key/template/concurrency/plan limit (sandboxes also
            // run on a
            // BYOK tenant's own E2B key) and must not trip the breaker shared by every tenant.
            int status = e.getStatusCode().value();
            if (status >= 500) {
                call.failure(e);
            } else {
                call.success();
            }
            throw new E2bApiException(
                    status, "E2B create-sandbox call failed: " + describeError(e));
        } catch (RestClientException e) {
            call.failure(e);
            throw new E2bApiException(
                    0, "Could not reach E2B's create-sandbox endpoint: " + e.getMessage());
        } finally {
            call.close(); // neutral if nothing above settled it
        }
    }

    /**
     * {@code DELETE /sandboxes/{sandboxID}} - 204 on success. A no-op-shaped call if the sandbox
     * already expired/was already killed is still treated as a real failure here (surfaced as
     * {@link E2bApiException}), not swallowed - callers that want idempotent "kill if present"
     * semantics should catch it themselves.
     */
    public void killSandbox(String sandboxId) {
        killSandbox(credentialService.resolveActiveApiKey(), sandboxId);
    }

    /**
     * Explicit-key form of {@link #killSandbox(String)} - must be the same key the sandbox was
     * created with.
     */
    public void killSandbox(String apiKey, String sandboxId) {
        try {
            restClient
                    .delete()
                    .uri(BASE_URL + "/sandboxes/{sandboxID}", sandboxId)
                    .header(API_KEY_HEADER, apiKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new E2bApiException(
                    e.getStatusCode().value(), "E2B kill-sandbox call failed: " + describeError(e));
        } catch (RestClientException e) {
            throw new E2bApiException(
                    0, "Could not reach E2B's kill-sandbox endpoint: " + e.getMessage());
        }
    }

    /**
     * {@code GET /sandboxes} - deprecated by E2B in favor of {@code GET /v2/sandboxes}, but still
     * live; used here both for genuine "what's running" visibility and as the cheapest available
     * credential sanity check (a bad key fails this with 401 before anything is created).
     */
    public List<ListedSandbox> listSandboxes() {
        try {
            JsonNode response =
                    restClient
                            .get()
                            .uri(BASE_URL + "/sandboxes")
                            .header(API_KEY_HEADER, credentialService.resolveActiveApiKey())
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null || !response.isArray()) {
                return List.of();
            }
            return StreamSupport.stream(response.spliterator(), false)
                    .map(
                            node ->
                                    new ListedSandbox(
                                            node.path("sandboxID").asString(null),
                                            node.path("templateID").asString(null),
                                            node.path("state").asString(null),
                                            parseInstant(node.path("startedAt").asString(null)),
                                            parseInstant(node.path("endAt").asString(null))))
                    .toList();
        } catch (RestClientResponseException e) {
            throw new E2bApiException(
                    e.getStatusCode().value(),
                    "E2B list-sandboxes call failed: " + describeError(e));
        } catch (RestClientException e) {
            throw new E2bApiException(
                    0, "Could not reach E2B's list-sandboxes endpoint: " + e.getMessage());
        }
    }

    /**
     * Best-effort teardown of a sandbox this client refuses to hand out; the TTL reaps it
     * otherwise.
     */
    private void killQuietly(String apiKey, String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank()) {
            return;
        }
        try {
            killSandbox(apiKey, sandboxId);
        } catch (RuntimeException e) {
            // The create error below is the one worth surfacing; the sandbox's own TTL is the
            // backstop.
        }
    }

    /** A JSON string field as text, or null when the field is absent, JSON null or blank. */
    private static String optionalText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asString();
        return text == null || text.isBlank() ? null : text;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    static String describeError(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String detail = (body == null || body.isBlank()) ? null : body.trim();
        return "HTTP " + e.getStatusCode().value() + (detail != null ? ": " + detail : "");
    }

    /**
     * Result of {@link #createSandbox} - {@code envdAccessToken} is only non-null when the sandbox
     * was created secure (this scaffold never sets {@code secure: true}, so it is always null
     * today; kept on the record for when a later stage adds that option). {@code
     * trafficAccessToken} is the {@link E2bSandboxClient#TRAFFIC_ACCESS_TOKEN_HEADER} value every
     * request to the sandbox's own host must carry; null only when the client was built with {@code
     * restrictPublicTraffic=false}.
     */
    public record SandboxHandle(
            String sandboxId,
            String templateId,
            String envdVersion,
            String envdAccessToken,
            String trafficAccessToken) {

        /** Pre-restriction shape (no traffic access token); kept for existing callers. */
        public SandboxHandle(
                String sandboxId, String templateId, String envdVersion, String envdAccessToken) {
            this(sandboxId, templateId, envdVersion, envdAccessToken, null);
        }
    }

    public record ListedSandbox(
            String sandboxId, String templateId, String state, Instant startedAt, Instant endAt) {}
}
