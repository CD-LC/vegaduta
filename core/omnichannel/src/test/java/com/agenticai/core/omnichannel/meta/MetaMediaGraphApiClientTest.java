package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * No live Meta credentials exist to test the media-upload/send calls against
 * (real Graph API acceptance is unverifiable from here) - same "stub the
 * real API with a local {@code com.sun.net.httpserver.HttpServer}" pattern
 * {@code DiscordApiClientMediaUploadTest}/{@code SlackApiClientMediaUploadTest}
 * already use for this same "forward an attachment" feature, so this pins
 * down the actual request shapes {@link MetaMediaGraphApiClient} builds
 * rather than Meta's real acceptance of them:
 * <ul>
 *   <li>WhatsApp's two-step protocol - multipart upload to {@code /media}
 *   returning an id, then a JSON send referencing that id - including that
 *   a caption is dropped (not sent, not erroring) for a type that doesn't
 *   support one (audio).</li>
 *   <li>Messenger/Instagram's one-step multipart attachment to {@code
 *   /me/messages}, plus the follow-up plain-text request this class sends
 *   for a caption (the Send API's attachment payload has no caption field
 *   of its own).</li>
 * </ul>
 */
class MetaMediaGraphApiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<byte[]> capturedBodies = new ArrayList<>();
    private final List<String> capturedPaths = new ArrayList<>();
    private final List<String> capturedContentTypes = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private MetaMediaGraphApiClient clientFor(HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        // Raw transport: the fixture is loopback, which the SSRF guard rightly refuses.
        return new MetaMediaGraphApiClient(MAPPER, baseUrl,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return server;
    }

    private void capture(HttpExchange exchange) throws IOException {
        capturedPaths.add(exchange.getRequestURI().getPath());
        capturedContentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
        capturedBodies.add(exchange.getRequestBody().readAllBytes());
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---- WhatsApp Cloud API ----

    @Test
    void sendWhatsAppMediaUploadsThenSendsByMediaId() throws Exception {
        server = startServer();
        server.createContext("/v21.0/PHONE123/media", exchange -> {
            try {
                capture(exchange);
                respondJson(exchange, 200, "{\"id\":\"MEDIA123\"}");
            } finally {
                exchange.close();
            }
        });
        server.createContext("/v21.0/PHONE123/messages", exchange -> {
            try {
                capture(exchange);
                respondJson(exchange, 200, "{\"messages\":[{\"id\":\"wamid.ABC\"}]}");
            } finally {
                exchange.close();
            }
        });
        MetaMediaGraphApiClient client = clientFor(server);

        String result = client.sendWhatsAppMedia(
            "PHONE123", "token-abc", "+1 555 123 4567",
            "hello".getBytes(StandardCharsets.UTF_8), "image/png", "photo.png", "a caption"
        );

        assertEquals(2, capturedPaths.size());
        assertEquals("/v21.0/PHONE123/media", capturedPaths.get(0));
        assertTrue(capturedContentTypes.get(0).startsWith("multipart/form-data"),
            "upload should be multipart/form-data: " + capturedContentTypes.get(0));
        String uploadBody = new String(capturedBodies.get(0), StandardCharsets.ISO_8859_1);
        assertTrue(uploadBody.contains("name=\"messaging_product\""), "should carry messaging_product field:\n" + uploadBody);
        assertTrue(uploadBody.contains("whatsapp"), "messaging_product value should be whatsapp:\n" + uploadBody);
        assertTrue(uploadBody.contains("name=\"file\"; filename=\"photo.png\""), "should carry the file part with the given filename:\n" + uploadBody);
        assertTrue(uploadBody.contains("Content-Type: image/png"), "file part should carry the given content type:\n" + uploadBody);
        assertTrue(uploadBody.contains("hello"), "file part should carry the raw bytes");

        assertEquals("/v21.0/PHONE123/messages", capturedPaths.get(1));
        assertEquals("application/json", capturedContentTypes.get(1));
        JsonNode payload = MAPPER.readTree(capturedBodies.get(1));
        assertEquals("whatsapp", payload.path("messaging_product").asText());
        // Meta wants digits only - "+1 555 123 4567" strips down to a bare number.
        assertEquals("15551234567", payload.path("to").asText());
        assertEquals("image", payload.path("type").asText());
        assertEquals("MEDIA123", payload.path("image").path("id").asText());
        assertEquals("a caption", payload.path("image").path("caption").asText());

        assertTrue(result.contains("wamid.ABC"), "result should surface Meta's returned message id: " + result);
    }

    @Test
    void sendWhatsAppMediaOmitsCaptionForAudio() throws Exception {
        server = startServer();
        server.createContext("/v21.0/PHONE123/media", exchange -> {
            try {
                respondJson(exchange, 200, "{\"id\":\"MEDIA123\"}");
            } finally {
                exchange.close();
            }
        });
        server.createContext("/v21.0/PHONE123/messages", exchange -> {
            try {
                capture(exchange);
                respondJson(exchange, 200, "{\"messages\":[{\"id\":\"wamid.ABC\"}]}");
            } finally {
                exchange.close();
            }
        });
        MetaMediaGraphApiClient client = clientFor(server);

        client.sendWhatsAppMedia(
            "PHONE123", "token-abc", "15551234567",
            "audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/ogg", "voice.ogg", "should be dropped, not sent"
        );

        JsonNode payload = MAPPER.readTree(capturedBodies.get(0));
        assertEquals("audio", payload.path("type").asText());
        assertFalse(payload.path("audio").has("caption"), "audio media object must not carry a caption field: " + payload);
    }

    @Test
    void sendWhatsAppMediaWrapsANonSuccessUploadStatusAsChannelDeliveryException() throws Exception {
        server = startServer();
        server.createContext("/v21.0/PHONE123/media", exchange -> {
            try {
                respondJson(exchange, 401, "{\"error\":{\"message\":\"Invalid OAuth access token\"}}");
            } finally {
                exchange.close();
            }
        });
        MetaMediaGraphApiClient client = clientFor(server);

        ChannelDeliveryException ex = assertThrows(ChannelDeliveryException.class, () ->
            client.sendWhatsAppMedia("PHONE123", "bad-token", "15551234567",
                "b".getBytes(StandardCharsets.UTF_8), "text/plain", "n.txt", null)
        );
        assertTrue(ex.getMessage().contains("401"), ex.getMessage());
    }

    // ---- Messenger / Instagram Send API ----

    @Test
    void sendPageAttachmentSendsMultipartThenFollowUpCaption() throws Exception {
        server = startServer();
        server.createContext("/v21.0/me/messages", exchange -> {
            try {
                capture(exchange);
                respondJson(exchange, 200, "{\"message_id\":\"m1\"}");
            } finally {
                exchange.close();
            }
        });
        MetaMediaGraphApiClient client = clientFor(server);

        String result = client.sendPageAttachment(
            "messenger", "page-token", "psid-1",
            "img-bytes".getBytes(StandardCharsets.UTF_8), "image/jpeg", "pic.jpg", "look at this"
        );

        assertEquals(2, capturedPaths.size());

        assertTrue(capturedContentTypes.get(0).startsWith("multipart/form-data"), capturedContentTypes.get(0));
        String attachmentBody = new String(capturedBodies.get(0), StandardCharsets.ISO_8859_1);
        assertTrue(attachmentBody.contains("\"type\":\"image\""), "attachment payload should be typed image:\n" + attachmentBody);
        assertTrue(attachmentBody.contains("name=\"filedata\"; filename=\"pic.jpg\""), attachmentBody);
        assertTrue(attachmentBody.contains("Content-Type: image/jpeg"), attachmentBody);
        assertTrue(attachmentBody.contains("img-bytes"), "should carry the raw bytes");

        assertEquals("application/json", capturedContentTypes.get(1));
        JsonNode followUp = MAPPER.readTree(capturedBodies.get(1));
        assertEquals("look at this", followUp.path("message").path("text").asText());
        assertEquals("psid-1", followUp.path("recipient").path("id").asText());

        assertTrue(result.contains("image"), result);
    }

    @Test
    void sendPageAttachmentSendsOnlyOneRequestWhenNoCaptionIsGiven() throws Exception {
        server = startServer();
        server.createContext("/v21.0/me/messages", exchange -> {
            try {
                capture(exchange);
                respondJson(exchange, 200, "{\"message_id\":\"m1\"}");
            } finally {
                exchange.close();
            }
        });
        MetaMediaGraphApiClient client = clientFor(server);

        client.sendPageAttachment(
            "instagram", "page-token", "igsid-1",
            "vid-bytes".getBytes(StandardCharsets.UTF_8), "video/mp4", "clip.mp4", null
        );

        assertEquals(1, capturedPaths.size(), "no caption should mean no follow-up text request");
    }

    @Test
    void sendPageAttachmentWrapsANonSuccessStatusAsChannelDeliveryException() throws Exception {
        server = startServer();
        server.createContext("/v21.0/me/messages", exchange -> {
            try {
                respondJson(exchange, 403, "{\"error\":{\"message\":\"permission denied\"}}");
            } finally {
                exchange.close();
            }
        });
        MetaMediaGraphApiClient client = clientFor(server);

        ChannelDeliveryException ex = assertThrows(ChannelDeliveryException.class, () ->
            client.sendPageAttachment("instagram", "page-token", "igsid-1",
                "b".getBytes(StandardCharsets.UTF_8), "text/plain", "n.txt", null)
        );
        assertTrue(ex.getMessage().contains("403"), ex.getMessage());
    }
}
