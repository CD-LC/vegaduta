package com.agenticai.core.agents.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@code tool-sandbox.request-timeout-millis} and {@code tool-sandbox.connect-timeout-millis} were bound, documented
 * and never applied: the client ran on whatever timeout the shared builder had, so a hung sandbox held the request
 * thread for the shared default. The configured request timeout now bounds a call, and an expired one is a SERVICE
 * fault ({@link ToolSandboxUnavailableException}), never a verdict about the tenant's code.
 */
class ToolSandboxClientTimeoutTest {

    private static final String VERDICT = "{\"valid\":true,\"errors\":[]}";

    private static HttpServer sandbox(long delayMillis, AtomicBoolean answered) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/validate", exchange -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = VERDICT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                answered.set(true);
            } catch (java.io.IOException ignored) {
                // the client gave up first, which is what the test wants
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static ToolSandboxProperties properties(HttpServer server, int requestTimeoutMillis) {
        ToolSandboxProperties p = new ToolSandboxProperties();
        p.setEnabled(true);
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setRequestTimeoutMillis(requestTimeoutMillis);
        return p;
    }

    @Test
    void aSandboxSlowerThanTheRequestTimeoutIsAServiceFaultNotAVerdict() throws Exception {
        HttpServer server = sandbox(3_000, new AtomicBoolean());
        try {
            ToolSandboxClient client = new ToolSandboxClient(RestClient.builder(), properties(server, 300));

            long start = System.nanoTime();
            assertThatThrownBy(() -> client.validate(CustomCodeToolLanguage.PYTHON, "t", "print(1)"))
                    .isInstanceOf(ToolSandboxUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_500));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aSandboxWithinTheRequestTimeoutAnswersNormally() throws Exception {
        HttpServer server = sandbox(100, new AtomicBoolean());
        try {
            ToolSandboxClient client = new ToolSandboxClient(RestClient.builder(), properties(server, 5_000));

            assertThat(client.validate(CustomCodeToolLanguage.PYTHON, "t", "print(1)").valid()).isTrue();
        } finally {
            server.stop(0);
        }
    }
}
