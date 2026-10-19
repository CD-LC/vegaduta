package com.agenticai.core.agents.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SEC-027 end-to-end for fetch_web_page: redirects into the network are refused per hop, and the
 * connection itself is pinned to the validated address (a DNS answer that flips after validation
 * never reaches a socket).
 */
class WebPageFetcherPinningTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger secretHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/page",
                exchange -> {
                    byte[] body =
                            "<html><body><p>Hello public</p></body></html>"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/to-internal",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add("Location", "http://internal.example.net:" + port + "/secret");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/secret",
                exchange -> {
                    secretHits.incrementAndGet();
                    byte[] body = "internal-secret".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

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

    @Test
    void publicPageIsFetched() {
        WebPageFetcher fetcher =
                new WebPageFetcher(
                        guard(
                                Map.of("public.example.com", "127.0.0.1"),
                                Set.of("public.example.com")));
        assertThat(fetcher.fetch("http://public.example.com:" + port + "/page", null))
                .isEqualTo("Hello public");
    }

    @Test
    void redirectIntoTheNetworkIsRefusedAndNeverRequested() {
        WebPageFetcher fetcher =
                new WebPageFetcher(
                        guard(
                                Map.of(
                                        "public.example.com",
                                        "127.0.0.1",
                                        "internal.example.net",
                                        "127.0.0.1"),
                                Set.of("public.example.com")));
        assertThatThrownBy(
                        () ->
                                fetcher.fetch(
                                        "http://public.example.com:" + port + "/to-internal", null))
                .isInstanceOf(WebPageFetchException.class)
                .hasMessageContaining("own network");
        assertThat(secretHits.get()).isZero();
    }

    @Test
    void dnsAnswerThatFlipsAfterValidationNeverReachesTheSocket() {
        // First lookup (validation) says public, every later one (connect) says loopback.
        AtomicInteger lookups = new AtomicInteger();
        SsrfGuard rebinding =
                SsrfGuard.of(
                        Set.of(),
                        host ->
                                lookups.getAndIncrement() == 0
                                        ? new InetAddress[] {InetAddress.getByName("93.184.216.34")}
                                        : new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        WebPageFetcher fetcher = new WebPageFetcher(rebinding);
        assertThatThrownBy(
                        () ->
                                fetcher.fetch(
                                        "http://rebind.attacker.example:" + port + "/secret", null))
                .isInstanceOf(WebPageFetchException.class)
                .hasMessageContaining("own network");
        assertThat(secretHits.get()).isZero();
    }
}
