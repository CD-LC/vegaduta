package com.agenticai.core.omnichannel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers {@link DiscordApiClient#sendMediaMessage} - the multipart/form-data {@code POST
 * /channels/{channel.id}/messages} call this class added alongside {@link DiscordApiClient}'s
 * existing plain-JSON {@link DiscordApiClient#sendMessage}. Since {@link RestClient}'s multipart
 * writer builds the actual wire body (boundary, per-part headers), this test exists specifically to
 * pin down that the resulting request matches Discord's documented contract: a {@code payload_json}
 * part (JSON content type, an {@code attachments} array whose entry {@code id} is {@code "0"}) plus
 * a {@code files[0]} part carrying the raw bytes.
 *
 * <p>Uses a real local {@code com.sun.net.httpserver.HttpServer} (JDK- provided, no extra test
 * dependency), same reasoning/pattern as {@code PhonePeGatewayClientNetworkTest} and {@code
 * SlackApiClientMediaUploadTest} - the constructor's {@code apiBase} parameter (added specifically
 * to make this possible) points at this local server instead of the real {@code
 * https://discord.com/api/v10}.
 */
class DiscordApiClientMediaUploadTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String BOT_TOKEN = "dummy-bot-token";
    private static final String CHANNEL_ID = "c123";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private DiscordApiClient clientFor(HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new DiscordApiClient(MAPPER, RestClient.builder(), baseUrl);
    }

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return server;
    }

    private static void respondJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void sendMediaMessageBuildsAMultipartRequestWithPayloadJsonAndFilesZeroParts()
            throws Exception {
        byte[] fileBytes = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);
        String[] capturedAuth = new String[1];
        String[] capturedContentType = new String[1];
        byte[][] capturedRawBody = new byte[1][];

        server = startServer();
        server.createContext(
                "/channels/" + CHANNEL_ID + "/messages",
                exchange -> {
                    try {
                        capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
                        capturedContentType[0] =
                                exchange.getRequestHeaders().getFirst("Content-Type");
                        capturedRawBody[0] = exchange.getRequestBody().readAllBytes();
                        respondJson(exchange, 200, "{\"id\":\"msg1\"}");
                    } finally {
                        exchange.close();
                    }
                });
        DiscordApiClient client = clientFor(server);

        String result =
                client.sendMediaMessage(
                        BOT_TOKEN,
                        CHANNEL_ID,
                        fileBytes,
                        "image/png",
                        "photo.png",
                        "here's a photo");

        assertEquals("Sent Discord media message to channel c123.", result);
        assertEquals("Bot " + BOT_TOKEN, capturedAuth[0]);
        assertTrue(
                capturedContentType[0].startsWith("multipart/form-data"),
                "Content-Type should be multipart/form-data: " + capturedContentType[0]);

        // Raw multipart body assertions - not a full multipart parse, but enough to pin the
        // documented shape: a payload_json part (JSON content-type, attachments[0].id == "0"),
        // and a files[0] part carrying the actual file bytes.
        String rawBody = new String(capturedRawBody[0], StandardCharsets.ISO_8859_1);
        assertTrue(
                rawBody.contains("name=\"payload_json\""),
                "multipart body should contain a payload_json part:\n" + rawBody);
        assertTrue(
                rawBody.contains("name=\"files[0]\""),
                "multipart body should contain a files[0] part:\n" + rawBody);
        assertTrue(
                rawBody.contains("\"content\":\"here's a photo\""),
                "payload_json should carry the caption as content:\n" + rawBody);
        assertTrue(
                rawBody.contains("\"id\":\"0\""),
                "attachments[0].id must be \"0\" to match files[0]:\n" + rawBody);
        assertTrue(
                rawBody.contains("\"filename\":\"photo.png\""),
                "attachments[0].filename should be the given filename:\n" + rawBody);
        assertTrue(
                rawBody.contains(new String(fileBytes, StandardCharsets.ISO_8859_1)),
                "the raw file bytes should be present in the files[0] part");
    }

    @Test
    void sendMediaMessageOmitsContentWhenNoCaptionIsGiven() throws Exception {
        byte[][] capturedRawBody = new byte[1][];
        server = startServer();
        server.createContext(
                "/channels/" + CHANNEL_ID + "/messages",
                exchange -> {
                    try {
                        capturedRawBody[0] = exchange.getRequestBody().readAllBytes();
                        respondJson(exchange, 200, "{\"id\":\"msg1\"}");
                    } finally {
                        exchange.close();
                    }
                });
        DiscordApiClient client = clientFor(server);

        client.sendMediaMessage(
                BOT_TOKEN,
                CHANNEL_ID,
                "bytes".getBytes(StandardCharsets.UTF_8),
                "text/plain",
                "note.txt",
                null);

        String rawBody = new String(capturedRawBody[0], StandardCharsets.ISO_8859_1);
        assertTrue(rawBody.contains("\"attachments\""));
        assertTrue(
                !rawBody.contains("\"content\":"),
                "no content field should be sent when there is no caption:\n" + rawBody);
    }

    @Test
    void sendMediaMessageWrapsANonSuccessStatusAsChannelDeliveryException() throws Exception {
        server = startServer();
        server.createContext(
                "/channels/" + CHANNEL_ID + "/messages",
                exchange -> {
                    try {
                        respondJson(
                                exchange, 403, "{\"message\":\"Missing Access\",\"code\":50001}");
                    } finally {
                        exchange.close();
                    }
                });
        DiscordApiClient client = clientFor(server);

        ChannelDeliveryException ex =
                assertThrows(
                        ChannelDeliveryException.class,
                        () ->
                                client.sendMediaMessage(
                                        BOT_TOKEN,
                                        CHANNEL_ID,
                                        "b".getBytes(StandardCharsets.UTF_8),
                                        "text/plain",
                                        "n.txt",
                                        null));
        assertTrue(ex.getMessage().contains("403"));
    }
}
