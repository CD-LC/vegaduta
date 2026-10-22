package com.agenticai.core.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Discovery half of the MCP authorization spec:
 *
 * <ol>
 *   <li><b>RFC 9728</b> protected-resource metadata - located via the {@code resource_metadata}
 *       parameter of a 401's {@code WWW-Authenticate} header when present, else the {@code
 *       /.well-known/oauth-protected-resource} well-known URIs.
 *   <li><b>RFC 8414</b> authorization-server metadata (with the OpenID Connect discovery fallback)
 *       for the first advertised authorization server.
 * </ol>
 *
 * Every fetch goes through {@link McpOAuthHttp} (SSRF-guarded in production), and every endpoint
 * URL in the returned metadata is required to be https. PKCE S256 support must be advertised by the
 * authorization server, as the MCP spec requires clients to refuse otherwise.
 */
@Component
public class McpOAuthDiscovery {

    private static final Pattern RESOURCE_METADATA_PARAM =
            Pattern.compile("resource_metadata\\s*=\\s*\"([^\"]+)\"");
    // Anchored so it matches the challenge's own `scope="..."` and not the tail of another
    // parameter name.
    private static final Pattern SCOPE_PARAM =
            Pattern.compile("(?:^|[\\s,])scope\\s*=\\s*\"([^\"]+)\"");

    private final McpOAuthHttp http;
    private final ObjectMapper objectMapper;

