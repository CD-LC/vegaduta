package com.agenticai.core.google;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Plain {@link HttpClient} calls against Google's OAuth 2.0 endpoints - no Google API Client
 * Library dependency, same "a handful of REST calls doesn't justify an SDK" choice {@code
 * MetaGraphApiClient} makes for Meta.
 *
 * <p>One platform-level OAuth app services every tenant (see {@code GoogleOAuthService}'s javadoc
 * for why this can't be BYOK the way Twilio/ Meta manual credentials are): a Google Cloud OAuth 2.0
 * Client ID of type "Web application" with this platform's callback URL added as an authorized
 * redirect URI, created once by the platform operator in Google Cloud Console, never by a tenant.
 */
@Component
public class GoogleOAuthClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";
    private static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo";

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;

    public GoogleOAuthClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * {@code access_type=offline} + {@code prompt=consent} are both required to reliably get a
     * {@code refresh_token} back - Google only issues one on a user's very first consent for a
     * given client+scopes combination otherwise, which would silently break
     * reconnect-after-disconnect.
     */
    public String buildAuthorizationUrl(
            String clientId, String redirectUri, String scopes, String state) {
        return buildAuthorizationUrl(clientId, redirectUri, scopes, state, null);
    }

    /**
     * {@code loginHint} is the incremental-consent account pin: Google pre-selects that account on
     * the consent screen, so a follow-up consent chained off a just-completed connect can't quietly
     * come back granted to a DIFFERENT Google account the user happened to be signed into. Google
     * still lets the user switch accounts deliberately - the email check in {@code
     * GoogleOAuthService#connect} is the hard backstop for that.
     */
    public String buildAuthorizationUrl(
            String clientId, String redirectUri, String scopes, String state, String loginHint) {
        return buildAuthorizationUrl(clientId, redirectUri, scopes, state, loginHint, null);
    }

    /**
     * {@code codeChallenge} (S256) turns on PKCE; null leaves the request exactly as the
     * web-application flow has always sent it. The matching verifier goes to {@link
     * #exchangeCode(String, String, String, String, String)}.
     */
    public String buildAuthorizationUrl(
            String clientId,
            String redirectUri,
            String scopes,
            String state,
            String loginHint,
            String codeChallenge) {
        return AUTH_ENDPOINT
                + "?client_id="
                + urlEncode(clientId)
                + "&redirect_uri="
                + urlEncode(redirectUri)
                + "&response_type=code"
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&scope="
                + urlEncode(scopes)
                + "&state="
                + urlEncode(state)
                + (loginHint == null ? "" : "&login_hint=" + urlEncode(loginHint))
                + (codeChallenge == null
                        ? ""
                        : "&code_challenge="
                                + urlEncode(codeChallenge)
                                + "&code_challenge_method=S256");
    }

    public TokenResponse exchangeCode(
            String clientId, String clientSecret, String redirectUri, String code) {
        return exchangeCode(clientId, clientSecret, redirectUri, code, null);
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
                        + (codeVerifier == null ? "" : "&code_verifier=" + urlEncode(codeVerifier))
                        + "&client_id="
                        + urlEncode(clientId)
                        + "&client_secret="
                        + urlEncode(clientSecret)
                        + "&redirect_uri="
                        + urlEncode(redirectUri)
                        + "&grant_type=authorization_code";
        return postToken(form, "Google rejected the authorization code");
    }

    public TokenResponse refreshAccessToken(
            String clientId, String clientSecret, String refreshToken) {
        String form =
                "refresh_token="
                        + urlEncode(refreshToken)
                        + "&client_id="
                        + urlEncode(clientId)
                        + "&client_secret="
                        + urlEncode(clientSecret)
                        + "&grant_type=refresh_token";
        return postToken(
                form, "Google rejected the refresh token - the tenant likely needs to reconnect");
    }

    private TokenResponse postToken(String form, String failureContext) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_ENDPOINT))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new GoogleApiException(
                        failureContext + " (" + response.statusCode() + "): " + response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null) {
                throw new GoogleApiException(
                        "Google's token response had no access_token: " + response.body());
            }
            return new TokenResponse(
                    accessToken,
                    node.path("refresh_token").asText(null),
                    node.path("expires_in").asInt(3600),
                    node.path("scope").asText(""));
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not reach Google's OAuth token endpoint: " + e.getMessage(), e);
        }
    }

    public String fetchEmail(String accessToken) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(USERINFO_ENDPOINT))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new GoogleApiException(
                        "Could not fetch the connected Google account's email ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());
            String email = node.path("email").asText(null);
            if (email == null) {
                throw new GoogleApiException(
                        "Google's userinfo response had no email: " + response.body());
            }
            return email;
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not reach Google's userinfo endpoint: " + e.getMessage(), e);
        }
    }

    /** Best-effort - failures are logged by the caller, not fatal to disconnecting locally. */
    public void revoke(String token) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(REVOKE_ENDPOINT + "?token=" + urlEncode(token)))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not reach Google's revoke endpoint: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * @param refreshToken null on a refresh-grant response - Google only returns a refresh_token on
     *     the initial authorization_code grant.
     * @param scope space-separated, what Google actually granted (see {@code
     *     GoogleCredential#hasScope}) - not necessarily everything that was requested.
     */
    public record TokenResponse(
            String accessToken, String refreshToken, int expiresInSeconds, String scope) {}
}
