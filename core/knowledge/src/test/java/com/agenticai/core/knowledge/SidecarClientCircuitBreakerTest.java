package com.agenticai.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.resilience.CircuitBreaker;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.CircuitBreakerSettings;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sidecar embeddings breaker: flag-gated, sheds with the existing exception when open. */
class SidecarClientCircuitBreakerTest {

    private static final String EMBED_OK =
            """
            {"model":"minilm-l6-v2","dimensions":2,"chunk_size":500,"chunk_overlap":0,
             "chunks":[{"index":0,"text":"hello","embedding":[0.1,0.2]}]}
            """;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 503;
    private SidecarClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/embed",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            (status == 200 ? EMBED_OK : "{\"detail\":\"x\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        client = new SidecarClient("http://127.0.0.1:" + server.getAddress().getPort(), "");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CircuitBreakerRegistry registry(boolean enabled) {
        CircuitBreakerSettings settings =
                new CircuitBreakerSettings(
                        2,
                        0,
                        10,
                        10,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        1,
                        Duration.ofSeconds(30));
        return new CircuitBreakerRegistry(
                enabled, f -> settings, f -> 0, Duration.ZERO, Clock.systemUTC(), null);
    }

    @Test
    void flagOffNeverShedsEvenAfterManyFailures() {
        client.setCircuitBreakerRegistry(registry(false));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.embedQuery("hello"))
                    .isInstanceOf(SidecarCommunicationException.class)
                    .hasMessageContaining("HTTP 503");
        }
        assertThat(hits).hasValue(5); // every call reached the sidecar, exactly as before
    }

    @Test
    void noRegistryAtAllIsTheSameAsFlagOff() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.embedQuery("hello"))
                    .isInstanceOf(SidecarCommunicationException.class);
        }
        assertThat(hits).hasValue(3);
    }

    @Test
    void anOpenCircuitShedsWithTheExistingExceptionWithoutCallingTheSidecar() {
        CircuitBreakerRegistry registry = registry(true);
        client.setCircuitBreakerRegistry(registry);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.embedQuery("hello"))
                    .isInstanceOf(SidecarCommunicationException.class)
                    .hasMessageContaining("HTTP 503");
        }
        assertThat(registry.breaker("sidecar").state()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = hits.get();
        assertThatThrownBy(() -> client.embedQuery("hello"))
                .isInstanceOf(SidecarCommunicationException.class)
                .hasMessageContaining("temporarily unavailable");
        assertThat(hits).hasValue(before);
    }

    @Test
    void clientErrorsMeanTheSidecarAnsweredAndDoNotTripTheBreaker() {
        status = 422;
        CircuitBreakerRegistry registry = registry(true);
        client.setCircuitBreakerRegistry(registry);
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.embedQuery("hello"))
                    .isInstanceOf(SidecarCommunicationException.class);
        }
        assertThat(registry.breaker("sidecar").state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(hits).hasValue(6);
    }

    @Test
    void successKeepsTheBreakerClosed() {
        status = 200;
        CircuitBreakerRegistry registry = registry(true);
        client.setCircuitBreakerRegistry(registry);
        assertThat(client.embedQuery("hello")).hasSize(2);
        assertThat(registry.breaker("sidecar").state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
