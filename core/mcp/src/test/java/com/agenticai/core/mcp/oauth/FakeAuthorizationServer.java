package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A stateful, in-process MCP server + authorization server behind the {@link McpOAuthHttp} seam. Unlike a canned
 * fake it ENFORCES the protocol, so a test passing means the client really sent the right thing:
 * PKCE S256 challenge/verifier binding, redirect-uri equality, RFC 8707 {@code resource} equality, client
 * authentication, single-use authorization codes, refresh-token rotation, and - for a CIMD client - that the
 * redirect URI is listed in the client's metadata document.
 *
 * <p>It applies the REAL {@link McpRemoteUrlGuard} (DNS-free {@code check}) exactly as {@link JdkMcpOAuthHttp}
 * does, so SSRF refusals in tests are the production guard's, not a stub's.
 */
final class FakeAuthorizationServer implements McpOAuthHttp {

    final String mcpUrl;
    final String issuer;
    final List<String> requests = new ArrayList<>();
    final List<String> tokenRequestBodies = new ArrayList<>();

    // --- knobs
    boolean mcpRequiresAuth = true;
    boolean advertiseResourceMetadataInChallenge = false;
    String challengeScope = null;
    List<String> scopesSupported = List.of("read", "write");
    String prmResource;
    String prmAuthorizationServer;
    boolean cimdSupported = false;
    boolean issParameterSupported = false;
    boolean hasRegistrationEndpoint = true;
    String tokenEndpointOverride = null;
    String registrationEndpointOverride = null;
    boolean issueRefreshToken = true;
    int expiresIn = 3600;
    boolean refreshInvalidGrant = false;
    int refreshTransientStatus = 0;
    /** client_id (URL) -> client metadata document the AS "fetches" for a CIMD client. */
    final Map<String, JsonNode> cimdDocuments = new HashMap<>();

    // --- state
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpRemoteUrlGuard guard = new McpRemoteUrlGuard();
    private final Map<String, Client> clients = new HashMap<>();
    private final Map<String, Code> codes = new HashMap<>();
    private final Set<String> liveRefreshTokens = new HashSet<>();
    private int counter;
    int registrations;

    private record Client(String id, String secret, Set<String> redirectUris) {
    }

    private record Code(String clientId, String redirectUri, String challenge, String resource, String scope) {
    }

    FakeAuthorizationServer(String mcpUrl, String issuer) {
        this.mcpUrl = mcpUrl;
        this.issuer = issuer;
        this.prmResource = mcpUrl;
        this.prmAuthorizationServer = issuer;
    }

    /** The user's browser "consents": validates the authorization request and returns {code, state, iss} for the callback. */
    Map<String, String> consent(String authorizationUrl) {
        URI uri = URI.create(authorizationUrl);
        if (!authorizationUrl.startsWith(issuer + "/authorize?")) {
            throw new AssertionError("not our authorization endpoint: " + authorizationUrl);
        }
        Map<String, String> q = query(uri.getRawQuery());
        String clientId = q.get("client_id");
        String redirect = q.get("redirect_uri");
        require("code".equals(q.get("response_type")), "response_type=code");
        require("S256".equals(q.get("code_challenge_method")) && q.get("code_challenge") != null, "PKCE S256");
        require(q.get("state") != null && !q.get("state").isBlank(), "state");
        require(q.get("resource") != null, "RFC 8707 resource");
        Client client = clients.get(clientId);
        if (client == null && cimdDocuments.containsKey(clientId)) {
            // CIMD: the AS fetches the client's metadata document and trusts its redirect_uris.
            JsonNode doc = cimdDocuments.get(clientId);
            require(clientId.equals(doc.path("client_id").asText()), "document client_id equals its URL");
            Set<String> uris = new HashSet<>();
            doc.path("redirect_uris").forEach(n -> uris.add(n.asText()));
            client = new Client(clientId, null, uris);
        }
        require(client != null, "known client " + clientId);
        require(client.redirectUris().contains(redirect), "redirect_uri registered for the client: " + redirect);
        String code = "code-" + (++counter);
        codes.put(code, new Code(clientId, redirect, q.get("code_challenge"), q.get("resource"), q.get("scope")));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("state", q.get("state"));
        if (issParameterSupported) {
            out.put("iss", issuer);
        }
        return out;
    }

    /** Query parameters of the authorization URL, decoded. */
    static Map<String, String> query(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Override
    public Optional<String> checkUrl(String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            return Optional.of("MCP OAuth only talks to https URLs.");
        }
        return guard.check(url);
    }

