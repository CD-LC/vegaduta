package com.agenticai.core.agents.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** SEC-048: the Agent Card verifier cannot be used to reach internal hosts. */
class A2aCardVerifierSsrfTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String CARD_URL =
            "https://publisher.example.com/.well-known/agent-card.json";

    private static SsrfGuard guard(Map<String, String> table, Set<String> allow) {
        return SsrfGuard.of(
                allow,
                host -> {
                    String ip = table.get(host);
                    return ip != null
                            ? new InetAddress[] {InetAddress.getByName(ip)}
                            : InetAddress.getAllByName(host);
                });
    }

    @Test
    void guardedFetcherRefusesAHostResolvingInsideWithoutConnecting() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try {
            A2aCardVerifier.Fetcher fetcher =
                    A2aCardVerifier.guardedFetcher(
                            guard(Map.of("cards.attacker.example", "127.0.0.1"), Set.of()));
            int port = server.getAddress().getPort();
            assertThatThrownBy(
                            () ->
                                    fetcher.get(
                                            "http://cards.attacker.example:" + port + "/card.json"))
                    .isInstanceOf(IOException.class)
                    .hasMessageNotContaining("127.0.0.1");
            assertThatThrownBy(() -> fetcher.get("http://169.254.169.254/latest/meta-data/"))
                    .isInstanceOf(IOException.class);
            assertThat(hits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void guardedFetcherDoesNotFollowRedirects() throws Exception {
        AtomicInteger secretHits = new AtomicInteger();
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int[] port = new int[1];
        server.createContext(
                "/card.json",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add("Location", "http://cards.example.com:" + port[0] + "/secret");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/secret",
                exchange -> {
                    secretHits.incrementAndGet();
                    byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, b.length);
                    exchange.getResponseBody().write(b);
                    exchange.close();
                });
        server.start();
        port[0] = server.getAddress().getPort();
        try {
            // Allow-listed so that ONLY the redirect policy is under test.
            A2aCardVerifier.Fetcher fetcher =
                    A2aCardVerifier.guardedFetcher(
                            guard(
                                    Map.of("cards.example.com", "127.0.0.1"),
                                    Set.of("cards.example.com")));
            assertThatThrownBy(
                            () -> fetcher.get("http://cards.example.com:" + port[0] + "/card.json"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("redirects are not followed");
            assertThat(secretHits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://keys.example.com/jwks.json", // cross-origin and not https
                "https://169.254.169.254/jwks.json",
                "https://168.63.129.16/jwks.json",
                "https://core:8080/.well-known/jwks.json", // compose service name
                "https://metadata.google.internal/jwks",
                "https://localhost/jwks.json"
            })
    void cardSuppliedJwksUriPointingInsideIsNeverFetched(String jwksUri) {
        A2aCardSigningService signing = new A2aCardSigningService(new A2aProperties());
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "Remote Agent");
        card.put("url", "https://publisher.example.com/a2a");
        card.put("jwks_uri", jwksUri);
        card.put("signatures", signing.sign(card));
        List<String> fetched = new ArrayList<>();
        A2aCardVerifier v =
                new A2aCardVerifier(
                        signing,
                        url -> {
                            fetched.add(url);
                            if (url.equals(CARD_URL)) {
                                return MAPPER.writeValueAsString(card);
                            }
                            return MAPPER.writeValueAsString(
                                    Map.of("keys", List.of(signing.publicJwk())));
                        },
                        guard(Map.of(), Set.of()));
        A2aCardVerifier.VerificationResult r = v.verifyRemote(CARD_URL);
        assertThat(r.verified()).isFalse();
        assertThat(r.detail()).contains("jwks_uri");
        assertThat(fetched).containsExactly(CARD_URL);
    }

    @Test
    void sameOriginJwksUriIsAccepted() {
        A2aCardVerifier v =
                new A2aCardVerifier(
                        new A2aCardSigningService(new A2aProperties()),
                        url -> "{}",
                        guard(Map.of(), Set.of()));
        assertThat(
                        v.jwksUriRefusal(
                                java.net.URI.create(CARD_URL),
                                "https://publisher.example.com/keys.json"))
                .isNull();
        assertThat(
                        v.jwksUriRefusal(
                                java.net.URI.create(CARD_URL),
                                "https://keys.example.com/jwks.json"))
                .isNull();
    }
}
