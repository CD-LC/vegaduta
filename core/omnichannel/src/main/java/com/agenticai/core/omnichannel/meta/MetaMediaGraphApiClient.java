package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Media-send counterpart to {@link MetaGraphApiClient} - deliberately its own class rather than
 * growing that one further (already a wide surface): every method here builds a multipart/form-data
 * request, a body- construction concern the plain-JSON text-send methods over there never need, so
 * keeping them apart keeps both files readable. Same "no SDK, plain {@link HttpClient} for a
 * handful of REST calls" choice as {@code MetaGraphApiClient}, same host/version/{@code
 * Authorization: Bearer} convention.
 *
 * <p>Two different upload shapes, because Meta's Graph API itself has two different shapes here:
 *
 * <ul>
 *   <li><b>WhatsApp Cloud API</b> is a real two-step protocol: upload bytes to {@code POST
 *       /{phone-number-id}/media} to get back a media {@code id}, then reference that id in a
 *       normal JSON {@code POST /{phone-number-id}/messages} call - see {@link #sendWhatsAppMedia},
 *       mirroring {@code MetaGraphApiClient#sendTextMessage}'s JSON-send shape for the second step.
 *   <li><b>Messenger/Instagram Send API</b> (Meta proxies Instagram business messaging through the
 *       linked Page, same as {@code MetaGraphApiClient#sendInstagramMessage}) accepts the bytes
 *       directly in one multipart {@code POST /me/messages} call - no separate upload/id step, but
 *       also no caption field on the attachment payload, so a non-blank caption goes out as a plain
 *       follow-up text message rather than being silently dropped - see {@link
 *       #sendPageAttachment}.
 * </ul>
 *
 * <p>The Graph host is overridable ({@code app.omnichannel.meta.graph-base-url}) purely so a test
 * can point this at a local stub server instead of the real Meta API - production never sets the
 * property and gets the real default.
 */
@Component
public class MetaMediaGraphApiClient {

    private static final String GRAPH_VERSION = "v21.0";
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String graphBaseUrl;

    @Autowired
    public MetaMediaGraphApiClient(
            ObjectMapper objectMapper,
            @Value("${app.omnichannel.meta.graph-base-url:https://graph.facebook.com}")
                    String graphBaseUrl) {
        this(
                objectMapper,
                graphBaseUrl,
                GuardedJdkHttpClient.defaults(Duration.ofSeconds(10), UPLOAD_TIMEOUT, false));
    }

    /**
     * Test seam: a raw transport for loopback fixture servers, which the SSRF guard rightly
     * refuses.
     */
    MetaMediaGraphApiClient(ObjectMapper objectMapper, String graphBaseUrl, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.graphBaseUrl = graphBaseUrl.replaceAll("/+$", "");
    }

    /** Optional {@code meta-graph} circuit breaker (default off); see {@link MetaGraphGuard}. */
    private volatile CircuitBreakerRegistry breakerRegistry;

    @Autowired(required = false)
    public void setCircuitBreakerRegistry(CircuitBreakerRegistry registry) {
        this.breakerRegistry = registry;
    }

    // ---- WhatsApp Cloud API ----

    /**
     * @return a short, human-readable log line, same contract as {@code
     *     MetaGraphApiClient.sendTextMessage}.
     */
    public String sendWhatsAppMedia(
            String phoneNumberId,
            String accessToken,
            String to,
            byte[] bytes,
            String contentType,
            String filename,
            String caption) {
        String mediaId =
                uploadWhatsAppMedia(phoneNumberId, accessToken, bytes, contentType, filename);
        return sendWhatsAppMediaMessage(
                phoneNumberId, accessToken, to, mediaId, contentType, filename, caption);
    }

    private String uploadWhatsAppMedia(
            String phoneNumberId,
            String accessToken,
            byte[] bytes,
            String contentType,
            String filename) {
        String boundary = "----vegaduta-" + UUID.randomUUID();
        Multipart body = new Multipart(boundary);
        body.addField("messaging_product", "whatsapp");
        body.addField(
                "type",
                StringUtils.hasText(contentType) ? contentType : "application/octet-stream");
        body.addFile(
                "file",
                StringUtils.hasText(filename) ? filename : "attachment",
                contentType,
                bytes);
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        graphBaseUrl
                                                + "/"
                                                + GRAPH_VERSION
                                                + "/"
                                                + phoneNumberId
                                                + "/media"))
                        .timeout(UPLOAD_TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.build()))
                        .build();
        try {
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        "whatsapp",
                        "Meta media upload failed ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            String mediaId = objectMapper.readTree(response.body()).path("id").asText(null);
            if (mediaId == null) {
                throw new ChannelDeliveryException(
                        "whatsapp", "Meta media upload response had no id: " + response.body());
            }
            return mediaId;
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "whatsapp", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    private String sendWhatsAppMediaMessage(
            String phoneNumberId,
            String accessToken,
            String to,
            String mediaId,
            String contentType,
            String filename,
            String caption) {
        String type = whatsAppMediaType(contentType);
        Map<String, Object> mediaObject = new LinkedHashMap<>();
        mediaObject.put("id", mediaId);
        // Meta rejects a caption/filename param on types that don't support
        // it (audio, sticker) rather than silently ignoring it - only attach
        // what each type actually allows.
        if (StringUtils.hasText(caption)
                && (type.equals("image") || type.equals("video") || type.equals("document"))) {
            mediaObject.put("caption", caption);
        }
        if (type.equals("document") && StringUtils.hasText(filename)) {
            mediaObject.put("filename", filename);
        }
        Map<String, Object> payload =
                Map.of(
                        "messaging_product",
                        "whatsapp",
                        "to",
                        normalizeRecipient(to),
                        "type",
                        type,
                        type,
                        mediaObject);
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            graphBaseUrl
                                                    + "/"
                                                    + GRAPH_VERSION
                                                    + "/"
                                                    + phoneNumberId
                                                    + "/messages"))
                            .timeout(UPLOAD_TIMEOUT)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(payload)))
                            .build();
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        "whatsapp",
                        "Meta Graph API media send failed ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            String messageId = "unknown";
            try {
                JsonNode messages = objectMapper.readTree(response.body()).path("messages");
                if (messages.isArray() && messages.size() > 0) {
                    messageId = messages.get(0).path("id").asText("unknown");
                }
            } catch (Exception ignored) {
                // Leave messageId as "unknown" - Meta already accepted the send (status checked
                // above).
            }
            return "Sent "
                    + type
                    + " attachment to "
                    + to
                    + " via Meta Cloud API (id "
                    + messageId
                    + ").";
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "whatsapp", "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    private static String whatsAppMediaType(String contentType) {
        if (contentType == null) {
            return "document";
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("image/")) {
            return "image";
        }
        if (lower.startsWith("audio/")) {
            return "audio";
        }
        if (lower.startsWith("video/")) {
            return "video";
        }
        return "document";
    }

    // Meta's `to` field wants digits only (country code + number, no "+") -
    // identical rule to MetaGraphApiClient.normalizeRecipient, duplicated
    // rather than shared because that method is private on that class.
    private static String normalizeRecipient(String to) {
        return to == null ? "" : to.replaceAll("[^0-9]", "");
    }

    // ---- Messenger / Instagram Send API ----

    /**
     * @return a short, human-readable log line describing what happened.
     */
    public String sendPageAttachment(
            String channelLabel,
            String pageAccessToken,
            String recipientId,
            byte[] bytes,
            String contentType,
            String filename,
            String caption) {
        String type = messengerAttachmentType(contentType);
        String boundary = "----vegaduta-" + UUID.randomUUID();
        Multipart body = new Multipart(boundary);
        try {
            body.addField("recipient", objectMapper.writeValueAsString(Map.of("id", recipientId)));
            body.addField(
                    "message",
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "attachment",
                                    Map.of(
                                            "type",
                                            type,
                                            "payload",
                                            Map.of("is_reusable", false)))));
            body.addField("messaging_type", "RESPONSE");
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    channelLabel, "Could not build attachment request: " + e.getMessage());
        }
        body.addFile(
                "filedata",
                StringUtils.hasText(filename) ? filename : "attachment",
                contentType,
                bytes);

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(graphBaseUrl + "/" + GRAPH_VERSION + "/me/messages"))
                        .timeout(UPLOAD_TIMEOUT)
                        .header("Authorization", "Bearer " + pageAccessToken)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.build()))
                        .build();
        try {
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        channelLabel,
                        "Meta Graph API attachment send failed ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    channelLabel, "Could not reach the Meta Graph API: " + e.getMessage());
        }

        // Send API's attachment object has no caption field (unlike
        // WhatsApp's media object) - a non-blank caption goes out as a plain
        // follow-up text message instead of being silently dropped.
        if (StringUtils.hasText(caption)) {
            sendFollowUpText(channelLabel, pageAccessToken, recipientId, caption);
        }
        return "Sent " + type + " attachment to " + recipientId + " via Meta Graph API.";
    }

    private void sendFollowUpText(
            String channelLabel, String pageAccessToken, String recipientId, String text) {
        try {
            Map<String, Object> payload =
                    Map.of(
                            "recipient", Map.of("id", recipientId),
                            "message", Map.of("text", text),
                            "messaging_type", "RESPONSE");
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(graphBaseUrl + "/" + GRAPH_VERSION + "/me/messages"))
                            .timeout(UPLOAD_TIMEOUT)
                            .header("Authorization", "Bearer " + pageAccessToken)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(payload)))
                            .build();
            HttpResponse<String> response =
                    MetaGraphGuard.send(breakerRegistry, httpClient, request);
            if (response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        channelLabel,
                        "Meta Graph API caption send failed ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    channelLabel, "Could not reach the Meta Graph API: " + e.getMessage());
        }
    }

    private static String messengerAttachmentType(String contentType) {
        if (contentType == null) {
            return "file";
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("image/")) {
            return "image";
        }
        if (lower.startsWith("audio/")) {
            return "audio";
        }
        if (lower.startsWith("video/")) {
            return "video";
        }
        // Messenger's Send API calls the generic bucket "file" (not
        // "document" - that's WhatsApp's naming).
        return "file";
    }

    /**
     * Minimal multipart/form-data body builder - no extra dependency for the handful of parts these
     * calls ever need.
     */
    private static final class Multipart {
        private final String boundary;
        private final List<byte[]> parts = new ArrayList<>();

        Multipart(String boundary) {
            this.boundary = boundary;
        }

        void addField(String name, String value) {
            parts.add(
                    ("--"
                                    + boundary
                                    + "\r\n"
                                    + "Content-Disposition: form-data; name=\""
                                    + name
                                    + "\"\r\n\r\n"
                                    + value
                                    + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
        }

        void addFile(String fieldName, String filename, String contentType, byte[] content) {
            String header =
                    "--"
                            + boundary
                            + "\r\n"
                            + "Content-Disposition: form-data; name=\""
                            + fieldName
                            + "\"; filename=\""
                            + filename
                            + "\"\r\n"
                            + "Content-Type: "
                            + (StringUtils.hasText(contentType)
                                    ? contentType
                                    : "application/octet-stream")
                            + "\r\n\r\n";
            parts.add(header.getBytes(StandardCharsets.UTF_8));
            parts.add(content);
            parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        byte[] build() {
            int total = 0;
            for (byte[] part : parts) {
                total += part.length;
            }
            byte[] footer = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            total += footer.length;
            byte[] result = new byte[total];
            int offset = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, result, offset, part.length);
                offset += part.length;
            }
            System.arraycopy(footer, 0, result, offset, footer.length);
            return result;
        }
    }
}
