package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.oauth.OAuthProviderRegistry.Provider;
import com.agenticai.core.mcp.oauth.OAuthProviderSpec.ClientAuth;
import com.agenticai.core.mcp.oauth.OAuthProviderSpec.RevokeStyle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Wire-level half of the provider OAuth flow: authorize-URL construction, code exchange, refresh
 * and revocation for every {@link OAuthProviderSpec}. All traffic goes through {@link McpOAuthHttp}
 * (https only, SSRF-guarded, no redirects, bounded bodies). Failure messages never contain a
 * response body or a token: bodies can echo request parameters.
 *
 * <p>Provider quirks handled here: GitHub and Slack answer HTTP 200 with an error document; Slack
 * user tokens live under {@code authed_user}; Notion wants Basic auth and a JSON body; Zoom wants
 * Basic auth; Microsoft's endpoints carry the Entra tenant segment.
 */
@Component
public class OAuthProviderClient {

    /** Outcome of a revoke attempt, surfaced to the UI so it can be honest about it. */
    public enum RevokeOutcome {
        REVOKED,
        /** The provider has no revocation endpoint (or the call needs a token that is gone). */
        NOT_SUPPORTED,
        FAILED
    }

    /**
     * @param expiresInSeconds null when the provider did not say (non-expiring token)
     * @param refreshToken null when the provider issued none
     */
    public record TokenResult(
            String accessToken, String refreshToken, Integer expiresInSeconds, String scope) {}

    private final McpOAuthHttp http;
    private final ObjectMapper objectMapper;

