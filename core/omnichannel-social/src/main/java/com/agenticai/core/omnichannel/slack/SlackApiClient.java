package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Plain {@link HttpClient} wrapper for the handful of Slack Web API calls this platform needs -
 * same "no SDK for a few REST endpoints" choice as {@code MetaGraphApiClient}/{@code
 * TelegramOutputService}. Slack's Web API is unusual among the providers this platform integrates
 * with in that EVERY call - including outright failures like an invalid token - returns HTTP 200,
 * with a JSON {@code {"ok": false, "error": "..."}} body instead of a non-2xx status; every method
 * here checks {@code ok} explicitly rather than relying on the status code the way {@code
 * MetaGraphApiClient} does.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class SlackApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Overridable only so tests can point this at a local {@code HttpServer} instead of the real
     * Slack API.
     */
    private final String apiBase;

    @Autowired
    public SlackApiClient(
            ObjectMapper objectMapper,
            @Value("${app.omnichannel.slack.api-base:https://slack.com/api}") String apiBase) {
        this(objectMapper, apiBase, GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false));
    }

    /**
     * Test seam: a raw transport for loopback fixture servers, which the SSRF guard rightly
     * refuses.
     */
    SlackApiClient(ObjectMapper objectMapper, String apiBase, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
    }

    /**
     * Validates a Bot Token with a real, non-billed call and returns the workspace/bot identity
     * Slack itself reports - same "prove it before persisting" reasoning as every other credential
     * activation in this codebase, and the only reliable way to learn this bot's own user ID
     * (needed to filter the bot's own messages back out of inbound events).
     */
    public AuthInfo authTest(String botToken) {
        JsonNode node = post(apiBase + "/auth.test", botToken, Map.of());
        if (!node.path("ok").asBoolean(false)) {
            throw new ChannelConfigurationException(
                    "slack",
                    "Slack rejected this Bot Token: " + node.path("error").asText("unknown error"));
        }
        return new AuthInfo(
                node.path("team_id").asText(null),
                node.path("team").asText(null),
                node.path("user_id").asText(null));
    }

    /**
     * @return a short, human-readable log line describing what happened.
     */
    public String sendMessage(String botToken, String channelOrUserId, String text) {
        JsonNode node =
                post(
                        apiBase + "/chat.postMessage",
                        botToken,
                        Map.of("channel", channelOrUserId, "text", text));
        if (!node.path("ok").asBoolean(false)) {
            throw new ChannelDeliveryException(
                    "slack",
                    "Slack chat.postMessage failed: " + node.path("error").asText("unknown error"));
        }
        return "Sent Slack message to " + channelOrUserId + ".";
    }

    /**
     * Step 1 of Slack's current (2024+) file-upload flow - {@code files.getUploadURLExternal}
     * returns a short-lived, pre-signed URL to POST the raw file bytes to next (see {@link
     * #uploadFileBytes}), plus the {@code file_id} that {@link #completeUpload} later associates
     * with a channel. Unlike most Slack Web API methods this one is documented to accept either
     * JSON or form-encoded bodies, so it reuses the same {@link #post} helper as every other JSON
     * call here.
     */
    public UploadUrlInfo getUploadUrl(String botToken, String filename, int length) {
        JsonNode node =
                post(
                        apiBase + "/files.getUploadURLExternal",
                        botToken,
                        Map.of("filename", filename, "length", length));
        if (!node.path("ok").asBoolean(false)) {
            throw new ChannelDeliveryException(
                    "slack",
                    "Slack files.getUploadURLExternal failed: "
                            + node.path("error").asText("unknown error"));
        }
        return new UploadUrlInfo(
                node.path("upload_url").asText(null), node.path("file_id").asText(null));
    }

    /**
     * Step 2: POSTs the raw bytes to the pre-signed {@code upload_url} from {@link #getUploadUrl} -
     * per Slack's docs this is a plain {@code application/octet-stream} body, NOT multipart and NOT
     * JSON, and the URL is already signed so no {@code Authorization} header is sent (or needed).
     * Slack does not return a JSON body here, just a 200 on success.
     */
    public void uploadFileBytes(String uploadUrl, byte[] bytes) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(uploadUrl))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/octet-stream")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        "slack",
                        "Slack file upload PUT to the signed URL failed with HTTP "
                                + response.statusCode());
            }
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "slack", "Could not reach Slack's file upload URL: " + e.getMessage());
        }
    }

    /**
     * Step 3: {@code files.completeUploadExternal} finalizes the upload, associates it with {@code
     * channelId}, and posts {@code caption} as the file's {@code initial_comment} (Slack's normal
     * share message) if given.
     */
    public void completeUpload(
            String botToken, String fileId, String filename, String channelId, String caption) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("id", fileId);
        file.put("title", filename);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", List.of(file));
        body.put("channel_id", channelId);
        if (caption != null && !caption.isBlank()) {
            body.put("initial_comment", caption);
        }
        JsonNode node = post(apiBase + "/files.completeUploadExternal", botToken, body);
        if (!node.path("ok").asBoolean(false)) {
            throw new ChannelDeliveryException(
                    "slack",
                    "Slack files.completeUploadExternal failed: "
                            + node.path("error").asText("unknown error"));
        }
    }

    private JsonNode post(String url, String botToken, Map<String, Object> body) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + botToken)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(body)))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (ChannelConfigurationException | ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "slack", "Could not reach the Slack API: " + e.getMessage());
        }
    }

    public record AuthInfo(String teamId, String teamName, String botUserId) {}

    public record UploadUrlInfo(String uploadUrl, String fileId) {}
}
