package com.agenticai.core.providers.localmodels;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** A JDK {@link HttpServer} that talks just enough Ollama: {@code /api/tags} and a streamed {@code /api/pull}. */
final class FakeOllama implements AutoCloseable {

    private final HttpServer server;
    volatile String tagsJson = "{\"models\":[]}";
    volatile int tagsStatus = 200;
    volatile int pullStatus = 200;
    volatile List<String> pullLines = List.of("{\"status\":\"success\"}");
    /** Held open between the first and the remaining pull lines when set, so a test can poll mid-pull. */
    volatile java.util.concurrent.CountDownLatch holdPull;
    final List<String> pullBodies = new CopyOnWriteArrayList<>();

    FakeOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = tagsJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(tagsStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/pull", exchange -> {
            pullBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            if (pullStatus != 200) {
                byte[] body = "{\"error\":\"pull model manifest: file does not exist\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(pullStatus, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                List<String> lines = pullLines;
                for (int i = 0; i < lines.size(); i++) {
                    out.write((lines.get(i) + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (i == 0 && holdPull != null) {
                        try {
                            holdPull.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A guard that lets exactly this server through, like an operator's allowed-private-hosts entry. */
    SsrfGuard allowingGuard() {
        return SsrfGuard.of(
                Set.of("127.0.0.1:" + server.getAddress().getPort()),
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
