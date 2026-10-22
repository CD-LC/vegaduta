package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Plain {@link HttpClient} wrapper for the two Discord REST API calls this platform needs. Unlike
 * {@code SlackApiClient}, Discord's API uses real non-2xx HTTP status codes for failures (a 401 for
 * a bad token, a 404 for an unknown channel, etc.) - closer to {@code MetaGraphApiClient}'s
 * contract than Slack's always-200 one.
 *
 * <p>Discord's Gateway (the persistent WebSocket connection that receives ordinary {@code
 * MESSAGE_CREATE} events) is deliberately NOT implemented here - see {@code
 * DiscordWebhookController}'s javadoc for why inbound messages instead arrive via the standalone
 * {@code channels/discord} Node service. This client only ever makes outbound, one-shot REST calls,
 * which need no persistent connection at all.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class DiscordApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /**
     * Overridable only so tests can point this at a local {@code HttpServer} instead of the real
     * Discord API.
     */
    private final String apiBase;

    public DiscordApiClient(
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            @Value("${app.omnichannel.discord.api-base:https://discord.com/api/v10}")
                    String apiBase) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
        this.apiBase = apiBase;
    }

    /**
     * Validates a Bot Token with a real call and returns the bot's own identity - same "prove it
     * before persisting" reasoning as every other credential activation in this codebase. A Discord
     * bot's user ID and its Application ID are the same value, so {@code id} here doubles as the
     * Application ID used to link this tenant via {@link
     * com.agenticai.core.tenancy.ExternalAccountProvider#DISCORD}.
     */
    public BotInfo fetchBotInfo(String botToken) {
        HttpResponse<String> response = send("GET", apiBase + "/users/@me", botToken, null);
        JsonNode node = parseOrThrow(response, "discord", "Discord rejected this Bot Token");
        return new BotInfo(node.path("id").asString(null), node.path("username").asString(null));
    }

    /**
     * @return a short, human-readable log line describing what happened.
     */
    public String sendMessage(String botToken, String channelId, String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("content", text));
            HttpResponse<String> response =
                    send("POST", apiBase + "/channels/" + channelId + "/messages", botToken, body);
            parseOrThrowDelivery(response, channelId);
            return "Sent Discord message to channel " + channelId + ".";
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "discord", "Could not reach the Discord API: " + e.getMessage());
        }
    }

    /**
     * {@code POST /channels/{channel.id}/messages} with a real attachment - Discord's
     * multipart/form-data flow (unlike {@link #sendMessage}'s plain JSON body). Uses {@link
     * RestClient} rather than the raw {@link #httpClient} this class otherwise standardizes on,
     * because hand-rolling a multipart body (boundary, per-part headers, CRLFs) over {@code
     * java.net.http.HttpClient} is exactly the kind of fiddly wire-format code Spring's {@link
     * MultiValueMap}-based multipart writer already gets right - same choice {@code
     * ElevenLabsClient#cloneVoice} made for its own file-upload call.
     *
     * <p>Per Discord's docs the {@code payload_json} part carries the message body (here just
     * {@code content} for the caption, plus an {@code attachments} array describing each file) as
     * {@code application/json}, and each file rides in its own {@code files[N]} part whose index
     * must match the {@code id} of its {@code attachments} entry - this method only ever sends one
     * file, so both are always {@code "0"}/{@code files[0]}.
     */
    public String sendMediaMessage(
            String botToken,
            String channelId,
            byte[] bytes,
            String contentType,
            String filename,
            String caption) {
        try {
            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("id", "0");
            attachment.put(
                    "filename", filename == null || filename.isBlank() ? "attachment" : filename);

            Map<String, Object> payload = new LinkedHashMap<>();
            if (caption != null && !caption.isBlank()) {
                payload.put("content", caption);
            }
            payload.put("attachments", List.of(attachment));

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            HttpHeaders payloadHeaders = new HttpHeaders();
            payloadHeaders.setContentType(MediaType.APPLICATION_JSON);
            body.add(
                    "payload_json",
                    new HttpEntity<>(objectMapper.writeValueAsString(payload), payloadHeaders));

            ByteArrayResource fileResource =
                    new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return filename == null || filename.isBlank() ? "attachment" : filename;
                        }
                    };
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(
                    contentType != null && !contentType.isBlank()
                            ? MediaType.parseMediaType(contentType)
                            : MediaType.APPLICATION_OCTET_STREAM);
            body.add("files[0]", new HttpEntity<>(fileResource, fileHeaders));

            restClient
                    .post()
                    .uri(apiBase + "/channels/{channelId}/messages", channelId)
                    .header("Authorization", "Bot " + botToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return "Sent Discord media message to channel " + channelId + ".";
        } catch (RestClientResponseException e) {
            throw new ChannelDeliveryException(
                    "discord",
                    "Discord media send to channel " + channelId + " failed: " + describeError(e));
        } catch (RestClientException e) {
            throw new ChannelDeliveryException(
                    "discord", "Could not reach the Discord API: " + e.getMessage());
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "discord", "Could not build the Discord media message: " + e.getMessage());
        }
    }

    private static String describeError(RestClientResponseException e) {
        String responseBody = e.getResponseBodyAsString();
        return (responseBody == null || responseBody.isBlank())
                ? "HTTP " + e.getStatusCode().value()
                : "HTTP " + e.getStatusCode().value() + ": " + responseBody.trim();
    }

    private HttpResponse<String> send(String method, String url, String botToken, String jsonBody) {
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bot " + botToken)
                            .header("Content-Type", "application/json; charset=utf-8");
            builder =
                    "POST".equals(method)
                            ? builder.POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            jsonBody == null ? "" : jsonBody))
                            : builder.GET();
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "discord", "Could not reach the Discord API: " + e.getMessage());
        }
    }

    private JsonNode parseOrThrow(
            HttpResponse<String> response, String channel, String failureMessage) {
        JsonNode node;
        try {
            node = objectMapper.readTree(response.body());
        } catch (Exception e) {
            node = objectMapper.nullNode();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ChannelConfigurationException(
                    channel,
                    failureMessage + ": " + node.path("message").asString(response.body()));
        }
        return node;
    }

    private void parseOrThrowDelivery(HttpResponse<String> response, String channelId) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            JsonNode node;
            try {
                node = objectMapper.readTree(response.body());
            } catch (Exception e) {
                node = objectMapper.nullNode();
            }
            throw new ChannelDeliveryException(
                    "discord",
                    "Discord send to channel "
                            + channelId
                            + " failed: "
                            + node.path("message").asString(response.body()));
        }
    }

    public record BotInfo(String applicationId, String username) {}
}
