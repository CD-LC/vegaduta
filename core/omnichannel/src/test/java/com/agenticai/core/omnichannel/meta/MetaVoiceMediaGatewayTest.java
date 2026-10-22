package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.omnichannel.whatsappvoice.TestAudio;
import com.agenticai.core.omnichannel.whatsappvoice.VoiceMediaGateway;
import com.agenticai.core.omnichannel.whatsappvoice.VoiceNoteException;
import com.agenticai.core.omnichannel.whatsappvoice.VoiceNoteOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real Meta is not reachable from here, so this pins the request SHAPES and every guard against a
 * local stub ({@code com.sun.net.httpserver}), the same approach {@code
 * MetaMediaGraphApiClientTest} takes. What it cannot show: that Meta accepts these requests and
 * serves media from a host on the allowlist.
 */
class MetaVoiceMediaGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "tok-123";

    private HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private MetaVoiceMediaGateway gateway(String base) {
        // Raw transport: the fixture is loopback, which the SSRF guard rightly refuses.
        return new MetaVoiceMediaGateway(
                MAPPER,
                base,
                mock(MetaMediaGraphApiClient.class),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    private void respond(HttpExchange ex, int status, String contentType, byte[] body)
            throws IOException {
        requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
        authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    private void lookup(String base, String json) {
        server.createContext(
                "/v21.0/9001",
                ex -> respond(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String infoJson(String base, String path, String mime, long size) {
        return "{\"url\":\""
                + base
                + path
                + "\",\"mime_type\":\""
                + mime
                + "\",\"sha256\":\"x\",\"file_size\":"
                + size
                + "}";
    }

    @Test
    void downloadsInMemoryWithTheBearerTokenOnBothRequests() throws Exception {
        String base = start();
        byte[] audio = TestAudio.oggOpus(48_000L, 0);
        lookup(base, infoJson(base, "/dl/f1", "audio/ogg; codecs=opus", audio.length));
        server.createContext("/dl/f1", ex -> respond(ex, 200, "audio/ogg", audio));

        VoiceMediaGateway.DownloadedMedia media =
                gateway(base).download(TOKEN, "9001", 1_000_000L, Duration.ofSeconds(5));

        assertArrayEquals(audio, media.bytes());
        assertEquals("audio/ogg", media.mimeType());
        assertEquals(List.of("GET /v21.0/9001", "GET /dl/f1"), requests);
        assertEquals(List.of("Bearer " + TOKEN, "Bearer " + TOKEN), authHeaders);
    }

    @Test
    void announcedSizeOverTheCapIsRefusedBeforeAnyDownload() throws Exception {
        String base = start();
        lookup(base, infoJson(base, "/dl/f1", "audio/ogg", 5_000_000L));
        server.createContext("/dl/f1", ex -> respond(ex, 200, "audio/ogg", new byte[10]));

        VoiceNoteException e =
                assertThrows(
                        VoiceNoteException.class,
                        () -> gateway(base).download(TOKEN, "9001", 1_000L, Duration.ofSeconds(5)));

        assertEquals(VoiceNoteOutcome.TOO_LARGE, e.getOutcome());
        assertEquals(List.of("GET /v21.0/9001"), requests);
    }

    @Test
    void actualBodyOverTheCapIsRefusedEvenWhenMetaUnderstatedTheSize() throws Exception {
        String base = start();
        lookup(base, infoJson(base, "/dl/f1", "audio/ogg", 10));
        server.createContext("/dl/f1", ex -> respond(ex, 200, "audio/ogg", new byte[5_000]));

        VoiceNoteException e =
                assertThrows(
                        VoiceNoteException.class,
                        () -> gateway(base).download(TOKEN, "9001", 1_000L, Duration.ofSeconds(5)));

        assertEquals(VoiceNoteOutcome.TOO_LARGE, e.getOutcome());
    }

    @Test
    void mimeTypeOutsideTheAllowlistIsRefused() throws Exception {
        String base = start();
        lookup(base, infoJson(base, "/dl/f1", "audio/wav", 10));

        VoiceNoteException e =
                assertThrows(
                        VoiceNoteException.class,
                        () -> gateway(base).download(TOKEN, "9001", 1_000L, Duration.ofSeconds(5)));

        assertEquals(VoiceNoteOutcome.UNSUPPORTED_MEDIA, e.getOutcome());
        assertEquals(List.of("GET /v21.0/9001"), requests);
    }

    @Test
    void aDownloadUrlOnAForeignHostIsNeverCalledSoTheTokenCannotLeak() throws Exception {
        String base = start();
        lookup(
                base,
                "{\"url\":\"https://evil.example/steal\",\"mime_type\":\"audio/ogg\",\"file_size\":10}");

        VoiceNoteException e =
                assertThrows(
                        VoiceNoteException.class,
                        () -> gateway(base).download(TOKEN, "9001", 1_000L, Duration.ofSeconds(5)));

        assertEquals(VoiceNoteOutcome.DOWNLOAD_FAILED, e.getOutcome());
        assertEquals(List.of("GET /v21.0/9001"), requests);
    }

    @Test
    void lookupErrorBecomesADownloadFailure() throws Exception {
        String base = start();
        server.createContext(
                "/v21.0/9001",
                ex ->
                        respond(
                                ex,
                                404,
                                "application/json",
                                "{\"error\":{}}".getBytes(StandardCharsets.UTF_8)));

        VoiceNoteException e =
                assertThrows(
                        VoiceNoteException.class,
                        () -> gateway(base).download(TOKEN, "9001", 1_000L, Duration.ofSeconds(5)));

        assertEquals(VoiceNoteOutcome.DOWNLOAD_FAILED, e.getOutcome());
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        String base = start();
        lookup(base, infoJson(base, "/dl/f1", "audio/ogg", 10));
        server.createContext(
                "/dl/f1",
                ex -> {
                    requests.add("GET /dl/f1");
                    ex.getResponseHeaders().add("Location", "https://evil.example/elsewhere");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                });

        VoiceNoteException e =
                assertThrows(
                        VoiceNoteException.class,
                        () -> gateway(base).download(TOKEN, "9001", 1_000L, Duration.ofSeconds(5)));

        assertEquals(VoiceNoteOutcome.DOWNLOAD_FAILED, e.getOutcome());
        assertEquals(List.of("GET /v21.0/9001", "GET /dl/f1"), requests);
    }

    @Test
    void aSlowServerHitsTheOverallDeadline() throws Exception {
        String base = start();
        server.createContext(
                "/v21.0/9001",
                ex -> {
                    try {
                        Thread.sleep(1_200);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    ex.close();
                });

        long started = System.nanoTime();
        VoiceNoteException e =
                assertThrows(
                        VoiceNoteException.class,
                        () ->
                                gateway(base)
                                        .download(TOKEN, "9001", 1_000L, Duration.ofMillis(400)));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(VoiceNoteOutcome.DOWNLOAD_FAILED, e.getOutcome());
        assertTrue(elapsedMs < 2_500, "gave up in " + elapsedMs + " ms");
    }

    @Test
    void hostAllowlistAcceptsMetaDomainsOverHttpsAndTheConfiguredGraphOriginOnly() {
        MetaVoiceMediaGateway gateway =
                new MetaVoiceMediaGateway(
                        MAPPER, "https://graph.facebook.com", mock(MetaMediaGraphApiClient.class));

        assertTrue(
                gateway.isAllowedDownloadUri(
                        URI.create(
                                "https://lookaside.fbsbx.com/whatsapp_business/attachments/?mid=1")));
        assertTrue(gateway.isAllowedDownloadUri(URI.create("https://mmg.whatsapp.net/v/t62/x")));
        assertTrue(gateway.isAllowedDownloadUri(URI.create("https://graph.facebook.com/v21.0/1")));
        assertFalse(gateway.isAllowedDownloadUri(URI.create("http://lookaside.fbsbx.com/x")));
        assertFalse(gateway.isAllowedDownloadUri(URI.create("https://fbsbx.com.evil.example/x")));
        assertFalse(gateway.isAllowedDownloadUri(URI.create("https://evilfbsbx.com/x")));
        assertFalse(
                gateway.isAllowedDownloadUri(
                        URI.create("https://169.254.169.254/latest/meta-data")));
        assertFalse(gateway.isAllowedDownloadUri(URI.create("/relative/path")));
    }

    @Test
    void sendAudioDelegatesToTheExistingMediaClientWithoutACaption() {
        MetaMediaGraphApiClient mediaClient = mock(MetaMediaGraphApiClient.class);
        MetaVoiceMediaGateway gateway =
                new MetaVoiceMediaGateway(MAPPER, "https://graph.facebook.com", mediaClient);
        byte[] audio = TestAudio.mp3();

        gateway.sendAudio("555", "tok", "919999900000", audio, "audio/mpeg", "reply.mp3");

        verify(mediaClient)
                .sendWhatsAppMedia(
                        "555", "tok", "919999900000", audio, "audio/mpeg", "reply.mp3", null);
    }
}
