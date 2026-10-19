package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/**
 * SEC-023 at USE time: the BYOK Ollama / Azure OpenAI chat path checks the
 * tenant base URL and talks to it through a transport that is pinned to the
 * validated addresses and follows no redirects.
 */
class LlmHttpClientFactorySsrfTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger secretHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://internal.example.net:" + port + "/secret");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/secret", exchange -> {
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

    private static LlmHttpClientFactory factory(SsrfGuard guard) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientHttpRequestFactorySettings> settings = mock(ObjectProvider.class);
        when(settings.getIfAvailable(any())).thenReturn(ClientHttpRequestFactorySettings.defaults());
        LlmHttpClientFactory f = new LlmHttpClientFactory(RestClient.builder(), settings,
            Duration.ofSeconds(2), Duration.ofSeconds(5), false, Duration.ZERO, 20, 40);
        f.setSsrfGuard(guard);
        return f;
    }

    private static SsrfGuard guard(Map<String, String> table, Set<String> allow) {
        return SsrfGuard.of(allow, host -> {
            String ip = table.get(host);
            return ip != null ? new InetAddress[] {InetAddress.getByName(ip)} : InetAddress.getAllByName(host);
        });
    }

    @Test
    void useTimeCheckRefusesInternalTenantBaseUrls() {
        LlmHttpClientFactory f = factory(guard(Map.of("llm.attacker.example", "169.254.169.254"), Set.of()));
        for (String url : new String[] {"http://keycloak:8080", "http://127.0.0.1:11434", "http://llm.attacker.example",
            "http://168.63.129.16", "http://[::ffff:10.0.0.1]:11434"}) {
            assertThatThrownBy(() -> f.requireAllowedTenantBaseUrl(url)).as(url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("169.254.169.254");
        }
    }

    @Test
    void operatorAllowListedSelfHostedOllamaStillWorks() {
        LlmHttpClientFactory f = factory(guard(Map.of("ollama", "127.0.0.1"), Set.of("ollama:" + port)));
        f.requireAllowedTenantBaseUrl("http://ollama:" + port);
        String body = f.guardedRestClientBuilder().build().get().uri("http://ollama:" + port + "/ok")
            .retrieve().body(String.class);
        assertThat(body).isEqualTo("{}");
    }

    @Test
    void guardedClientIsPinnedEvenIfTheUseTimeCheckWereSkipped() {
        LlmHttpClientFactory f = factory(guard(Map.of("rebound.example.com", "127.0.0.1"), Set.of()));
        RestClient client = f.guardedRestClientBuilder().build();
        assertThatThrownBy(() -> client.get().uri("http://rebound.example.com:" + port + "/ok").retrieve().body(String.class))
            .hasRootCauseInstanceOf(SsrfGuard.BlockedAddressException.class);
    }

    @Test
    void guardedClientDoesNotFollowRedirectsIntoTheNetwork() {
        LlmHttpClientFactory f = factory(guard(
            Map.of("ollama.example.com", "127.0.0.1", "internal.example.net", "127.0.0.1"),
            Set.of("ollama.example.com", "internal.example.net")));
        RestClient client = f.guardedRestClientBuilder().build();
        // Both hosts are reachable for this test; only the redirect policy stops the hop.
        var response = client.post().uri("http://ollama.example.com:" + port + "/v1/chat/completions")
            .body("{}").retrieve().toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(307);
        assertThat(secretHits.get()).isZero();
    }
}
