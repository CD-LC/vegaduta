package com.agenticai.core.omnichannel.x;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Plain {@link HttpClient} wrapper for the two X API v2 endpoints {@code XDmPollingJob}/{@code
 * XChannelAdapter} need. Uses real HTTP status codes for failures, same contract as {@code
 * DiscordApiClient}/{@code MetaGraphApiClient} (unlike Slack's always-200 body).
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class XApiClient {

    private static final String API_BASE = "https://api.x.com/2";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;

    public XApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches the single most recent DM event id for this account, without processing anything -
     * used once, right after {@link XOAuthService#connect}, to seed {@code lastDmEventId} so the
     * very first poll doesn't treat this account's entire DM history as "new".
     *
     * @return the latest event id, or {@code null} if this account has no DM history at all.
     */
    public String fetchLatestEventId(String accessToken) {
        JsonNode root = getDmEvents(accessToken, null);
        JsonNode first =
                root.path("data").isArray() && root.path("data").size() > 0
                        ? root.path("data").get(0)
                        : null;
        return first == null ? null : first.path("id").asText(null);
    }

    /**
     * @param sinceEventId the last event id this tenant already processed (never {@code null} in
     *     normal polling - only {@link #fetchLatestEventId} calls with {@code null}).
     * @return new {@code MessageCreate} events NOT sent by {@code ownXUserId}, oldest first (so the
     *     caller processes them in the order they actually happened), plus the new cursor to
     *     persist regardless of whether any were returned.
     */
    public PollResult fetchNewMessagesSince(
            String accessToken, String sinceEventId, String ownXUserId) {
        JsonNode root = getDmEvents(accessToken, null);
        JsonNode data = root.path("data");
        List<DmEvent> newEvents = new ArrayList<>();
        String newCursor = sinceEventId;
        boolean sawCursor = false;
        if (data.isArray()) {
            for (int i = 0; i < data.size(); i++) {
                JsonNode node = data.get(i);
                String id = node.path("id").asText(null);
                if (i == 0 && id != null) {
                    newCursor = id;
                }
                if (id != null && id.equals(sinceEventId)) {
                    sawCursor = true;
                    break; // everything from here back was already processed
                }
                if (!"MessageCreate".equals(node.path("event_type").asText())) {
                    continue;
                }
                String senderId = node.path("sender_id").asText(null);
                String text = node.path("text").asText(null);
                String conversationId = node.path("dm_conversation_id").asText(null);
                if (senderId == null
                        || text == null
                        || conversationId == null
                        || senderId.equals(ownXUserId)) {
                    continue; // drop our own sent messages, same reasoning as
                    // SlackWebhookController's self-message filter
                }
                newEvents.add(new DmEvent(id, conversationId, senderId, text));
            }
        }
        if (!sawCursor && sinceEventId != null && !newEvents.isEmpty()) {
            // The cursor fell off the end of this page (more than one page's worth of DMs arrived
            // between polls) -
            // still process what we found on this page; the next poll cycle will pick up the rest
            // naturally since
            // newCursor already advanced to this page's newest id.
        }
        Collections.reverse(newEvents); // API returns newest-first; process oldest-first
        return new PollResult(newEvents, newCursor);
    }

    private JsonNode getDmEvents(String accessToken, String paginationToken) {
        String url =
                API_BASE
                        + "/dm_events?max_results=100&event_types=MessageCreate"
                        + "&dm_event.fields=id,text,event_type,dm_conversation_id,sender_id,created_at"
                        + (paginationToken != null ? "&pagination_token=" + paginationToken : "");
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new XApiException(
                        "X rejected the dm_events request ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (XApiException e) {
            throw e;
        } catch (Exception e) {
            throw new XApiException("Could not reach X's dm_events endpoint: " + e.getMessage(), e);
        }
    }

    /**
     * @return a short, human-readable log line describing what happened.
     */
    public String sendDm(String accessToken, String dmConversationId, String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("text", text));
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            API_BASE
                                                    + "/dm_conversations/"
                                                    + dmConversationId
                                                    + "/messages"))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        "x",
                        "X rejected the DM send ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            return "Sent X DM to conversation " + dmConversationId + ".";
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "x", "Could not reach X's dm_conversations endpoint: " + e.getMessage());
        }
    }

    public record DmEvent(String id, String dmConversationId, String senderId, String text) {}

    public record PollResult(List<DmEvent> newEvents, String newCursor) {}
}
