package com.agenticai.core.mcp.atlassian;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Plain {@link HttpClient} calls against Atlassian's remote MCP server's OAuth 2.1 endpoints - same
 * "a handful of REST calls doesn't justify an SDK" choice {@code XOAuthClient}/{@code
 * GoogleOAuthClient} already make. Endpoints confirmed by probing {@code
 * https://mcp.atlassian.com/.well-known/ oauth-authorization-server} during design
 * (authorization_endpoint=/v1/authorize, token_endpoint=/v1/token,
 * registration_endpoint=/v1/register, code_challenge_methods_supported=[plain,S256]).
 *
 * <p><b>Dynamic Client Registration (RFC 7591), not BYOK.</b> Unlike X (each tenant brings their
 * own Developer App), Atlassian's server has a self-service {@code /v1/register} endpoint - {@link
 * #registerClient} is called exactly once, platform-wide (see {@link
 * AtlassianPlatformClientService}), not per tenant. {@code client_secret_basic} is requested
 * explicitly, but the server's actual grant is authoritative - {@link
 * DynamicClientRegistration#tokenEndpointAuthMethod()} reflects whatever came back in the response,
 * never the request, since a DCR server is free to coerce the auth method (e.g. downgrade to a
 * public {@code "none"} client) regardless of what was asked for.
 *
 * <p><b>PKCE, same as X.</b> {@link #newPkcePair()} generates the {@code code_verifier}/{@code
 * code_challenge} pair; {@link AtlassianOAuthService} embeds the verifier in the same encrypted
 * {@code state} blob that already carries the tenant's schema.
 *
 * <p><b>{@code resource} parameter (RFC 8707).</b> Included on both the authorize and token calls
 * per the MCP authorization spec's guidance to scope a token to the specific MCP server it was
 * requested for - this is a convention this class applies proactively, not something confirmed as
 * strictly required by Atlassian's server; verify empirically against real traffic and drop it if
 * it turns out to cause a rejection.
 *
 * <p><b>{@code scope} deliberately omitted from the authorization request.</b> Atlassian's
 * discovery document does not publish a {@code scopes_supported} list, and no scope string has been
 * confirmed against real Atlassian behavior at design time - inventing one risks a silent over- or
 * under-grant. {@code scope} is optional per RFC 6749 §3.3; add an explicit value here only once
 * the correct scope(s) for Confluence/Jira read access are confirmed against Atlassian's own
 * documentation or a real consent screen.
 */