    @Override
    public Response send(String method, String url, Map<String, String> headers, String body) {
        Optional<String> refused = checkUrl(url);
        if (refused.isPresent()) {
            throw new McpOAuthBlockedException("MCP OAuth request blocked: " + refused.get());
        }
        requests.add(method + " " + url); // only requests that passed the guard "reach the network"
        try {
            return route(method, url, headers, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Response route(String method, String url, Map<String, String> headers, String body) throws Exception {
        URI mcp = URI.create(mcpUrl);
        String mcpOrigin = "https://" + mcp.getRawAuthority();
        String prmPath = mcpOrigin + "/.well-known/oauth-protected-resource" + (mcp.getRawPath() == null ? "" : mcp.getRawPath());
        if ("POST".equals(method) && url.equals(mcpUrl)) {
            if (!mcpRequiresAuth) {
                return json(200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"bad request\"},\"id\":null}");
            }
            StringBuilder challenge = new StringBuilder("Bearer");
            List<String> params = new ArrayList<>();
            if (advertiseResourceMetadataInChallenge) {
                params.add("resource_metadata=\"" + prmPath + "\"");
            }
            if (challengeScope != null) {
                params.add("scope=\"" + challengeScope + "\"");
            }
            if (!params.isEmpty()) {
                challenge.append(' ').append(String.join(", ", params));
            }
            return new Response(401, Map.of("www-authenticate", challenge.toString()), "");
        }
        if ("GET".equals(method) && url.equals(prmPath)) {
            return json(200, mapper.writeValueAsString(Map.of(
                "resource", prmResource,
                "authorization_servers", List.of(prmAuthorizationServer),
                "scopes_supported", scopesSupported)));
        }
        if ("GET".equals(method) && url.equals(issuer + "/.well-known/oauth-authorization-server")) {
            Map<String, Object> as = new LinkedHashMap<>();
            as.put("issuer", issuer);
            as.put("authorization_endpoint", issuer + "/authorize");
            as.put("token_endpoint", tokenEndpointOverride != null ? tokenEndpointOverride : issuer + "/token");
            if (hasRegistrationEndpoint) {
                as.put("registration_endpoint", registrationEndpointOverride != null ? registrationEndpointOverride : issuer + "/register");
            }
            as.put("code_challenge_methods_supported", List.of("S256"));
            as.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_basic"));
            if (cimdSupported) {
                as.put("client_id_metadata_document_supported", true);
            }
            if (issParameterSupported) {
                as.put("authorization_response_iss_parameter_supported", true);
            }
            return json(200, mapper.writeValueAsString(as));
        }
        if ("POST".equals(method) && url.equals(issuer + "/register")) {
            registrations++;
            JsonNode req = mapper.readTree(body);
            String id = "dcr-client-" + registrations;
            String secret = "dcr-secret-" + registrations;
            Set<String> uris = new HashSet<>();
            req.path("redirect_uris").forEach(n -> uris.add(n.asText()));
            clients.put(id, new Client(id, secret, uris));
            return json(201, mapper.writeValueAsString(Map.of(
                "client_id", id, "client_secret", secret, "token_endpoint_auth_method", "client_secret_basic")));
        }
        if ("POST".equals(method) && url.equals(issuer + "/token")) {
            tokenRequestBodies.add(body);
            return token(headers, body);
        }
        return new Response(404, Map.of(), "");
    }

    private Response token(Map<String, String> headers, String body) throws Exception {
        Map<String, String> form = query(body);
        String clientId = form.get("client_id");
        String auth = headers.get("Authorization");
        if (auth != null && auth.startsWith("Basic ")) {
            String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
            String[] idSecret = decoded.split(":", 2);
            clientId = URLDecoder.decode(idSecret[0], StandardCharsets.UTF_8);
            Client c = clients.get(clientId);
            if (c == null || !URLDecoder.decode(idSecret[1], StandardCharsets.UTF_8).equals(c.secret())) {
                return json(401, "{\"error\":\"invalid_client\"}");
            }
        }
        if (clientId == null) {
            return json(401, "{\"error\":\"invalid_client\"}");
        }
        String grant = form.get("grant_type");
        if ("authorization_code".equals(grant)) {
            Code code = codes.remove(form.get("code"));
            if (code == null) {
                return json(400, "{\"error\":\"invalid_grant\"}");
            }
            boolean ok = code.clientId().equals(clientId)
                && code.redirectUri().equals(form.get("redirect_uri"))
                && code.resource().equals(form.get("resource"))
                && code.challenge().equals(McpOAuthPkce.challengeFor(String.valueOf(form.get("code_verifier"))));
            if (!ok) {
                return json(400, "{\"error\":\"invalid_grant\"}");
            }
            return issue(code.scope());
        }
        if ("refresh_token".equals(grant)) {
            if (refreshTransientStatus != 0) {
                return json(refreshTransientStatus, "temporarily unavailable");
            }
            if (refreshInvalidGrant || !liveRefreshTokens.remove(form.get("refresh_token"))) {
                return json(400, "{\"error\":\"invalid_grant\"}");
            }
            return issue(null);
        }
        return json(400, "{\"error\":\"unsupported_grant_type\"}");
    }

    private Response issue(String scope) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", "at-" + UUID.randomUUID());
        out.put("token_type", "Bearer");
        out.put("expires_in", expiresIn);
        if (issueRefreshToken) {
            String rt = "rt-" + UUID.randomUUID();
            liveRefreshTokens.add(rt);
            out.put("refresh_token", rt);
        }
        if (scope != null) {
            out.put("scope", scope);
        }
        return json(200, mapper.writeValueAsString(out));
    }

    /** Invalidates every refresh token (the user revoked the grant at the AS). */
    void revokeAll() {
        liveRefreshTokens.clear();
    }

    private static Response json(int status, String body) {
        return new Response(status, Map.of("content-type", "application/json"), body);
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("authorization server rejected the request: " + what);
        }
    }
}
