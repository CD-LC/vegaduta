package com.agenticai.core.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/**
 * SEC-008: the web-crawl connector must not be bounced into the internal
 * network by a redirect, and must refuse an internal URL outright.
 *
 * <p>The "public" host is a loopback test server that the guard is told to
 * allow (operator allow-list, like SsrfGuardTest does); the redirect target
 * resolves to the same loopback server but is NOT allowed - so the only thing
 * standing between the redirect and the secret is the per-hop re-validation.
 */
class WebCrawlConnectorSsrfTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger secretHits = new AtomicInteger();

    private static SsrfGuard.HostResolver fakeDns(Map<String, String> table) {
        return host -> {
            String ip = table.get(host);
            if (ip == null) {
                return InetAddress.getAllByName(host);
            }
            return new InetAddress[] {InetAddress.getByName(ip)};
        };
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/to-internal", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://internal.example.net:" + port + "/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/to-metadata", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/to-compose", exchange -> {
            exchange.getResponseHeaders().add("Location", "//core:8080/actuator/env");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/hop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/page", exchange -> {
            byte[] body = "<html>public page</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/secret", exchange -> {
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
    void stopServer() {
        server.stop(0);
    }

    private WebCrawlConnectorFetcher httpFetcher() {
        SsrfGuard guard = SsrfGuard.of(Set.of("public.example.com"), fakeDns(Map.of(
            "public.example.com", "127.0.0.1", "internal.example.net", "127.0.0.1")));
        return new WebCrawlConnectorFetcher(RestClient.builder(), guard, false);
    }

    private String url(String path) {
        return "http://public.example.com:" + port + path;
    }

    @Test
    void publicUrlRedirectingToAnInternalHostIsRefusedAndNeverFetched() {
        WebCrawlConnectorFetcher fetcher = httpFetcher();
        assertThatThrownBy(() -> fetcher.fetch(Map.of("urls", url("/to-internal")), null))
            .isInstanceOf(ConnectorFetchException.class)
            .hasMessageContaining("not allowed")
            .hasMessageNotContaining("127.0.0.1");
        assertThat(secretHits.get()).isZero();
    }

    @Test
    void redirectToCloudMetadataIsRefused() {
        assertThatThrownBy(() -> httpFetcher().fetch(Map.of("urls", url("/to-metadata")), null))
            .isInstanceOf(ConnectorFetchException.class);
    }

    @Test
    void schemeRelativeRedirectToAComposeServiceIsRefused() {
        assertThatThrownBy(() -> httpFetcher().fetch(Map.of("urls", url("/to-compose")), null))
            .isInstanceOf(ConnectorFetchException.class);
    }

    @Test
    void sameHostRedirectIsStillFollowed() {
        List<ConnectorDocument> docs = httpFetcher().fetch(Map.of("urls", url("/hop")), null);
        assertThat(docs).hasSize(1);
        assertThat(new String(docs.get(0).content(), StandardCharsets.UTF_8)).isEqualTo("<html>public page</html>");
    }

    @Test
    void redirectLoopsAreCapped() {
        assertThatThrownBy(() -> httpFetcher().fetch(Map.of("urls", url("/loop")), null))
            .isInstanceOf(ConnectorFetchException.class)
            .hasMessageContaining("redirected more than");
    }

    @Test
    void theClientItselfIsPinnedToValidatedAddresses() {
        // Even if a caller skipped requirePublicHttps, the transport refuses:
        // internal.example.net resolves to loopback and is not allow-listed.
        SsrfGuard guard = SsrfGuard.of(Set.of(), fakeDns(Map.of("internal.example.net", "127.0.0.1")));
        RestClient client = RestClient.builder().requestFactory(ConnectorHttp.guardedRequestFactory(guard)).build();
        assertThatThrownBy(() -> client.get().uri("http://internal.example.net:" + port + "/secret")
                .retrieve().body(String.class))
            .hasRootCauseInstanceOf(SsrfGuard.BlockedAddressException.class);
        assertThat(secretHits.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://127.0.0.1/x", "https://169.254.169.254/latest", "https://core:8080/actuator",
        "https://keycloak:8080/", "https://[::1]/", "https://168.63.129.16/machine", "https://100.64.0.1/",
        "http://example.com/", "file:///etc/passwd"
    })
    void initialInternalOrNonHttpsUrlIsRefused(String raw) {
        SsrfGuard guard = SsrfGuard.of(Set.of(), fakeDns(Map.of("example.com", "93.184.216.34")));
        WebCrawlConnectorFetcher fetcher = new WebCrawlConnectorFetcher(RestClient.builder(), guard);
        assertThatThrownBy(() -> fetcher.fetch(Map.of("urls", raw), null))
            .isInstanceOf(ConnectorFetchException.class);
    }
}
