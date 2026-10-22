package com.agenticai.core.google;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gmail v1 REST calls, split across two scopes (see {@code GoogleScopes}): {@link
 * #listRecent}/{@link #readMessage} need {@code gmail.readonly}; {@link #sendMessage} needs {@code
 * gmail.send} instead - a tenant that only granted one of the two gets a normal 403 from Google on
 * the other, which {@code GoogleGmailToolCallback} surfaces back to the model as an error rather
 * than pretending the capability doesn't exist.
 */
@Component
public class GoogleGmailClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final GoogleOAuthService oauthService;

    public GoogleGmailClient(ObjectMapper objectMapper, GoogleOAuthService oauthService) {
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    /**
     * @param query Gmail search syntax, e.g. "is:unread", or blank for the most recent messages
     *     overall.
     */
    public List<MessageSummary> listRecent(String query, int maxResults) {
        String q = (query == null || query.isBlank()) ? "" : "&q=" + urlEncode(query);
        JsonNode list =
                get(BASE + "/messages?maxResults=" + Math.max(1, Math.min(maxResults, 10)) + q);
        List<MessageSummary> summaries = new ArrayList<>();
        for (JsonNode item : list.path("messages")) {
            String id = item.path("id").asString(null);
            if (id == null) continue;
            summaries.add(fetchSummary(id));
        }
        return summaries;
    }

    private MessageSummary fetchSummary(String id) {
        JsonNode message =
                get(
                        BASE
                                + "/messages/"
                                + id
                                + "?format=metadata&metadataHeaders=Subject&metadataHeaders=From");
        String subject = null;
        String from = null;
        for (JsonNode header : message.path("payload").path("headers")) {
            String name = header.path("name").asString("");
            if ("Subject".equalsIgnoreCase(name)) subject = header.path("value").asString(null);
            if ("From".equalsIgnoreCase(name)) from = header.path("value").asString(null);
        }
        return new MessageSummary(id, from, subject, message.path("snippet").asString(""));
    }

    public String readMessage(String messageId) {
        JsonNode message = get(BASE + "/messages/" + urlEncode(messageId) + "?format=full");
        String text = extractPlainText(message.path("payload"));
        return text != null ? text : message.path("snippet").asString("");
    }

    private String extractPlainText(JsonNode part) {
        if ("text/plain".equals(part.path("mimeType").asString())) {
            String data = part.path("body").path("data").asString(null);
            if (data != null) {
                return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
            }
        }
        for (JsonNode child : part.path("parts")) {
            String found = extractPlainText(child);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * @return a short, human-readable log line, same contract as the WhatsApp channel clients' send
     *     methods.
     */
    public String sendMessage(String to, String subject, String body) {
        String raw =
                "To: "
                        + to
                        + "\r\n"
                        + "Subject: "
                        + subject
                        + "\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                        + body;
        String encoded =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        try {
            String payload = objectMapper.writeValueAsString(Map.of("raw", encoded));
            HttpRequest request =
                    authedRequest(BASE + "/messages/send")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
            HttpResponse<String> response = send(request);
            if (response.statusCode() >= 300) {
                throw new GoogleApiException(
                        "Gmail send failed (" + response.statusCode() + "): " + response.body());
            }
            return "Sent email to " + to + " via Gmail.";
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException("Could not send via Gmail: " + e.getMessage(), e);
        }
    }

    private JsonNode get(String uri) {
        HttpRequest request = authedRequest(uri).GET().build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 300) {
            throw new GoogleApiException(
                    "Gmail API call failed (" + response.statusCode() + "): " + response.body());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not parse Gmail API response: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder authedRequest(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + oauthService.currentAccessToken());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GoogleApiException("Could not reach the Gmail API: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record MessageSummary(String id, String from, String subject, String snippet) {}
}
