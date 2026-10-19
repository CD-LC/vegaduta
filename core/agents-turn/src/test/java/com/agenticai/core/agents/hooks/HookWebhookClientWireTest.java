package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real {@code send} over a {@code SafeHttpClients} client, against a loopback server. The unit tests in
 * {@link HookWebhookClientTest} replace the transport, so this is what shows the wire behaviour survived the move
 * off the JDK client: body, headers, the 4 KiB reply cap, redirects refused and the SSRF guard on the socket.
 */
class HookWebhookClientWireTest {

    private HttpServer server;
    private URI uri;
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> seenHeaders = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/vet");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static CloseableHttpClient allowingLoopback() {
        SsrfGuard guard = SsrfGuard.of(Set.of("127.0.0.1"), InetAddress::getAllByName);
        return SafeHttpClients.create(guard, Duration.ofSeconds(2), Duration.ofSeconds(5), false);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenHeaders.set(ex.getRequestHeaders());
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @Test
    void postsTheJsonBodyWithTheHookHeadersAndReturnsTheReply() throws Exception {
        respond("/vet", 200, "{\"decision\":\"allow\"}");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put(HookWebhookClient.EVENT_HEADER, "agent.hook");
        headers.put("User-Agent", "VegaDuta-AgentHooks/1");

        try (CloseableHttpClient client = allowingLoopback()) {
            HookWebhookClient.Response response =
                HookWebhookClient.send(client, uri, "{\"tool\":\"café\"}", headers, Duration.ofSeconds(3));

            assertEquals(200, response.status());
            assertEquals("allow", HookWebhookClient.parseReply(response).allow() ? "allow" : "deny");
        }
        assertEquals("{\"tool\":\"café\"}", seenBody.get());
        assertEquals("agent.hook", seenHeaders.get().getFirst(HookWebhookClient.EVENT_HEADER));
        assertEquals("VegaDuta-AgentHooks/1", seenHeaders.get().getFirst("User-Agent"));
        assertTrue(seenHeaders.get().getFirst("Content-Type").startsWith("application/json"));
    }

    @Test
    void anOversizeReplyIsCutShortAndRefused() throws Exception {
        respond("/vet", 200, "x".repeat(HookWebhookClient.MAX_REPLY_BYTES * 20));

        try (CloseableHttpClient client = allowingLoopback()) {
            HookWebhookClient.Response response =
                HookWebhookClient.send(client, uri, "{}", Map.of(), Duration.ofSeconds(3));

            assertEquals(HookWebhookClient.MAX_REPLY_BYTES + 1, response.body().length());
            assertThrows(HookWebhookClient.WebhookException.class, () -> HookWebhookClient.parseReply(response));
        }
    }

    @Test
    void aRedirectIsNotFollowed() throws Exception {
        server.createContext("/vet", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();

        try (CloseableHttpClient client = allowingLoopback()) {
            HookWebhookClient.Response response =
                HookWebhookClient.send(client, uri, "{}", Map.of(), Duration.ofSeconds(3));

            assertEquals(302, response.status());
            assertThrows(HookWebhookClient.WebhookException.class, () -> HookWebhookClient.parseReply(response));
        }
    }

    @Test
    void aSlowReceiverIsCutOffAtTheCallersTimeout() throws Exception {
        server.createContext("/vet", ex -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.start();

        try (CloseableHttpClient client = allowingLoopback()) {
            long start = System.nanoTime();
            assertThrows(
                HookWebhookClient.WebhookException.class,
                () -> HookWebhookClient.send(client, uri, "{}", Map.of(), Duration.ofMillis(300)));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_500);
        }
    }

    @Test
    void theGuardRefusesLoopbackThatTheOperatorHasNotListed() throws Exception {
        respond("/vet", 200, "{\"decision\":\"allow\"}");
        SsrfGuard strict = SsrfGuard.of(Set.of(), InetAddress::getAllByName);

        try (CloseableHttpClient client =
                 SafeHttpClients.create(strict, Duration.ofSeconds(2), Duration.ofSeconds(5), false)) {
            assertThrows(
                HookWebhookClient.WebhookException.class,
                () -> HookWebhookClient.send(client, uri, "{}", Map.of(), Duration.ofSeconds(3)));
        }
        assertEquals(null, seenBody.get(), "the request must never have reached the loopback server");
    }
}
