package com.agenticai.core.agents.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
import org.springframework.beans.factory.ObjectProvider;

/**
 * SEC-027 for the OpenAPI tool: shared classification, per-hop re-validation, pinned connections.
 */
class OpenApiSsrfPinningTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger secretHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/spec.json",
                exchange -> {
                    byte[] body = "{\"openapi\":\"3.0.0\"}".getBytes(StandardCharsets.UTF_8);
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
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private static OpenApiToolExecutor executor(SsrfGuard guard) {
        return new OpenApiToolExecutor(
                mock(ObjectProvider.class), mock(ObjectProvider.class), guard);
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
    void specFetchWorksForAnAllowedHost() {
        OpenApiToolExecutor ex =
                executor(guard(Map.of("api.example.com", "127.0.0.1"), Set.of("api.example.com")));
        assertThat(ex.fetchSpecText("http://api.example.com:" + port + "/spec.json"))
                .contains("openapi");
    }

    @Test
    void specRedirectIntoTheNetworkIsRefused() {
        OpenApiToolExecutor ex =
                executor(
                        guard(
                                Map.of(
                                        "api.example.com",
                                        "127.0.0.1",
                                        "internal.example.net",
                                        "127.0.0.1"),
                                Set.of("api.example.com")));
        assertThatThrownBy(
                        () -> ex.fetchSpecText("http://api.example.com:" + port + "/to-internal"))
                .isInstanceOf(OpenApiToolException.class)
                .hasMessageContaining("own network");
        assertThat(secretHits.get()).isZero();
    }

    @Test
    void rebindingBetweenCheckAndConnectIsRefused() {
        AtomicInteger lookups = new AtomicInteger();
        SsrfGuard rebinding =
                SsrfGuard.of(
                        Set.of(),
                        host ->
                                lookups.getAndIncrement() == 0
                                        ? new InetAddress[] {InetAddress.getByName("93.184.216.34")}
                                        : new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        assertThatThrownBy(
                        () ->
                                executor(rebinding)
                                        .fetchSpecText(
                                                "http://rebind.attacker.example:"
                                                        + port
                                                        + "/secret"))
                .isInstanceOf(OpenApiToolException.class)
                .hasMessageContaining("own network");
        assertThat(secretHits.get()).isZero();
    }

    @Test
    void guardUsesTheSharedRangeList() {
        SsrfGuard g = guard(Map.of(), Set.of());
        for (String url :
                new String[] {
                    "http://168.63.129.16/",
                    "http://192.0.0.8/",
                    "http://198.18.0.1/",
                    "http://240.0.0.1/",
                    "http://keycloak:8080/",
                    "http://[64:ff9b::a9fe:a9fe]/"
                }) {
            assertThatThrownBy(() -> OpenApiUrlGuard.validatePublicHttpUri(url, g))
                    .as(url)
                    .isInstanceOf(OpenApiToolException.class);
        }
    }
}