    public McpOAuthDiscovery(McpOAuthHttp http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    /**
     * RFC 9728 document, reduced to what the flow needs.
     *
     * @param challengeScope the {@code scope} parameter of the 401 {@code WWW-Authenticate}
     *     challenge, or null
     */
    public record ProtectedResource(
            String resource,
            String authorizationServer,
            List<String> scopesSupported,
            String challengeScope) {
        public ProtectedResource(
                String resource, String authorizationServer, List<String> scopesSupported) {
            this(resource, authorizationServer, scopesSupported, null);
        }

        /**
         * The scope set the platform may ask for (scope minimisation): the challenge's own scope
         * when the server sent one, otherwise the resource's {@code scopes_supported}, otherwise
         * nothing. Never anything the caller made up.
         */
        public List<String> allowedScopes() {
            if (challengeScope != null && !challengeScope.isBlank()) {
                return List.of(challengeScope.trim().split("\\s+"));
            }
            return scopesSupported == null ? List.of() : scopesSupported;
        }
    }

    /**
     * RFC 8414 document, reduced to what the flow needs.
     *
     * @param clientIdMetadataDocumentSupported {@code client_id_metadata_document_supported} (OAuth
     *     Client ID Metadata Documents)
     * @param issParameterSupported {@code authorization_response_iss_parameter_supported} (RFC
     *     9207)
     */
    public record AuthServerMetadata(
            String issuer,
            String authorizationEndpoint,
            String tokenEndpoint,
            String registrationEndpoint,
            List<String> scopesSupported,
            List<String> tokenEndpointAuthMethodsSupported,
            boolean clientIdMetadataDocumentSupported,
            boolean issParameterSupported) {
        public AuthServerMetadata(
                String issuer,
                String authorizationEndpoint,
                String tokenEndpoint,
                String registrationEndpoint,
                List<String> scopesSupported,
                List<String> tokenEndpointAuthMethodsSupported) {
            this(
                    issuer,
                    authorizationEndpoint,
                    tokenEndpoint,
                    registrationEndpoint,
                    scopesSupported,
                    tokenEndpointAuthMethodsSupported,
                    false,
                    false);
        }
    }

    public record Discovered(ProtectedResource resource, AuthServerMetadata authServer) {}

    /**
     * What an unauthenticated request to the MCP endpoint reveals.
     *
     * @param status HTTP status, or -1 when the endpoint could not be reached
     * @param resourceMetadataUrl the {@code resource_metadata} parameter of a 401 challenge, or
     *     null
     * @param challengeScope the {@code scope} parameter of a 401 challenge, or null
     */
    public record Probe(int status, String resourceMetadataUrl, String challengeScope) {
        /**
         * True only for a 401: the server asks for credentials. Anything else means no OAuth
         * sign-in is demanded.
         */
        public boolean authRequired() {
            return status == 401;
        }
    }

    /** Full discovery for an MCP server URL. @throws McpOAuthException on any failure. */
    public Discovered discover(String mcpServerUrl) {
        ProtectedResource resource = discoverProtectedResource(mcpServerUrl);
        AuthServerMetadata as = discoverAuthServer(resource.authorizationServer());
        return new Discovered(resource, as);
    }

    /**
     * Unauthenticated probe of the MCP endpoint (SSRF-guarded like every other call). POST, not
     * GET: a GET against a streamable-HTTP/SSE endpoint can hold the connection open indefinitely,
     * and the body read would block. An unauthenticated POST gets its 401 immediately.
     *
     * @throws McpOAuthBlockedException the guard refused the URL (must surface, never be swallowed)
     */
    public Probe probe(String mcpServerUrl) {
        requireHttps(mcpServerUrl, "MCP server URL");
        refuseIfBlocked(mcpServerUrl, "MCP server URL");
        try {
            McpOAuthHttp.Response response =
                    http.send(
                            "POST",
                            mcpServerUrl,
                            Map.of(
                                    "Content-Type",
                                    "application/json",
                                    "Accept",
                                    "application/json, text/event-stream"),
                            "{}");
            String challenge =
                    response.status() == 401 ? response.header("www-authenticate") : null;
            return new Probe(
                    response.status(),
                    challengeParam(challenge, RESOURCE_METADATA_PARAM),
                    challengeParam(challenge, SCOPE_PARAM));
        } catch (McpOAuthBlockedException e) {
            throw e;
        } catch (McpOAuthException e) {
            return new Probe(-1, null, null);
        }
    }

    public ProtectedResource discoverProtectedResource(String mcpServerUrl) {
        requireHttps(mcpServerUrl, "MCP server URL");
        List<String> candidates = new ArrayList<>();

        // 1. A 401 challenge's resource_metadata parameter is authoritative when the server sends
        // one.
        // A guard rejection surfaces as-is, not swallowed into a misleading "no metadata" message;
        // an
        // unreachable probe falls through to the well-known fallbacks, which report their own
        // failure.
        Probe probe = probe(mcpServerUrl);
        if (probe.resourceMetadataUrl() != null) {
            candidates.add(probe.resourceMetadataUrl());
        }
        // 2. Well-known fallbacks: path-aware first, then host root.
        candidates.add(wellKnown(mcpServerUrl, "oauth-protected-resource", true));
        candidates.add(wellKnown(mcpServerUrl, "oauth-protected-resource", false));

        McpOAuthException last = null;
        for (String candidate : candidates.stream().distinct().toList()) {
            try {
                JsonNode doc = fetchJson(candidate);
                String resource = text(doc, "resource");
                if (resource == null) {
                    throw new McpOAuthException(
                            "Protected-resource metadata at " + candidate + " has no 'resource'.");
                }
                // RFC 8707 audience binding: the token will be requested for `resource`, so the
                // document may
                // only name a resource that actually covers the server the tenant typed. A server
                // that
                // advertises a different host/path is substituting metadata - refuse.
                if (!McpOAuthUrls.covers(resource, mcpServerUrl)) {
                    throw new McpOAuthException(
                            "Protected-resource metadata 'resource' ("
                                    + resource
                                    + ") does not cover the MCP server URL - refusing (possible metadata substitution).");
                }
                List<String> servers = stringList(doc, "authorization_servers");
                if (servers.isEmpty()) {
                    throw new McpOAuthException(
                            "Protected-resource metadata at "
                                    + candidate
                                    + " lists no authorization_servers.");
                }
                requireHttps(servers.get(0), "authorization server issuer");
                refuseIfBlocked(servers.get(0), "authorization server issuer");
                return new ProtectedResource(
                        resource,
                        servers.get(0),
                        stringList(doc, "scopes_supported"),
                        probe.challengeScope());
            } catch (McpOAuthBlockedException e) {
                throw e;
            } catch (McpOAuthException e) {
                last = e;
            }
        }
        throw new McpOAuthException(
                "This MCP server does not publish OAuth protected-resource metadata (RFC 9728)"
                        + (last == null ? "." : ": " + last.getMessage()),
                last);
    }

    public AuthServerMetadata discoverAuthServer(String issuer) {
        requireHttps(issuer, "authorization server issuer");
        List<String> candidates = new ArrayList<>();
        candidates.add(wellKnown(issuer, "oauth-authorization-server", true));
        candidates.add(wellKnown(issuer, "openid-configuration", true));
        candidates.add(stripTrailingSlash(issuer) + "/.well-known/openid-configuration");
        McpOAuthException last = null;
        McpOAuthException policy =
                null; // a fetched document that violated policy beats later fetch failures
        for (String candidate : candidates.stream().distinct().toList()) {
            boolean fetched = false;
            try {
                JsonNode doc = fetchJson(candidate);
                fetched = true;
                String docIssuer = text(doc, "issuer");
                if (!stripTrailingSlash(issuer)
                        .equals(stripTrailingSlash(docIssuer == null ? "" : docIssuer))) {
                    throw new McpOAuthException(
                            "Authorization-server metadata issuer '"
                                    + docIssuer
                                    + "' does not match expected '"
                                    + issuer
                                    + "' (RFC 8414 section 3.3).");
                }
                String authz = text(doc, "authorization_endpoint");
                String token = text(doc, "token_endpoint");
                if (authz == null || token == null) {
                    throw new McpOAuthException(
                            "Authorization-server metadata is missing authorization_endpoint or token_endpoint.");
                }
                requireHttps(authz, "authorization_endpoint");
                requireHttps(token, "token_endpoint");
                // Every endpoint the flow will call (or send the user's browser to) is vetted NOW,
                // so an
                // authorization server that advertises an internal address is refused up front,
                // with the
                // reason, rather than at token time.
                refuseIfBlocked(authz, "authorization_endpoint");
                refuseIfBlocked(token, "token_endpoint");
                String registration = text(doc, "registration_endpoint");
                if (registration != null) {
                    requireHttps(registration, "registration_endpoint");
                    refuseIfBlocked(registration, "registration_endpoint");
                }
                if (!stringList(doc, "code_challenge_methods_supported").contains("S256")) {
                    throw new McpOAuthException(
                            "Authorization server does not advertise PKCE S256 support - refusing to connect (MCP authorization spec).");
                }
                return new AuthServerMetadata(
                        docIssuer,
                        authz,
                        token,
                        registration,
                        stringList(doc, "scopes_supported"),
                        stringList(doc, "token_endpoint_auth_methods_supported"),
                        flag(doc, "client_id_metadata_document_supported"),
                        flag(doc, "authorization_response_iss_parameter_supported"));
            } catch (McpOAuthBlockedException e) {
                throw e;
            } catch (McpOAuthException e) {
                if (fetched) {
                    policy = e;
                } else {
                    last = e;
                }
            }
        }
        McpOAuthException cause = policy != null ? policy : last;
        throw new McpOAuthException(
                "Could not discover authorization-server metadata for "
                        + issuer
                        + (cause == null ? "." : ": " + cause.getMessage()),
                cause);
    }

    private JsonNode fetchJson(String url) {
        McpOAuthHttp.Response response =
                http.send("GET", url, Map.of("Accept", "application/json"), null);
        if (response.status() != 200) {
            throw new McpOAuthException("GET " + url + " returned HTTP " + response.status() + ".");
        }
        try {
            JsonNode node = objectMapper.readTree(response.body());
            if (node == null || !node.isObject()) {
                throw new McpOAuthException("GET " + url + " did not return a JSON object.");
            }
            return node;
        } catch (McpOAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new McpOAuthException("GET " + url + " returned invalid JSON.", e);
        }
    }

    /**
     * Well-known URI per RFC 8615 / 8414 section 3.1 / 9728 section 3.1: the suffix is inserted
     * between host and path. {@code withPath=false} always yields the host-root form.
     */
    static String wellKnown(String url, String suffix, boolean withPath) {
        URI uri = URI.create(url);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath() == null ? "" : stripTrailingSlash(uri.getRawPath());
        return origin + "/.well-known/" + suffix + (withPath ? path : "");
    }

    /** Throws {@link McpOAuthBlockedException} when the transport's guard refuses {@code url}. */
    private void refuseIfBlocked(String url, String what) {
        http.checkUrl(url)
                .ifPresent(
                        reason -> {
                            throw new McpOAuthBlockedException(
                                    "The " + what + " was refused: " + reason);
                        });
    }

    private static String challengeParam(String challenge, Pattern pattern) {
        if (challenge == null) {
            return null;
        }
        Matcher m = pattern.matcher(challenge);
        return m.find() ? m.group(1) : null;
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isBoolean() && v.asBoolean();
    }

    private static void requireHttps(String url, String what) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            throw new McpOAuthException(
                    "The " + what + " must be an https URL (got: " + url + ").");
        }
        try {
            URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new McpOAuthException("The " + what + " is not a valid URL.", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode v = node.get(field);
        List<String> out = new ArrayList<>();
        if (v != null && v.isArray()) {
            v.forEach(
                    e -> {
                        if (e.isTextual()) {
                            out.add(e.asText());
                        }
                    });
        }
        return out;
    }
}
