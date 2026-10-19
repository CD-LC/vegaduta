package com.agenticai.core.agents.tools.openapi;

import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.USER_AGENT;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.appendQuery;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.closeQuietly;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.encodeComponent;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.errorJson;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.hostOf;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.parseInput;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.readCapped;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.redact;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.serializeBody;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.stringify;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.toolRequest;
import static com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.tryParseJson;

import com.agenticai.core.agents.tools.openapi.OpenApiHttpSupport.PinnedResponse;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns one {@link OpenApiTool} call back into the HTTP request its operation describes, sends it,
 * and returns a JSON result the model can read - plus the one-off GET that fetches a spec document
 * at import time. Everything security-relevant about calling a tenant-configured API from an
 * LLM-steered tool call lives here:
 *
 * <ul>
 *   <li><b>SSRF</b> - the fully assembled URL (base + path + query) is run through {@link
 *       OpenApiUrlGuard} on EVERY call, after DNS resolution, and redirects are never followed for
 *       tool calls (an API that 302s an authenticated request is not one we forward a credential
 *       to). Path-parameter values are percent-encoded, so an LLM-supplied {@code ../} or {@code #}
 *       cannot escape the operation's path.
 *   <li><b>Credentials</b> - decrypted from the source's ciphertext only here, only for the
 *       duration of the request, and scrubbed from the response text and every error message before
 *       either reaches the model. A {@code {{secret.NAME}}} value is resolved through the tenant
 *       vault (workspace-scoped, same as MCP server env) instead.
 *   <li><b>Cost</b> - 10s connect / 30s request timeouts, response read capped at {@value
 *       #MAX_RESPONSE_BYTES} bytes and clipped again to {@value #MAX_RESULT_CHARS} chars: a tool
 *       result re-enters the prompt on every subsequent turn.
 * </ul>
 *
 * <p>Both injected collaborators are {@link ObjectProvider}s for the same reason {@code
 * CustomCodeToolService} holds its vault that way: they exist in the real app (core/crypto and
 * core/secrets are on the classpath), but a slim unit-test context need not provide them, and an
 * unauthenticated source never touches either.
 */
@Component
public class OpenApiToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToolExecutor.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final int MAX_RESULT_CHARS = 16_000;
    private static final int MAX_SPEC_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final CloseableHttpClient httpClient;
    private final SsrfGuard guard;
    private final ObjectProvider<EncryptionService> encryptionService;
    private final ObjectProvider<SecretReferenceResolver> secretReferenceResolver;

    // Access tokens minted for OAUTH2_CLIENT_CREDENTIALS sources, keyed by
    // source id - in-memory only (a token is a credential; it does not belong
    // in a table). Availity tokens live five minutes, so without this every
    // tool call would be two HTTP calls.
    private final Map<UUID, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private record CachedToken(String token, long expiresAtEpochMillis) {}

    public OpenApiToolExecutor(
            ObjectProvider<EncryptionService> encryptionService,
            ObjectProvider<SecretReferenceResolver> secretReferenceResolver) {
        this(encryptionService, secretReferenceResolver, SsrfGuard.defaultGuard());
    }

    /**
     * SEC-027: every request (spec fetch, token call, tool call) goes through a {@link
     * SafeHttpClients} client built on {@code guard} - resolved and validated at connect time,
     * connected only to a validated address (no DNS-rebinding window between {@link
     * OpenApiUrlGuard}'s check and the socket), no system proxy, redirects never followed by the
     * client.
     */
    @Autowired
    public OpenApiToolExecutor(
            ObjectProvider<EncryptionService> encryptionService,
            ObjectProvider<SecretReferenceResolver> secretReferenceResolver,
            SsrfGuard guard) {
        this.encryptionService = encryptionService;
        this.secretReferenceResolver = secretReferenceResolver;
        this.guard = guard;
        this.httpClient = SafeHttpClients.create(guard, CONNECT_TIMEOUT, REQUEST_TIMEOUT, false);
    }

    // ------------------------------------------------------------------
    // Spec fetch (import / refresh time)
    // ------------------------------------------------------------------

    /**
     * GETs an OpenAPI document. Redirects are followed manually (max {@value #MAX_SPEC_REDIRECTS}
     * hops) with the full public-address check re-run on every hop, exactly like {@code
     * WebPageFetcher}. Throws {@link OpenApiToolException} with a person-readable reason on every
     * failure path.
     */
    public String fetchSpecText(String rawUrl) {
        URI current = OpenApiUrlGuard.validatePublicHttpUri(rawUrl, guard);
        for (int hop = 0; hop <= MAX_SPEC_REDIRECTS; hop++) {
            PinnedResponse response;
            try {
                HttpGet request = new HttpGet(current);
                request.addHeader("User-Agent", USER_AGENT);
                request.addHeader(
                        "Accept",
                        "application/json, application/yaml, application/x-yaml, text/yaml, text/plain;q=0.8, */*;q=0.5");
                response = send(request);
            } catch (SsrfGuard.BlockedAddressException e) {
                throw new OpenApiToolException("the URL points inside the platform's own network");
            } catch (IOException e) {
                log.debug("OpenAPI spec fetch could not reach {}: {}", current, e.toString());
                throw new OpenApiToolException("The spec URL could not be reached.");
            } catch (IllegalArgumentException e) {
                throw new OpenApiToolException("The spec URL is not a valid web address.");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response);
                String location = response.header("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new OpenApiToolException(
                            "The spec URL redirected without saying where to.");
                }
                String resolved;
                try {
                    resolved = current.resolve(location.strip()).toString();
                } catch (IllegalArgumentException e) {
                    throw new OpenApiToolException(
                            "The spec URL redirected to an invalid address.");
                }
                current = OpenApiUrlGuard.validatePublicHttpUri(resolved, guard);
                continue;
            }
            if (status != 200) {
                closeQuietly(response);
                throw new OpenApiToolException("The spec URL returned HTTP " + status + ".");
            }
            byte[] raw;
            try {
                raw = readCapped(response.body(), OpenApiSpecParser.MAX_SPEC_CHARS + 1);
            } catch (IOException e) {
                // The send() above is guarded, but draining the body is a second,
                // separately-failing network read - a connection reset mid-download
                // lands here, not there. This method's javadoc promises an
                // OpenApiToolException on every failure path, so translate rather
                // than letting a checked IOException escape an undeclared throws.
                closeQuietly(response);
                log.debug("OpenAPI spec body could not be read from {}: {}", current, e.toString());
                throw new OpenApiToolException("The spec URL could not be read to the end.");
            }
            if (raw.length > OpenApiSpecParser.MAX_SPEC_CHARS) {
                throw new OpenApiToolException("The OpenAPI document is larger than 1 MB.");
            }
            return new String(raw, StandardCharsets.UTF_8);
        }
        throw new OpenApiToolException(
                "The spec URL redirected more than " + MAX_SPEC_REDIRECTS + " times.");
    }

    // ------------------------------------------------------------------
    // Tool execution (agent turn / console test)
    // ------------------------------------------------------------------

    /**
     * Executes {@code tool} with the model's JSON input. Never throws for an API-side problem - a
     * non-2xx status is a normal result the model should see ({@code {"status": 404, "body":
     * ...}}); platform-side refusals (SSRF, missing credential, unreachable host) come back as
     * {@code {"error": ...}}. Only programming errors escape, and {@link OpenApiToolCallback}
     * catches those too.
     */
    public String execute(OpenApiTool tool, String toolInputJson) {
        OpenApiToolSource source = tool.getSource();
        String secret = null;
        String bearerToken = null;
        try {
            ObjectNode input = parseInput(toolInputJson);
            JsonNode parameters =
                    MAPPER.readTree(
                            StringUtils.hasText(tool.getParametersJson())
                                    ? tool.getParametersJson()
                                    : "[]");

            String path = tool.getPathTemplate();
            StringBuilder query = new StringBuilder();
            Map<String, String> headers = new LinkedHashMap<>();
            Set<String> consumed = new HashSet<>();

            for (JsonNode p : parameters) {
                String name = p.path("name").asText("");
                String in = p.path("in").asText("");
                boolean required = p.path("required").asBoolean(false);
                JsonNode value = input.get(name);
                boolean present = value != null && !value.isNull();
                switch (in) {
                    case "path" -> {
                        if (!present) {
                            return errorJson(
                                    "Missing required path parameter \"" + name + "\".", null);
                        }
                        path = path.replace("{" + name + "}", encodeComponent(stringify(value)));
                        consumed.add(name);
                    }
                    case "query" -> {
                        if (!present) {
                            if (required) {
                                return errorJson(
                                        "Missing required query parameter \"" + name + "\".", null);
                            }
                            continue;
                        }
                        if (value.isArray()) {
                            for (JsonNode element : value) {
                                appendQuery(query, name, stringify(element));
                            }
                        } else {
                            appendQuery(query, name, stringify(value));
                        }
                        consumed.add(name);
                    }
                    case "header" -> {
                        if (!present) {
                            if (required) {
                                return errorJson(
                                        "Missing required header parameter \"" + name + "\".",
                                        null);
                            }
                            continue;
                        }
                        headers.put(name, stringify(value));
                        consumed.add(name);
                    }
                    default -> {
                        // cookie / unknown - never exposed by the parser.
                    }
                }
            }
            if (path.contains("{")) {
                return errorJson(
                        "The operation path \""
                                + tool.getPathTemplate()
                                + "\" has a parameter the tool input did not supply.",
                        null);
            }

            String contentType =
                    StringUtils.hasText(tool.getBodyContentType())
                            ? tool.getBodyContentType()
                            : "application/json";
            String body = null;
            if (tool.getBodyMode() == OpenApiBodyMode.FLATTENED) {
                ObjectNode bodyNode = MAPPER.createObjectNode();
                Iterator<Map.Entry<String, JsonNode>> it = JsonNodes.fields(input);
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> field = it.next();
                    if (!consumed.contains(field.getKey())) {
                        bodyNode.set(field.getKey(), field.getValue());
                    }
                }
                if (!bodyNode.isEmpty() || !"GET".equalsIgnoreCase(tool.getHttpMethod())) {
                    body = serializeBody(bodyNode, contentType);
                }
            } else if (tool.getBodyMode() == OpenApiBodyMode.WRAPPED) {
                JsonNode bodyNode = input.get("body");
                if (bodyNode != null && !bodyNode.isNull()) {
                    body = serializeBody(bodyNode, contentType);
                }
            }

            String base = source.getBaseUrl() == null ? "" : source.getBaseUrl().strip();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            String fullUrl = base + path + (query.length() > 0 ? "?" + query : "");
            URI uri = OpenApiUrlGuard.validatePublicHttpUri(fullUrl, guard);

            boolean oauth2 = source.getAuthType() == OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS;
            // SEC-006: a vault reference in the credential resolves only for the
            // host the credential is actually sent to (token endpoint for OAuth2
            // client credentials, the API host otherwise).
            secret =
                    resolveSecret(
                            source, oauth2 ? hostOf(source.getOauth2TokenUrl()) : uri.getHost());
            if (oauth2) {
                bearerToken = obtainAccessToken(source, secret);
            }

            String method = tool.getHttpMethod().toUpperCase(Locale.ROOT);
            PinnedResponse response =
                    send(
                            toolRequest(
                                    uri,
                                    headers,
                                    secret,
                                    bearerToken,
                                    source,
                                    contentType,
                                    method,
                                    body));
            int status = response.statusCode();
            if (status == 401 && oauth2) {
                // The cached token outlived its welcome (clock skew, a revoked
                // grant, an endpoint minting a shorter life than advertised) -
                // evict, re-authenticate, and replay the identical request
                // exactly once. Anything but refetch+resend would hand the
                // model a failure it can do nothing about.
                closeQuietly(response);
                tokenCache.remove(source.getId());
                bearerToken = obtainAccessToken(source, secret);
                response =
                        send(
                                toolRequest(
                                        uri,
                                        headers,
                                        secret,
                                        bearerToken,
                                        source,
                                        contentType,
                                        method,
                                        body));
                status = response.statusCode();
            }
            byte[] raw = readCapped(response.body(), MAX_RESPONSE_BYTES);
            String text = new String(raw, StandardCharsets.UTF_8);
            boolean truncated = false;
            if (text.length() > MAX_RESULT_CHARS) {
                text = text.substring(0, MAX_RESULT_CHARS);
                truncated = true;
            }
            String responseContentType = response.header("Content-Type").orElse("");

            ObjectNode out = MAPPER.createObjectNode();
            out.put("status", status);
            if (status >= 400) {
                out.put("error", "The API returned HTTP " + status + ".");
            }
            JsonNode parsedBody = tryParseJson(text, responseContentType, truncated);
            if (parsedBody != null) {
                out.set("body", parsedBody);
            } else {
                out.put("body", text);
            }
            if (truncated) {
                out.put("truncated", true);
            }
            return redact(out.toString(), secret, bearerToken);
        } catch (OpenApiToolException e) {
            return errorJson(e.getMessage(), secret, bearerToken);
        } catch (SsrfGuard.BlockedAddressException e) {
            // The guard's resolver refused at connect time (DNS changed since the check).
            return errorJson(
                    "the URL points inside the platform's own network", secret, bearerToken);
        } catch (IOException e) {
            log.debug(
                    "OpenAPI tool {} could not reach its API: {}",
                    tool.getToolName(),
                    e.toString());
            return errorJson("The API could not be reached.", secret, bearerToken);
        } catch (IllegalArgumentException e) {
            // HttpRequest.Builder rejects restricted header names and some URIs.
            return errorJson(
                    "The request could not be built: " + e.getMessage(), secret, bearerToken);
        }
    }

    /**
     * One request through the guarded client. Throws {@link SsrfGuard.BlockedAddressException} (an
     * {@link IOException}) when the guard's resolver refuses the host at connect time.
     */
    private PinnedResponse send(HttpUriRequestBase request) throws IOException {
        return new PinnedResponse(request, httpClient.executeOpen(null, request, null));
    }

    // ------------------------------------------------------------------
    // OAuth2 client-credentials token
    // ------------------------------------------------------------------

    /**
     * Fetches (or reuses) a short-lived access token for an {@code OAUTH2_CLIENT_CREDENTIALS}
     * source - {@code clientSecret} is the already-decrypted stored credential. Cached until just
     * before the endpoint's advertised {@code expires_in}; the cache is keyed by source id only, so
     * a rotated credential lands under the same id - the retry-on-401 in {@link #execute} is what
     * evicts the stale entry.
     *
     * <p>Two token-request body styles exist in the wild and both are supported via the source's
     * {@code oauth2TokenStyle}: {@code FORM} (application/x-www-form-urlencoded - Availity {@code
     * /v1/token}, RFC 6749's canonical shape) and {@code JSON} (application/json - Optum {@code
     * /apip/auth/v2/token}). The token endpoint URL is run through {@link OpenApiUrlGuard} on every
     * fetch, same as the tool URL.
     */
    private String obtainAccessToken(OpenApiToolSource source, String clientSecret) {
        long now = System.currentTimeMillis();
        CachedToken cached = tokenCache.get(source.getId());
        if (cached != null && cached.expiresAtEpochMillis() > now) {
            return cached.token();
        }
        if (!StringUtils.hasText(source.getOauth2TokenUrl())
                || !StringUtils.hasText(source.getOauth2ClientId())
                || !StringUtils.hasText(clientSecret)) {
            throw new OpenApiToolException(
                    "The \""
                            + source.getName()
                            + "\" API source is missing its OAuth2 "
                            + "token URL, client id or client secret - re-enter them in Tools.");
        }
        URI tokenUri = OpenApiUrlGuard.validatePublicHttpUri(source.getOauth2TokenUrl(), guard);

        TokenRequestBody tokenBody = buildTokenRequestBody(source, clientSecret);
        HttpPost tokenRequest = new HttpPost(tokenUri);
        tokenRequest.addHeader("User-Agent", USER_AGENT);
        tokenRequest.addHeader("Accept", "application/json");
        tokenRequest.addHeader("Content-Type", tokenBody.contentType());
        tokenRequest.setEntity(new StringEntity(tokenBody.body(), StandardCharsets.UTF_8));

        PinnedResponse response;
        try {
            response = send(tokenRequest);
        } catch (SsrfGuard.BlockedAddressException e) {
            throw new OpenApiToolException("the URL points inside the platform's own network");
        } catch (IOException e) {
            log.debug(
                    "OAuth2 token endpoint {} unreachable for OpenAPI source {}: {}",
                    tokenUri,
                    source.getId(),
                    e.toString());
            throw new OpenApiToolException("The API's token endpoint could not be reached.");
        }
        int status = response.statusCode();
        String text;
        try {
            text =
                    new String(
                            readCapped(response.body(), MAX_RESPONSE_BYTES),
                            StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OpenApiToolException("The token endpoint's response could not be read.");
        }
        if (status != 200) {
            // The error body often says WHY (unknown client, bad scope) - log
            // it with the secret scrubbed, but let only the status travel to
            // the caller and onward to the model.
            log.debug(
                    "OAuth2 token request for OpenAPI source {} failed with HTTP {}: {}",
                    source.getId(),
                    status,
                    redact(text, clientSecret));
            throw new OpenApiToolException(
                    "The API's token endpoint rejected the client credentials (HTTP "
                            + status
                            + ") - check the client id, client secret and scope in Tools.");
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new OpenApiToolException("The token endpoint did not return JSON.");
        }
        String token = parsed.path("access_token").asText("");
        if (!StringUtils.hasText(token)) {
            throw new OpenApiToolException(
                    "The token endpoint's response contained no access_token.");
        }
        // expires_in is advisory in practice - trust it minus a 10%/60s skew,
        // and trust nothing when absent (default five minutes, like Availity).
        long expiresIn = parsed.path("expires_in").asLong(300);
        long skew = Math.min(60, Math.max(0, expiresIn / 10));
        tokenCache.put(
                source.getId(), new CachedToken(token, now + Math.max(0, expiresIn - skew) * 1000));
        return token;
    }

    record TokenRequestBody(String body, String contentType) {}

    /**
     * Builds the token-endpoint request body in the source's configured style. Package-private and
     * static so the unit tests can assert the wire format without a reachable endpoint - the URL
     * guard (correctly) refuses localhost token URLs, so the HTTP leg cannot be exercised in tests.
     */
    static TokenRequestBody buildTokenRequestBody(OpenApiToolSource source, String clientSecret) {
        if ("JSON".equalsIgnoreCase(source.getOauth2TokenStyle())) {
            ObjectNode tokenBody = MAPPER.createObjectNode();
            tokenBody.put("grant_type", "client_credentials");
            tokenBody.put("client_id", source.getOauth2ClientId());
            tokenBody.put("client_secret", clientSecret);
            if (StringUtils.hasText(source.getOauth2Scope())) {
                tokenBody.put("scope", source.getOauth2Scope());
            }
            return new TokenRequestBody(tokenBody.toString(), "application/json");
        }
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        appendQuery(form, "client_id", source.getOauth2ClientId());
        appendQuery(form, "client_secret", clientSecret);
        if (StringUtils.hasText(source.getOauth2Scope())) {
            appendQuery(form, "scope", source.getOauth2Scope());
        }
        return new TokenRequestBody(form.toString(), "application/x-www-form-urlencoded");
    }

    // ------------------------------------------------------------------
    // Credentials
    // ------------------------------------------------------------------

    /**
     * The ONE decrypt path for a source's credential. Returns {@code null} for an unauthenticated
     * source. A {@code {{secret.NAME}}} reference is expanded through the vault with no user id -
     * i.e. workspace-scoped secrets only, the same rule MCP server env expansion applies - so an
     * imported API can never act with one member's personal credential on another member's behalf.
     */
    String resolveSecret(OpenApiToolSource source, String destinationHost) {
        if (source.getAuthType() == null || source.getAuthType() == OpenApiAuthType.NONE) {
            return null;
        }
        if (!source.hasSecret()) {
            throw new OpenApiToolException(
                    "No credential is stored for the \""
                            + source.getName()
                            + "\" API source - add one in Tools.");
        }
        EncryptionService encryption = encryptionService.getIfAvailable();
        if (encryption == null) {
            throw new OpenApiToolException(
                    "Credential encryption is not configured on this deployment.");
        }
        String plaintext;
        try {
            plaintext = encryption.decrypt(source.getEncryptedSecret());
        } catch (RuntimeException e) {
            // Almost always an APP_ENCRYPTION_KEY mismatch between environments.
            log.error(
                    "Failed to decrypt the credential of OpenAPI source {} - check APP_ENCRYPTION_KEY",
                    source.getId(),
                    e);
            throw new OpenApiToolException(
                    "The stored credential could not be decrypted - re-enter it in Tools.");
        }
        SecretReferenceResolver resolver = secretReferenceResolver.getIfAvailable();
        if (resolver != null && resolver.containsReference(plaintext)) {
            SecretReferenceResolver.Resolution resolution =
                    destinationHost == null
                            ? null
                            : resolver.resolveForDestination(plaintext, null, destinationHost);
            if (resolution == null || resolution.hasUnresolved()) {
                // One message for missing, revoked and not-allowed-for-this-host
                // (SEC-006): no secret-name enumeration.
                throw new OpenApiToolException(
                        "A referenced secret is not available for this destination - allow the API's host on the "
                                + "workspace secret in the vault.");
            }
            plaintext = resolution.value();
        }
        return plaintext;
    }
}