@Component
public class AtlassianOAuthClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String AUTHORIZE_ENDPOINT = "https://mcp.atlassian.com/v1/authorize";
    private static final String TOKEN_ENDPOINT = "https://mcp.atlassian.com/v1/token";
    private static final String REGISTER_ENDPOINT = "https://mcp.atlassian.com/v1/register";
    private static final String RESOURCE = "https://mcp.atlassian.com";
    private static final String REQUESTED_AUTH_METHOD = "client_secret_basic";

    // Guarded transport: the socket resolves through SsrfGuard, so even the
    // fixed Atlassian endpoints cannot be DNS-rebound to an internal address.
    private final HttpClient httpClient =
            GuardedJdkHttpClient.create(SsrfGuard.defaultGuard(), TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;

    public AtlassianOAuthClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A fresh, single-use PKCE pair for one authorization attempt - identical shape to {@code
     * XOAuthClient.newPkcePair}.
     */
    public PkcePair newPkcePair() {
        // Shared with the generic MCP OAuth flow (com.agenticai.core.mcp.oauth) - one PKCE
        // implementation.
        try {
            com.agenticai.core.mcp.oauth.McpOAuthPkce.Pair pair =
                    com.agenticai.core.mcp.oauth.McpOAuthPkce.generate();
            return new PkcePair(pair.verifier(), pair.challenge());
        } catch (Exception e) {
            throw new AtlassianApiException(
                    "Could not generate a PKCE challenge: " + e.getMessage(), e);
        }
    }

    /**
     * One-time (platform-wide) RFC 7591 registration - see {@link
     * AtlassianPlatformClientService#ensureRegistered}.
     */
    public DynamicClientRegistration registerClient(String redirectUri) {
        String body;
        try {
            body =
                    objectMapper.writeValueAsString(
                            new RegistrationRequestBody(
                                    "VegaDuta",
                                    List.of(redirectUri),
                                    List.of("authorization_code", "refresh_token"),
                                    List.of("code"),
                                    REQUESTED_AUTH_METHOD));
        } catch (Exception e) {
            throw new AtlassianApiException(
                    "Could not build the Dynamic Client Registration request body: "
                            + e.getMessage(),
                    e);
        }
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(REGISTER_ENDPOINT))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new AtlassianApiException(
                        "Atlassian rejected Dynamic Client Registration ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());
            String clientId = node.path("client_id").asText(null);
            if (clientId == null) {
                throw new AtlassianApiException(
                        "Atlassian's registration response had no client_id: " + response.body());
            }
            String clientSecret = node.path("client_secret").asText(null);
            // The server's actual grant is authoritative, not what was requested - see class
            // javadoc.
            String grantedAuthMethod =
                    node.path("token_endpoint_auth_method").asText(REQUESTED_AUTH_METHOD);
            return new DynamicClientRegistration(clientId, clientSecret, grantedAuthMethod);
        } catch (AtlassianApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AtlassianApiException(
                    "Could not reach Atlassian's Dynamic Client Registration endpoint: "
                            + e.getMessage(),
                    e);
        }
    }

    public String buildAuthorizationUrl(
            String clientId, String redirectUri, String state, String codeChallenge) {
        return AUTHORIZE_ENDPOINT
                + "?client_id="
                + urlEncode(clientId)
                + "&redirect_uri="
                + urlEncode(redirectUri)
                + "&response_type=code"
                + "&state="
                + urlEncode(state)
                + "&code_challenge="
                + urlEncode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&resource="
                + urlEncode(RESOURCE);
    }

    public TokenResponse exchangeCode(
            String clientId,
            String clientSecret,
            String authMethod,
            String redirectUri,
            String code,
            String codeVerifier) {
        String form =
                "grant_type=authorization_code"
                        + "&code="
                        + urlEncode(code)
                        + "&client_id="
                        + urlEncode(clientId)
                        + "&redirect_uri="
                        + urlEncode(redirectUri)
                        + "&code_verifier="
                        + urlEncode(codeVerifier)
                        + "&resource="
                        + urlEncode(RESOURCE);
        return postToken(
                form,
                clientId,
                clientSecret,
                authMethod,
                "Atlassian rejected the authorization code");
    }

    /**
     * @return the NEW token pair. Whether Atlassian rotates the refresh token on every use (like X)
     *     or returns a long-lived reusable one (like Google) is unconfirmed - the caller persists a
     *     rotated token defensively whenever {@link TokenResponse#refreshToken()} is non-null,
     *     which is correct under either behavior.
     */
    public TokenResponse refreshAccessToken(
            String clientId, String clientSecret, String authMethod, String refreshToken) {
        String form =
                "grant_type=refresh_token"
                        + "&refresh_token="
                        + urlEncode(refreshToken)
                        + "&client_id="
                        + urlEncode(clientId)
                        + "&resource="
                        + urlEncode(RESOURCE);
        return postToken(
                form,
                clientId,
                clientSecret,
                authMethod,
                "Atlassian rejected the refresh token - the tenant likely needs to reconnect");
    }

    /**
     * Auth-method-aware POST, since a Dynamic-Client-Registration server may grant a different auth
     * method than requested (public {@code "none"} client, {@code client_secret_post} instead of
     * {@code _basic}, etc.) - see class javadoc.
     */
    private TokenResponse postToken(
            String form,
            String clientId,
            String clientSecret,
            String authMethod,
            String failureContext) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_ENDPOINT))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded");
        String effectiveForm = form;
        if ("client_secret_basic".equals(authMethod) && clientSecret != null) {
            String basicAuth =
                    Base64.getEncoder()
                            .encodeToString(
                                    (urlEncode(clientId) + ":" + urlEncode(clientSecret))
                                            .getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basicAuth);
        } else if ("client_secret_post".equals(authMethod) && clientSecret != null) {
            effectiveForm = effectiveForm + "&client_secret=" + urlEncode(clientSecret);
        }
        // "none" (public client, or no secret was granted): client_id alone in the body, no secret
        // anywhere - already the case.
        HttpRequest request =
                builder.POST(HttpRequest.BodyPublishers.ofString(effectiveForm)).build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                // Best-effort parse of RFC 6749 §5.2's {"error": "..."} shape -
                // null (not a parse failure) when absent/unparseable, which
                // AtlassianOAuthService#currentAccessToken treats as "unknown,
                // do not assume invalid_grant" - see AtlassianApiException's
                // javadoc.
                String oauthErrorCode = null;
                try {
                    oauthErrorCode =
                            objectMapper.readTree(response.body()).path("error").asText(null);
                } catch (Exception ignored) {
                    // Non-JSON or unexpected error body - leave oauthErrorCode null.
                }
                throw new AtlassianApiException(
                        failureContext + " (" + response.statusCode() + "): " + response.body(),
                        null,
                        oauthErrorCode);
            }
            JsonNode node = objectMapper.readTree(response.body());
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null) {
                throw new AtlassianApiException(
                        "Atlassian's token response had no access_token: " + response.body());
            }
            return new TokenResponse(
                    accessToken,
                    node.path("refresh_token").asText(null),
                    node.path("expires_in").asInt(3600),
                    node.path("scope").asText(""));
        } catch (AtlassianApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AtlassianApiException(
                    "Could not reach Atlassian's OAuth token endpoint: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record PkcePair(String verifier, String challenge) {}

    /**
     * @param clientSecret null when Atlassian granted a public ({@code "none"}) client despite
     *     {@code client_secret_basic} being requested - see class javadoc.
     * @param tokenEndpointAuthMethod what Atlassian's registration response actually granted,
     *     persisted verbatim - never assume it matches what was requested.
     */
    public record DynamicClientRegistration(
            String clientId, String clientSecret, String tokenEndpointAuthMethod) {}

    /**
     * @param refreshToken the NEW refresh token to persist if non-null - see {@link
     *     #refreshAccessToken}'s javadoc.
     */
    public record TokenResponse(
            String accessToken, String refreshToken, int expiresInSeconds, String scope) {}

    /** RFC 7591 registration request body shape. */
    private record RegistrationRequestBody(
            String client_name,
            List<String> redirect_uris,
            List<String> grant_types,
            List<String> response_types,
            String token_endpoint_auth_method) {
        // Component names are written as RFC 7591's exact wire names
        // (snake_case) rather than camelCase + @JsonProperty on every
        // field - Jackson serializes a record component's name verbatim by
        // default, so this alone is enough regardless of any global naming
        // strategy.
    }
}