    public OAuthProviderClient(McpOAuthHttp http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    public String buildAuthorizationUrl(Provider p, String state, String codeChallenge) {
        OAuthProviderSpec s = p.spec();
        String base = p.resolve(s.authorizeUrl());
        StringBuilder url = new StringBuilder(base);
        url.append(base.contains("?") ? '&' : '?');
        url.append("response_type=code");
        url.append("&client_id=").append(enc(p.clientId()));
        url.append("&redirect_uri=").append(enc(p.redirectUri()));
        url.append("&state=").append(enc(state));
        if (!p.scopes().isEmpty()) {
            url.append('&')
                    .append(s.scopeParam())
                    .append('=')
                    .append(enc(String.join(s.scopeSeparator(), p.scopes())));
        }
        for (Map.Entry<String, String> e : s.extraAuthParams().entrySet()) {
            url.append('&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        if (s.pkce() && codeChallenge != null) {
            url.append("&code_challenge=").append(enc(codeChallenge));
            url.append("&code_challenge_method=S256");
        }
        return url.toString();
    }

    public TokenResult exchangeCode(Provider p, String code, String codeVerifier) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", p.redirectUri());
        if (p.spec().pkce() && codeVerifier != null) {
            f.put("code_verifier", codeVerifier);
        }
        return postToken(p, f, true, "The provider rejected the authorization code");
    }

    public TokenResult refresh(Provider p, String refreshToken) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refreshToken);
        return postToken(p, f, false, "The provider rejected the refresh token");
    }

    private TokenResult postToken(
            Provider p,
            Map<String, String> fields,
            boolean initialExchange,
            String failureContext) {
        OAuthProviderSpec s = p.spec();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.putAll(s.tokenHeaders());
        Map<String, String> body = new LinkedHashMap<>(fields);
        if (s.clientAuth() == ClientAuth.BODY) {
            body.put("client_id", p.clientId());
            body.put("client_secret", p.clientSecret());
        } else {
            headers.put("Authorization", basic(p));
        }
        String payload;
        if (s.clientAuth() == ClientAuth.BASIC_JSON) {
            headers.put("Content-Type", "application/json");
            payload = json(body);
        } else {
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            payload = form(body);
        }
        McpOAuthHttp.Response response =
                http.send("POST", p.resolve(s.tokenUrl()), headers, payload);
        JsonNode node = parseQuietly(response.body());
        String errorCode = errorCodeOf(node);
        if (response.status() >= 300 || errorCode != null) {
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
        if (node == null) {
            throw new McpOAuthException("Could not parse the token response.");
        }
        // Slack user tokens come back under authed_user on the initial exchange only.
        JsonNode src = node;
        if (s.slackUserToken()
                && initialExchange
                && node.path("authed_user").path("access_token").isTextual()) {
            src = node.path("authed_user");
        }
        String accessToken = src.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new McpOAuthException("The token response had no access_token.");
        }
        String refreshToken = src.path("refresh_token").asText(null);
        if (refreshToken != null && refreshToken.isBlank()) {
            refreshToken = null;
        }
        int expiresIn = src.path("expires_in").asInt(0);
        return new TokenResult(
                accessToken,
                refreshToken,
                expiresIn > 0 ? Integer.valueOf(expiresIn) : null,
                src.path("scope").asText(""));
    }

    /**
     * Best-effort revocation. Never throws and never reports a body: a provider that is down must
     * not stop the user disconnecting locally.
     *
     * @param token the token this provider's {@link OAuthProviderSpec#revokeToken()} asks for
     */
    public RevokeOutcome revoke(Provider p, String token) {
        OAuthProviderSpec s = p.spec();
        if (s.revokeStyle() == RevokeStyle.NONE || s.revokeUrl() == null) {
            return RevokeOutcome.NOT_SUPPORTED;
        }
        if (token == null || token.isBlank()) {
            return RevokeOutcome.NOT_SUPPORTED;
        }
        try {
            String url =
                    s.revokeUrl()
                            .replace("{client_id}", enc(p.clientId()))
                            .replace("{token}", enc(token));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            McpOAuthHttp.Response r;
            switch (s.revokeStyle()) {
                case FORM_TOKEN:
                    {
                        Map<String, String> f = new LinkedHashMap<>();
                        f.put("token", token);
                        headers.put("Content-Type", "application/x-www-form-urlencoded");
                        r = http.send("POST", url, headers, form(f));
                        break;
                    }
                case FORM_TOKEN_CLIENT_BODY:
                    {
                        Map<String, String> f = new LinkedHashMap<>();
                        f.put("token", token);
                        f.put("client_id", p.clientId());
                        f.put("client_secret", p.clientSecret());
                        headers.put("Content-Type", "application/x-www-form-urlencoded");
                        r = http.send("POST", url, headers, form(f));
                        break;
                    }
                case FORM_TOKEN_BASIC:
                    {
                        Map<String, String> f = new LinkedHashMap<>();
                        f.put("token", token);
                        headers.put("Authorization", basic(p));
                        headers.put("Content-Type", "application/x-www-form-urlencoded");
                        r = http.send("POST", url, headers, form(f));
                        break;
                    }
                case JSON_TOKEN_BASIC:
                    {
                        Map<String, String> f = new LinkedHashMap<>();
                        f.put("token", token);
                        headers.put("Authorization", basic(p));
                        headers.put("Content-Type", "application/json");
                        // Revocation is a newer Notion endpoint; its reference pins this API
                        // version.
                        headers.put("Notion-Version", "2026-03-11");
                        r = http.send("POST", url, headers, json(f));
                        break;
                    }
                case GITHUB_DELETE:
                    {
                        Map<String, String> f = new LinkedHashMap<>();
                        f.put("access_token", token);
                        headers.put("Accept", "application/vnd.github+json");
                        headers.put("Authorization", basic(p));
                        headers.put("Content-Type", "application/json");
                        headers.put("User-Agent", "VegaDuta");
                        r = http.send("DELETE", url, headers, json(f));
                        break;
                    }
                case HUBSPOT_DELETE:
                    r = http.send("DELETE", url, headers, null);
                    break;
                default:
                    return RevokeOutcome.NOT_SUPPORTED;
            }
            if (r.status() >= 300) {
                return RevokeOutcome.FAILED;
            }
            JsonNode node = parseQuietly(r.body());
            if (node != null && node.has("ok") && !node.path("ok").asBoolean(true)) {
                return RevokeOutcome.FAILED; // Slack: HTTP 200 with {"ok":false}
            }
            return RevokeOutcome.REVOKED;
        } catch (RuntimeException e) {
            return RevokeOutcome.FAILED;
        }
    }

    /**
     * RFC 6749 error code, or null when the document carries none. Handles GitHub's and Slack's
     * 200-with-error bodies.
     */
    private static String errorCodeOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (node.has("ok") && !node.path("ok").asBoolean(true)) {
            String e = node.path("error").asText(null);
            return e == null || e.isBlank() ? "oauth_error" : e;
        }
        if (node.path("error").isTextual() && !node.path("access_token").isTextual()) {
            return node.path("error").asText();
        }
        // HubSpot reports a revoked/invalid refresh token as HTTP 400
        // {"status":"BAD_REFRESH_TOKEN",...} with no
        // "error" field; lower-cased it matches OAuthProviderService's permanent-refresh-error
        // codes.
        // https://developers.hubspot.com/docs/api/oauth/tokens
        if (node.path("status").isTextual()
                && !node.path("access_token").isTextual()
                && node.path("status").asText().matches("[A-Za-z][A-Za-z_]{0,63}")) {
            return node.path("status").asText().toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }

    private JsonNode parseQuietly(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Map<String, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw new McpOAuthException("Could not build the request.", e);
        }
    }

    private static String basic(Provider p) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString(
                                (p.clientId() + ":" + p.clientSecret())
                                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
