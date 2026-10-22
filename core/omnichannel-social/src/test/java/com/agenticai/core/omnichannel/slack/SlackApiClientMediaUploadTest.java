package com.agenticai.core.omnichannel.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the 3-step media-upload flow this class added on top of {@link SlackApiClient}'s existing
 * {@code chat.postMessage}/{@code auth.test} calls: {@link SlackApiClient#getUploadUrl}, {@link
 * SlackApiClient#uploadFileBytes}, {@link SlackApiClient#completeUpload}.
 *
 * <p>Uses a real local {@code com.sun.net.httpserver.HttpServer} (JDK- provided, no extra test
 * dependency) the same way {@code PhonePeGatewayClientNetworkTest} does, since {@link
 * SlackApiClient} builds its own internal {@code HttpClient} - there is no seam to inject a mock
 * HTTP layer from outside. The constructor's {@code apiBase} parameter (added specifically to make
 * this possible) points at this local server instead of the real {@code https://slack.com/api}.
 */
class SlackApiClientMediaUploadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BOT_TOKEN = "xoxb-dummy-token";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SlackApiClient clientFor(HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        // Raw transport: the fixture is loopback, which the SSRF guard rightly refuses.
        return new SlackApiClient(
                MAPPER,
                baseUrl,
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .build());
    }

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    // --- files.getUploadURLExternal -----------------------------------------------------------

    @Test
    void getUploadUrlSendsFilenameAndLengthAndParsesTheSignedUrlAndFileId() throws Exception {
        String[] capturedAuth = new String[1];
        JsonNode[] capturedBody = new JsonNode[1];
        HttpHandler handler =
                exchange -> {
                    try {
                        capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
                        capturedBody[0] = MAPPER.readTree(readBody(exchange));
                        respond(
                                exchange,
                                200,
                                "{\"ok\":true,\"upload_url\":\"http://127.0.0.1:9/upload-raw\",\"file_id\":\"F123\"}");
                    } finally {
                        exchange.close();
                    }
                };
        server = startServer();
        server.createContext("/files.getUploadURLExternal", handler);
        SlackApiClient client = clientFor(server);

        SlackApiClient.UploadUrlInfo info = client.getUploadUrl(BOT_TOKEN, "photo.png", 42);

        assertEquals("http://127.0.0.1:9/upload-raw", info.uploadUrl());
        assertEquals("F123", info.fileId());
        assertEquals("Bearer " + BOT_TOKEN, capturedAuth[0]);
        assertEquals("photo.png", capturedBody[0].path("filename").asText());
        assertEquals(42, capturedBody[0].path("length").asInt());
    }

    @Test
    void getUploadUrlThrowsChannelDeliveryExceptionWhenSlackRejectsTheCall() throws Exception {
        server = startServer();
        server.createContext(
                "/files.getUploadURLExternal",
                exchange -> {
                    try {
                        respond(exchange, 200, "{\"ok\":false,\"error\":\"invalid_auth\"}");
                    } finally {
                        exchange.close();
                    }
                });
        SlackApiClient client = clientFor(server);

        ChannelDeliveryException ex =
                assertThrows(
                        ChannelDeliveryException.class,
                        () -> client.getUploadUrl(BOT_TOKEN, "photo.png", 42));
        assertTrue(ex.getMessage().contains("invalid_auth"));
    }

    // --- raw byte upload to the signed URL ------------------------------------------------------

    @Test
    void uploadFileBytesPostsRawOctetStreamBodyWithNoAuthorizationHeader() throws Exception {
        byte[] payload = {1, 2, 3, 4, 5};
        String[] capturedContentType = new String[1];
        String[] capturedAuth = new String[1];
        byte[][] capturedBody = new byte[1][];
        server = startServer();
        server.createContext(
                "/upload-raw",
                exchange -> {
                    try {
                        capturedContentType[0] =
                                exchange.getRequestHeaders().getFirst("Content-Type");
                        capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
                        capturedBody[0] = exchange.getRequestBody().readAllBytes();
                        respond(exchange, 200, "OK");
                    } finally {
                        exchange.close();
                    }
                });
        SlackApiClient client = clientFor(server);

        client.uploadFileBytes(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/upload-raw", payload);

        assertEquals("application/octet-stream", capturedContentType[0]);
        assertNull(capturedAuth[0], "the signed upload_url needs no Authorization header");
        assertTrue(java.util.Arrays.equals(payload, capturedBody[0]));
    }

    @Test
    void uploadFileBytesThrowsChannelDeliveryExceptionOnANonSuccessStatus() throws Exception {
        server = startServer();
        server.createContext(
                "/upload-raw",
                exchange -> {
                    try {
                        respond(exchange, 400, "bad request");
                    } finally {
                        exchange.close();
                    }
                });
        SlackApiClient client = clientFor(server);

        assertThrows(
                ChannelDeliveryException.class,
                () ->
                        client.uploadFileBytes(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/upload-raw",
                                new byte[] {9}));
    }

    // --- files.completeUploadExternal -----------------------------------------------------------

    @Test
    void completeUploadSendsFileIdTitleChannelAndInitialCommentWhenCaptionIsGiven()
            throws Exception {
        JsonNode[] capturedBody = new JsonNode[1];
        server = startServer();
        server.createContext(
                "/files.completeUploadExternal",
                exchange -> {
                    try {
                        capturedBody[0] = MAPPER.readTree(readBody(exchange));
                        respond(
                                exchange,
                                200,
                                "{\"ok\":true,\"files\":[{\"id\":\"F123\",\"title\":\"photo.png\"}]}");
                    } finally {
                        exchange.close();
                    }
                });
        SlackApiClient client = clientFor(server);

        client.completeUpload(BOT_TOKEN, "F123", "photo.png", "C0123", "here you go");

        JsonNode file = capturedBody[0].path("files").get(0);
        assertEquals("F123", file.path("id").asText());
        assertEquals("photo.png", file.path("title").asText());
        assertEquals("C0123", capturedBody[0].path("channel_id").asText());
        assertEquals("here you go", capturedBody[0].path("initial_comment").asText());
    }

    @Test
    void completeUploadOmitsInitialCommentWhenNoCaptionIsGiven() throws Exception {
        JsonNode[] capturedBody = new JsonNode[1];
        server = startServer();
        server.createContext(
                "/files.completeUploadExternal",
                exchange -> {
                    try {
                        capturedBody[0] = MAPPER.readTree(readBody(exchange));
                        respond(exchange, 200, "{\"ok\":true,\"files\":[{\"id\":\"F123\"}]}");
                    } finally {
                        exchange.close();
                    }
                });
        SlackApiClient client = clientFor(server);

        client.completeUpload(BOT_TOKEN, "F123", "photo.png", "C0123", null);

        assertFalse(capturedBody[0].has("initial_comment"));
    }

    @Test
    void completeUploadThrowsChannelDeliveryExceptionWhenSlackRejectsTheCall() throws Exception {
        server = startServer();
        server.createContext(
                "/files.completeUploadExternal",
                exchange -> {
                    try {
                        respond(exchange, 200, "{\"ok\":false,\"error\":\"file_not_found\"}");
                    } finally {
                        exchange.close();
                    }
                });
        SlackApiClient client = clientFor(server);

        ChannelDeliveryException ex =
                assertThrows(
                        ChannelDeliveryException.class,
                        () -> client.completeUpload(BOT_TOKEN, "F123", "photo.png", "C0123", null));
        assertTrue(ex.getMessage().contains("file_not_found"));
    }
}
