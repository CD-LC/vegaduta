package com.agenticai.core.agents.tools.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The monitor endpoint against a stubbed desktop-agent: the gate (404 unless the desktop tools are
 * on), byte/header pass-through with the Bearer added server-side, 502 on sidecar failure, and the
 * per-caller single-flight 429.
 */
class DesktopMonitorControllerTest {

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicBoolean block = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile byte[] reply = {1, 2, 3};
    private volatile int replyStatus = 200;
    private volatile String replyType = "image/jpeg";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            lastRequest.set(
                    exchange.getRequestMethod()
                            + " "
                            + exchange.getRequestURI().getPath()
                            + " auth="
                            + exchange.getRequestHeaders().getFirst("Authorization"));
            entered.countDown();
            if (block.get()) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseHeaders().set("Content-Type", replyType);
            exchange.getResponseHeaders().set("X-Frame-Format", "jpeg");
            exchange.sendResponseHeaders(replyStatus, reply.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(reply);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    /** A source whose single-user check passes via the shared-host acknowledgement. */
    @SuppressWarnings("unchecked")
    private DesktopToolSource source(String agentUrl) {
        return new DesktopToolSource(
                mock(Environment.class),
                true,
                agentUrl,
                "s3cret",
                true,
                mock(ObjectProvider.class));
    }

    @SuppressWarnings("unchecked")
    private DesktopMonitorController controller(DesktopToolSource source) {
        ObjectProvider<DesktopToolSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(source);
        return new DesktopMonitorController(provider);
    }

    private String agentUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void framePassesBytesAndHeadersThroughAndAddsTheBearerServerSide() {
        DesktopMonitorController controller = controller(source(agentUrl()));

        ResponseEntity<?> res = controller.frame(() -> "alice");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) res.getBody());
        assertEquals("image/jpeg", res.getHeaders().getFirst("Content-Type"));
        assertEquals("jpeg", res.getHeaders().getFirst("X-Frame-Format"));
        assertEquals("no-store", res.getHeaders().getFirst("Cache-Control"));
        // The secret never comes from the caller - the client stamps it on the way out.
        assertEquals("GET /frame auth=Bearer s3cret", lastRequest.get());
    }

    @Test
    void aMissingOrDisabledToolSourceIs404LikeAnyUnknownPath() {
        assertEquals(
                HttpStatus.NOT_FOUND,
                controller(source("")).frame(() -> "alice").getStatusCode());
        ObjectProvider<DesktopToolSource> absent = mock(ObjectProvider.class);
        assertEquals(
                HttpStatus.NOT_FOUND,
                new DesktopMonitorController(absent).frame(null).getStatusCode());
        assertNull(lastRequest.get());
    }

    @Test
    void aSidecarRefusalIs502NotTheSidecarStatus() {
        replyStatus = 500;
        reply = "{\"error\":\"no screen\"}".getBytes();
        replyType = "application/json";
        DesktopMonitorController controller = controller(source(agentUrl()));

        ResponseEntity<?> res = controller.frame(() -> "alice");

        assertEquals(HttpStatus.BAD_GATEWAY, res.getStatusCode());
    }

    @Test
    void aSecondInFlightRequestForTheSameCallerGets429() throws Exception {
        block.set(true);
        DesktopMonitorController controller = controller(source(agentUrl()));
        Future<ResponseEntity<?>> first = executor.submit(() -> controller.frame(() -> "alice"));
        entered.await(10, TimeUnit.SECONDS);

        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                controller.frame(() -> "alice").getStatusCode());

        release.countDown();
        assertEquals(HttpStatus.OK, first.get(10, TimeUnit.SECONDS).getStatusCode());
        // The window closed with the first request: the next poll goes through.
        assertEquals(HttpStatus.OK, controller.frame(() -> "alice").getStatusCode());
    }
}
