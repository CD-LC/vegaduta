package com.agenticai.core.providers.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.providers.AzureOpenAiCredential;
import com.agenticai.core.providers.OllamaCredential;
import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * SEC-023 on the tenant BYOK validators: DNS-resolved internal targets and
 * compose names are refused, the HTTP probe is pinned and does not follow
 * redirects, and the error text is not a port-scan oracle.
 */
class TenantBaseUrlSsrfPinningTest {

    private static SsrfGuard guard(Map<String, String> table, Set<String> allowList) {
        return SsrfGuard.of(allowList, host -> {
            String ip = table.get(host);
            if (ip != null) {
                return new InetAddress[] {InetAddress.getByName(ip)};
            }
            return InetAddress.getAllByName(host);
        });
    }

    @Test
    void ollamaRefusesAComposeNameAndAHostnameResolvingInside() {
        OllamaProviderValidator ollama = new OllamaProviderValidator(RestClient.builder(),
            guard(Map.of("llm.attacker.example", "10.0.0.5"), Set.of()));
        for (String target : new String[] {"http://keycloak:8080", "http://core:8080", "http://llm.attacker.example:11434"}) {
            String cred = new OllamaCredential(target, null).encode();
            assertThatThrownBy(() -> ollama.validate(cred))
                .as(target)
                .isInstanceOf(ProviderValidationException.class)
                .hasMessageContaining("not allowed")
                .hasMessageNotContaining("10.0.0.5");
        }
    }

    @Test
    void azureRefusesWireServerAndReservedRanges() {
        AzureOpenAIProviderValidator azure = new AzureOpenAIProviderValidator(RestClient.builder(),
            guard(Map.of(), Set.of()));
        for (String target : new String[] {"https://168.63.129.16", "https://192.0.0.8", "https://198.18.0.1", "https://240.0.0.1"}) {
            String cred = new AzureOpenAiCredential(target, "k", "gpt-4o", "2024-02-01").encode();
            assertThatThrownBy(() -> azure.validate(cred)).as(target).isInstanceOf(ProviderValidationException.class);
        }
    }

    @Test
    void probeDoesNotFollowARedirectIntoThePrivateNetwork() throws Exception {
        AtomicInteger internalHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int[] port = new int[1];
        server.createContext("/api/tags", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://internal.example.net:" + port[0] + "/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/secret", exchange -> {
            internalHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        port[0] = server.getAddress().getPort();
        try {
            // The "public" Ollama is the allow-listed test server; the redirect target is not allowed.
            OllamaProviderValidator ollama = new OllamaProviderValidator(RestClient.builder(), guard(
                Map.of("ollama.example.com", "127.0.0.1", "internal.example.net", "127.0.0.1"),
                Set.of("ollama.example.com")));
            String cred = new OllamaCredential("http://ollama.example.com:" + port[0], null).encode();
            try {
                ollama.validate(cred);
            } catch (ProviderValidationException ignored) {
                // Either outcome is fine for this test - only the hop matters.
            }
            assertThat(internalHits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableErrorCarriesNoTransportDetail() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        OllamaProviderValidator ollama = new OllamaProviderValidator(RestClient.builder(), guard(
            Map.of("ollama.example.com", "127.0.0.1"), Set.of("ollama.example.com")));
        String cred = new OllamaCredential("http://ollama.example.com:" + closedPort, null).encode();
        assertThatThrownBy(() -> ollama.validate(cred))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageNotContaining("refused")
            .hasMessageNotContaining("Connect")
            .hasMessageNotContaining("timed out");
    }
}
