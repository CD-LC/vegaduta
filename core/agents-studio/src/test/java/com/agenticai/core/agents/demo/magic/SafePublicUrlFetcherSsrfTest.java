package com.agenticai.core.agents.demo.magic;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** SEC-027 for the Magic Studio public fetcher: shared ranges, per-hop checks, pinned connection. */
class SafePublicUrlFetcherSsrfTest {

    private static SsrfGuard guard(Map<String, String> dns, Set<String> allow) {
        return SsrfGuard.of(allow, host -> {
            String ip = dns.get(host);
            return ip != null ? new InetAddress[] {InetAddress.getByName(ip)} : InetAddress.getAllByName(host);
        });
    }

    @Test
    void internalTargetsAreRefused() {
        SafePublicUrlFetcher fetcher = new SafePublicUrlFetcher(guard(Map.of("rebind.attacker.example", "10.0.0.5"), Set.of()));
        for (String url : new String[] {"http://127.0.0.1/", "http://168.63.129.16/", "http://keycloak:8080/",
            "http://rebind.attacker.example/", "http://198.18.0.1/", "file:///etc/passwd"}) {
            assertThat(fetcher.fetchText(url)).as(url).isEmpty();
        }
    }

    @Test
    void redirectIntoTheNetworkAndRebindingAreRefused() throws Exception {
        AtomicInteger secretHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int[] port = new int[1];
        server.createContext("/to-internal", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://internal.example.net:" + port[0] + "/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/secret", exchange -> {
            secretHits.incrementAndGet();
            byte[] b = "internal".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();
        port[0] = server.getAddress().getPort();
        try {
            SafePublicUrlFetcher redirecting = new SafePublicUrlFetcher(guard(
                Map.of("public.example.com", "127.0.0.1", "internal.example.net", "127.0.0.1"),
                Set.of("public.example.com")));
            assertThat(redirecting.fetchText("http://public.example.com:" + port[0] + "/to-internal")).isEmpty();

            AtomicInteger lookups = new AtomicInteger();
            SafePublicUrlFetcher rebinding = new SafePublicUrlFetcher(SsrfGuard.of(Set.of(),
                host -> lookups.getAndIncrement() == 0
                    ? new InetAddress[] {InetAddress.getByName("93.184.216.34")}
                    : new InetAddress[] {InetAddress.getByName("127.0.0.1")}));
            assertThat(rebinding.fetchText("http://rebind.attacker.example:" + port[0] + "/secret")).isEmpty();

            assertThat(secretHits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }
}
