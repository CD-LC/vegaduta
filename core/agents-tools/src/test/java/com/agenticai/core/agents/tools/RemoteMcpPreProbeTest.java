package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.RemoteMcpPreProbe.FailureKind;
import com.agenticai.core.agents.tools.RemoteMcpPreProbe.ProbeResult;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Connection Doctor's pre-probe goes through a {@link
 * com.agenticai.core.security.net.SafeHttpClients} client (security review): the connection is
 * pinned to the addresses the {@link SsrfGuard} validated, so there is no second, unguarded DNS
 * resolution between the caller's check and the socket. The probe must keep returning the same
 * evidence (status + Content-Type, or a classified transport failure) it did before the change.
 */
class RemoteMcpPreProbeTest {

    private static final String PUBLIC_NAME = "mcp.example.net";

    private HttpServer server;
    private ExecutorService executor;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> seenMethods = new CopyOnWriteArrayList<>();
    private final List<String> seenAuth = new CopyOnWriteArrayList<>();
    private final List<String> seenHosts = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseStream = new CountDownLatch(1);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(
                "/mcp",
                exchange -> {
                    hits.incrementAndGet();
                    seenMethods.add(exchange.getRequestMethod());
                    seenAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    seenHosts.add(exchange.getRequestHeaders().getFirst("Host"));
                    byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "Application/JSON");
                    exchange.sendResponseHeaders(401, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/sse",
                exchange -> {
                    hits.incrementAndGet();
                    seenMethods.add(exchange.getRequestMethod());
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    // Chunked, never-ending stream: the probe must come back as soon as the
                    // headers are in, not after draining a body that has no end.
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream out = exchange.getResponseBody();
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    try {
                        releaseStream.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        releaseStream.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    /** A guard whose resolver answers from {@code dns}; everything else goes to the system. */
    private static SsrfGuard guard(Map<String, String> dns, Set<String> allow) {
        return SsrfGuard.of(
                allow,
                host -> {
                    String ip = dns.get(host);
                    return ip != null
                            ? new InetAddress[] {InetAddress.getByName(ip)}
                            : InetAddress.getAllByName(host);
                });
    }

    /**
     * A name only the guard's resolver knows, operator-allowed so loopback is admissible: the
     * system resolver cannot resolve it, so a successful probe proves the connection went through
     * the guard's resolver (address pinning) and not through a second, independent lookup.
     */
    private SsrfGuard pinnedGuard() {
        return guard(Map.of(PUBLIC_NAME, "127.0.0.1"), Set.of(PUBLIC_NAME));
    }

    @Test
    void streamableHttpProbePostsThroughTheGuardedClientAndReportsStatusAndContentType() {
        ProbeResult result =
                RemoteMcpPreProbe.probe(
                        "http://" + PUBLIC_NAME + ":" + port + "/mcp",
                        Map.of("Authorization", "Bearer probe-token", "Bad Name", "x"),
                        McpServerType.STREAMABLE_HTTP,
                        pinnedGuard());

        assertThat(result.performed()).isTrue();
        assertThat(result.failureKind()).isEqualTo(FailureKind.NONE);
        assertThat(result.statusCode()).isEqualTo(401);
        assertThat(result.contentType()).isEqualTo("application/json");
        assertThat(result.detail()).isNull();
        assertThat(hits.get()).isEqualTo(1);
        assertThat(seenMethods).containsExactly("POST");
        assertThat(seenAuth).containsExactly("Bearer probe-token");
        assertThat(seenHosts.get(0)).startsWith(PUBLIC_NAME);
    }

    @Test
    void sseProbeGetsAndReturnsAsSoonAsTheHeadersArriveWithoutDrainingTheStream() {
        Instant started = Instant.now();
        ProbeResult result =
                RemoteMcpPreProbe.probe(
                        "http://" + PUBLIC_NAME + ":" + port + "/sse",
                        Map.of(),
                        McpServerType.SSE,
                        pinnedGuard());
        Duration took = Duration.between(started, Instant.now());

        assertThat(result.performed()).isTrue();
        assertThat(result.failureKind()).isEqualTo(FailureKind.NONE);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.contentType()).isEqualTo("text/event-stream");
        assertThat(seenMethods).containsExactly("GET");
        assertThat(took).isLessThan(RemoteMcpPreProbe.PROBE_TIMEOUT);
    }

    @Test
    void aHostThatResolvesToABlockedAddressIsRefusedBeforeAnySocketOpens() {
        // The caller's URL guard checked a public-looking answer; by connect time the record
        // points at loopback. The guarded client's resolver refuses, nothing reaches the server.
        SsrfGuard rebound = guard(Map.of("rebound.example.net", "127.0.0.1"), Set.of());

        ProbeResult result =
                RemoteMcpPreProbe.probe(
                        "http://rebound.example.net:" + port + "/mcp",
                        Map.of("Authorization", "Bearer secret"),
                        McpServerType.STREAMABLE_HTTP,
                        rebound);

        assertThat(result.performed()).isTrue();
        assertThat(result.failureKind()).isEqualTo(FailureKind.CONNECT_REFUSED);
        assertThat(result.statusCode()).isNull();
        assertThat(result.detail()).contains("outbound-address policy");
        assertThat(hits.get()).isZero();
    }

    @Test
    void aClosedPortIsConnectRefused() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        ProbeResult result =
                RemoteMcpPreProbe.probe(
                        "http://" + PUBLIC_NAME + ":" + closedPort + "/mcp",
                        Map.of(),
                        McpServerType.STREAMABLE_HTTP,
                        pinnedGuard());

        assertThat(result.performed()).isTrue();
        assertThat(result.failureKind()).isEqualTo(FailureKind.CONNECT_REFUSED);
        assertThat(result.statusCode()).isNull();
    }

    @Test
    void anUnresolvableHostIsUnknownHost() {
        SsrfGuard failing =
                SsrfGuard.of(
                        Set.of(),
                        host -> {
                            throw new java.net.UnknownHostException(host);
                        });

        ProbeResult result =
                RemoteMcpPreProbe.probe(
                        "https://does-not-exist.example.net/mcp",
                        Map.of(),
                        McpServerType.STREAMABLE_HTTP,
                        failing);

        assertThat(result.performed()).isTrue();
        // The guard reports an unresolvable name through its own resolver refusal, which the
        // probe classifies as unreachable rather than as a server-side failure.
        assertThat(result.failureKind())
                .isIn(FailureKind.UNKNOWN_HOST, FailureKind.CONNECT_REFUSED);
        assertThat(result.statusCode()).isNull();
    }

    @Test
    void malformedOrRelativeUrlsAreSkippedNotProbed() {
        assertThat(
                        RemoteMcpPreProbe.probe(
                                        "not a url",
                                        Map.of(),
                                        McpServerType.STREAMABLE_HTTP,
                                        pinnedGuard())
                                .performed())
                .isFalse();
        assertThat(
                        RemoteMcpPreProbe.probe(
                                        "/relative/only",
                                        Map.of(),
                                        McpServerType.SSE,
                                        pinnedGuard())
                                .performed())
                .isFalse();
        assertThat(hits.get()).isZero();
    }
}
