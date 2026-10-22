package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.oauth.McpOAuthDiscovery.AuthServerMetadata;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Generic, metadata-driven OAuth 2.1 client for a remote MCP server: RFC 7591 dynamic client
 * registration, authorization-URL construction (PKCE S256 + RFC 8707 {@code resource}), code
 * exchange and refresh. Endpoint URLs come from {@link AuthServerMetadata}; this is the
 * generalisation of {@code AtlassianOAuthClient}, whose endpoints were hard-coded constants.
 *
 * <p>Granted client auth method is taken from the registration RESPONSE (a DCR server may coerce
 * it), never assumed from the request.
 */
@Component
public class McpOAuthClient {

    private final McpOAuthHttp http;
    private final ObjectMapper objectMapper;

    public McpOAuthClient(McpOAuthHttp http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    /**
     * @param clientSecret null for a public ("none") client
     */
    public record Registration(
            String clientId, String clientSecret, String tokenEndpointAuthMethod) {}

    /**
     * @param refreshToken the NEW refresh token to persist when non-null
     */
    public record TokenResponse(
            String accessToken, String refreshToken, int expiresInSeconds, String scope) {}

    public Registration register(AuthServerMetadata as, String redirectUri, String clientName) {
        if (as.registrationEndpoint() == null) {
            throw new McpOAuthException(
                    "This authorization server does not support dynamic client registration "
                            + "(no registration_endpoint); a pre-registered client id is required.");
        }
        String requested =
                as.tokenEndpointAuthMethodsSupported().contains("none")
                        ? "none"
                        : "client_secret_basic";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", clientName);
        body.put("redirect_uris", List.of(redirectUri));
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        // MCP 2026-07-28 (SEP-837): state the application type explicitly so OIDC servers do not
        // apply the "native" default and reject our https redirect URI. The redirect is always a
        // public https callback of this server, i.e. a web client.
        body.put("application_type", "web");
        body.put("token_endpoint_auth_method", requested);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new McpOAuthException("Could not build the client registration request.", e);
        }
        McpOAuthHttp.Response response =
                http.send(
                        "POST",
                        as.registrationEndpoint(),
                        Map.of("Content-Type", "application/json", "Accept", "application/json"),
                        json);
        if (response.status() >= 300) {
            throw new McpOAuthException(
                    "Dynamic client registration was rejected (HTTP " + response.status() + ").");
        }
        JsonNode node = parse(response.body(), "client registration response");
        String clientId = node.path("client_id").asString(null);
        if (clientId == null || clientId.isBlank()) {
            throw new McpOAuthException("The registration response had no client_id.");
        }
        String secret = node.path("client_secret").asString(null);
        String granted = node.path("token_endpoint_auth_method").asString(requested);
        return new Registration(clientId, secret, granted);
    }

    public String buildAuthorizationUrl(
            AuthServerMetadata as,
            String clientId,
            String redirectUri,
            String state,
            String codeChallenge,
            String resource,
            String scope) {
        StringBuilder url = new StringBuilder(as.authorizationEndpoint());
        url.append(as.authorizationEndpoint().contains("?") ? '&' : '?');
        url.append("response_type=code");
        url.append("&client_id=").append(enc(clientId));
        url.append("&redirect_uri=").append(enc(redirectUri));
        url.append("&state=").append(enc(state));
        url.append("&code_challenge=").append(enc(codeChallenge));
        url.append("&code_challenge_method=S256");
        url.append("&resource=").append(enc(resource));
        if (scope != null && !scope.isBlank()) {
            url.append("&scope=").append(enc(scope));
        }
        return url.toString();
    }

    public TokenResponse exchangeCode(
            AuthServerMetadata as,
            String clientId,
            String clientSecret,
            String authMethod,
            String redirectUri,
            String code,
            String codeVerifier,
            String resource) {
        String form =
                "grant_type=authorization_code"
                        + "&code="
                        + enc(code)
                        + "&redirect_uri="
                        + enc(redirectUri)
                        + "&code_verifier="
                        + enc(codeVerifier)
                        + "&resource="
                        + enc(resource);
        return postToken(
                as,
                form,
                clientId,
                clientSecret,
                authMethod,
                "The authorization server rejected the authorization code");
    }

    public TokenResponse refresh(
            AuthServerMetadata as,
            String clientId,
            String clientSecret,
            String authMethod,
            String refreshToken,
            String resource) {
        String form =
                "grant_type=refresh_token"
                        + "&refresh_token="
                        + enc(refreshToken)
                        + "&resource="
                        + enc(resource);
        return postToken(
                as,
                form,
                clientId,
                clientSecret,
                authMethod,
                "The authorization server rejected the refresh token");
    }

    private TokenResponse postToken(
            AuthServerMetadata as,
            String form,
            String clientId,
            String clientSecret,
            String authMethod,
            String failureContext) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        String effective = form;
        if ("client_secret_basic".equals(authMethod) && clientSecret != null) {
            headers.put(
                    "Authorization",
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (enc(clientId) + ":" + enc(clientSecret))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } else if ("client_secret_post".equals(authMethod) && clientSecret != null) {
            effective =
                    effective
                            + "&client_id="
                            + enc(clientId)
                            + "&client_secret="
                            + enc(clientSecret);
        } else {
            effective = effective + "&client_id=" + enc(clientId);
        }
        McpOAuthHttp.Response response = http.send("POST", as.tokenEndpoint(), headers, effective);
        if (response.status() >= 300) {
            String errorCode = null;
            try {
                errorCode = objectMapper.readTree(response.body()).path("error").asString(null);
            } catch (Exception ignored) {
                // non-JSON error body: leave null (= unknown, treated as transient by callers)
            }
            // Deliberately omits the response body: it can echo request parameters.
            throw new McpOAuthException(
                    failureContext
                            + " (HTTP "
                            + response.status()
                            + (errorCode == null ? "" : ", " + errorCode)
                            + ").",
                    null,
                    errorCode);
        }
        JsonNode node = parse(response.body(), "token response");
        String accessToken = node.path("access_token").asString(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new McpOAuthException("The token response had no access_token.");
        }
        return new TokenResponse(
                accessToken,
                node.path("refresh_token").asString(null),
                node.path("expires_in").asInt(3600),
                node.path("scope").asString(""));
    }

    private JsonNode parse(String body, String what) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new McpOAuthException("Could not parse the " + what + ".", e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
