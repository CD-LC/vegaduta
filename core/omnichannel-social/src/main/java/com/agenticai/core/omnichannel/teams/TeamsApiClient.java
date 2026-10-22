package com.agenticai.core.omnichannel.teams;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Plain {@link HttpClient} wrapper for the two HTTP surfaces a Bot Framework bot needs - the same
 * "handful of REST calls" shape {@code TelegramApiClient} takes, no Microsoft SDK:
 *
 * <ol>
 *   <li><b>Token:</b> OAuth2 client-credentials against {@code
 *       login.microsoftonline.com/{aadTenantId}/oauth2/v2.0/token} with scope {@code
 *       https://api.botframework.com/.default}. The returned access token is what {@code
 *       Authorization: Bearer} on outbound connector calls expects, and is also this class's
 *       credential proof: a token mint means appId + appPassword + tenant are all correct, which is
 *       the only activation-time validation Azure offers (there is no "getMe" equivalent). Tokens
 *       are cached per appId until 60s before their own {@code expires_in} so a chatty conversation
 *       doesn't mint one per reply.
 *   <li><b>Connector:</b> {@code POST {serviceUrl}v3/conversations/{id}/activities} - serviceUrl
 *       comes from the inbound activity (or {@code teams_conversation_route} for async sends); it
 *       is per-conversation, never a global constant, which is the classic Bot Framework mistake.
 * </ol>
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class TeamsApiClient {

    private static final String TOKEN_SCOPE = "https://api.botframework.com/.default";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TOKEN_EXPIRY_SKEW = Duration.ofSeconds(60);

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final TeamsServiceUrlPolicy serviceUrlPolicy;

    public TeamsApiClient(ObjectMapper objectMapper, TeamsServiceUrlPolicy serviceUrlPolicy) {
        this.objectMapper = objectMapper;
        this.serviceUrlPolicy = serviceUrlPolicy;
    }

    /**
     * Activation-time credential proof: a successful token mint means the
     * appId/appPassword/aadTenantId triple is real. Throws {@link ChannelConfigurationException}
     * with Entra's own error text otherwise.
     */
    public void validateCredentials(String appId, String appPassword, String aadTenantId) {
        mintToken(appId, appPassword, aadTenantId);
    }

    /**
     * Sends a text reply activity to a Bot Framework conversation.
     *
     * @param serviceUrl the conversation's service URL (from the inbound activity or {@code
     *     teams_conversation_route}), e.g. {@code https://smba.trafficmanager.net/amer/}
     * @param conversationId the Bot Framework conversation id
     * @param text reply text
     * @param replyToId optional activity id to thread the reply under
     * @return a short, human-readable log line describing what happened
     */
    public String sendActivity(
            String appId,
            String appPassword,
            String aadTenantId,
            String serviceUrl,
            String conversationId,
            String text,
            String replyToId) {
        // SEC-047: the bot's AAD bearer token is attached below - never send it to a
        // host that is not a Microsoft Bot Framework connector (covers routes stored
        // before the JWT serviceurl binding existed, and async sends).
        if (!serviceUrlPolicy.isAllowed(serviceUrl)) {
            throw new ChannelDeliveryException(
                    "teams",
                    "Refusing to send: the conversation's serviceUrl is not an allowed Bot Framework host");
        }
        try {
            String base = serviceUrl.endsWith("/") ? serviceUrl : serviceUrl + "/";
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("type", "message");
            activity.put("text", text);
            activity.put("from", Map.of("id", appId));
            activity.put("conversation", Map.of("id", conversationId));
            if (replyToId != null && !replyToId.isBlank()) {
                activity.put("replyToId", replyToId);
            }
            String body = objectMapper.writeValueAsString(activity);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            base
                                                    + "v3/conversations/"
                                                    + encode(conversationId)
                                                    + "/activities"))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header(
                                    "Authorization",
                                    "Bearer " + token(appId, appPassword, aadTenantId))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        "teams",
                        "Bot Framework send failed ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            return "Sent Teams message to conversation " + conversationId + ".";
        } catch (ChannelDeliveryException | ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "teams", "Could not reach the Bot Framework connector: " + e.getMessage());
        }
    }

    private String token(String appId, String appPassword, String aadTenantId) {
        CachedToken cached = tokenCache.get(appId);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        String token = mintToken(appId, appPassword, aadTenantId);
        return token;
    }

    private String mintToken(String appId, String appPassword, String aadTenantId) {
        try {
            String form =
                    "grant_type=client_credentials"
                            + "&client_id="
                            + encode(appId)
                            + "&client_secret="
                            + encode(appPassword)
                            + "&scope="
                            + encode(TOKEN_SCOPE);
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            "https://login.microsoftonline.com/"
                                                    + encode(aadTenantId)
                                                    + "/oauth2/v2.0/token"))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node;
            try {
                node = objectMapper.readTree(response.body());
            } catch (Exception e) {
                node = objectMapper.nullNode();
            }
            String accessToken = node.path("access_token").asString(null);
            if (response.statusCode() != 200 || accessToken == null) {
                String detail = node.path("error_description").asString(response.body());
                throw new ChannelConfigurationException(
                        "teams",
                        "Microsoft Entra rejected these bot credentials ("
                                + response.statusCode()
                                + "): "
                                + detail);
            }
            long expiresIn = node.path("expires_in").asLong(3600);
            tokenCache.put(
                    appId,
                    new CachedToken(
                            accessToken,
                            Instant.now().plusSeconds(expiresIn).minus(TOKEN_EXPIRY_SKEW)));
            return accessToken;
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "teams", "Could not reach Microsoft Entra: " + e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record CachedToken(String value, Instant expiresAt) {}
}
