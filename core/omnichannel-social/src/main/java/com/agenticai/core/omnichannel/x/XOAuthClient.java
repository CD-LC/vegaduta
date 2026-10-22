package com.agenticai.core.omnichannel.x;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Plain {@link HttpClient} calls against X's OAuth 2.0 endpoints - same "a handful of REST calls
 * doesn't justify an SDK" choice every other API client in this codebase makes.
 *
 * <p>Unlike {@code GoogleOAuthClient}, every call here takes a tenant-owned {@code clientId}/{@code
 * clientSecret} rather than one platform-wide pair - see {@code XCredential}'s javadoc for why X's
 * OAuth has to be BYOK.
 *
 * <p>X's OAuth 2.0 additionally requires PKCE (Proof Key for Code Exchange) even for confidential
 * clients - {@link #newPkcePair()} generates the {@code code_verifier}/{@code code_challenge} pair;
 * the verifier has to survive the redirect round-trip somehow, so {@link XOAuthService} embeds it
 * inside the same encrypted {@code state} blob that already carries the tenant's schema (see that
 * class's javadoc), rather than any server-side session store.
 */
@Component
public class XOAuthClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String AUTH_ENDPOINT = "https://x.com/i/oauth2/authorize";
    private static final String TOKEN_ENDPOINT = "https://api.x.com/2/oauth2/token";
    private static final String USERS_ME_ENDPOINT = "https://api.x.com/2/users/me";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;

    public XOAuthClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A fresh, single-use PKCE pair for one authorization attempt. */
    public PkcePair newPkcePair() {
        byte[] bytes = new byte[64];
        RANDOM.nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return new PkcePair(verifier, challenge);
        } catch (Exception e) {
            throw new XApiException("Could not generate a PKCE challenge: " + e.getMessage(), e);
        }
    }

    public String buildAuthorizationUrl(
            String clientId,
            String redirectUri,
            String scopes,
            String state,
            String codeChallenge) {
        return AUTH_ENDPOINT
                + "?client_id="
                + urlEncode(clientId)
                + "&redirect_uri="
                + urlEncode(redirectUri)
                + "&response_type=code"
                + "&scope="
                + urlEncode(scopes)
                + "&state="
                + urlEncode(state)
                + "&code_challenge="
                + urlEncode(codeChallenge)
                + "&code_challenge_method=S256";
    }

    public TokenResponse exchangeCode(
            String clientId,
            String clientSecret,
            String redirectUri,
            String code,
            String codeVerifier) {
        String form =
                "code="
                        + urlEncode(code)
                        + "&grant_type=authorization_code"
                        + "&client_id="
                        + urlEncode(clientId)
                        + "&redirect_uri="
                        + urlEncode(redirectUri)
                        + "&code_verifier="
                        + urlEncode(codeVerifier);
        return postToken(form, clientId, clientSecret, "X rejected the authorization code");
    }

    /**
     * @return the NEW token pair, including a NEW refresh token - X rotates refresh tokens on every
     *     use (see {@code XCredential}'s javadoc); the caller MUST persist {@link
     *     TokenResponse#refreshToken()} again or the old one stops working on the very next
     *     refresh.
     */
    public TokenResponse refreshAccessToken(
            String clientId, String clientSecret, String refreshToken) {
        String form =
                "refresh_token="
                        + urlEncode(refreshToken)
                        + "&grant_type=refresh_token"
                        + "&client_id="
                        + urlEncode(clientId);
        return postToken(
                form,
                clientId,
                clientSecret,
                "X rejected the refresh token - the tenant likely needs to reconnect");
    }

    private TokenResponse postToken(
            String form, String clientId, String clientSecret, String failureContext) {
        String basicAuth =
                Base64.getEncoder()
                        .encodeToString(
                                (urlEncode(clientId) + ":" + urlEncode(clientSecret))
                                        .getBytes(StandardCharsets.UTF_8));
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_ENDPOINT))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Basic " + basicAuth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new XApiException(
                        failureContext + " (" + response.statusCode() + "): " + response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());
            String accessToken = node.path("access_token").asString(null);
            if (accessToken == null) {
                throw new XApiException(
                        "X's token response had no access_token: " + response.body());
            }
            return new TokenResponse(
                    accessToken,
                    node.path("refresh_token").asString(null),
                    node.path("expires_in").asInt(7200),
                    node.path("scope").asString(""));
        } catch (XApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XApiException(
                    "Could not reach X's OAuth token endpoint: " + e.getMessage(), e);
        }
    }

    public MeResponse fetchMe(String accessToken) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(USERS_ME_ENDPOINT))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new XApiException(
                        "Could not fetch the connected X account's identity ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            String id = data.path("id").asString(null);
            if (id == null) {
                throw new XApiException("X's users/me response had no id: " + response.body());
            }
            return new MeResponse(id, data.path("username").asString(null));
        } catch (XApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XApiException("Could not reach X's users/me endpoint: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record PkcePair(String verifier, String challenge) {}

    /**
     * @param refreshToken the NEW refresh token to persist - see {@link #refreshAccessToken}'s
     *     javadoc. Never null on a successful response as long as {@code offline.access} was
     *     granted.
     */
    public record TokenResponse(
            String accessToken, String refreshToken, int expiresInSeconds, String scope) {}

    public record MeResponse(String id, String username) {}
}
