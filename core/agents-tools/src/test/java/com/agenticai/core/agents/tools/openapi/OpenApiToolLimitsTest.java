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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The limits of the OpenAPI tool HTTP calls ({@code app.tools.openapi.*}) are settings: these tests drive the
 * real executor against a local server and change the setting.
 */
class OpenApiToolLimitsTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/loop",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/loop");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/slow",
                exchange -> {
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    try {
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } catch (java.io.IOException ignored) {
                        // the client gave up first, which is the point
                    }
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
    private OpenApiToolExecutor executor(OpenApiToolProperties properties) {
        SsrfGuard guard =
                SsrfGuard.of(
                        Set.of("api.example.com"),
                        host ->
                                new InetAddress[] {
                                    InetAddress.getByName("api.example.com".equals(host) ? "127.0.0.1" : host)
                                });
        return new OpenApiToolExecutor(
                mock(ObjectProvider.class), mock(ObjectProvider.class), guard, properties);
    }

    @Test
    void theShippedDefaultsAreTheLimitsTheExecutorAlwaysHad() {
        OpenApiToolProperties p = new OpenApiToolProperties();
        assertThat(p.getConnectTimeoutSeconds()).isEqualTo(10);
        assertThat(p.getRequestTimeoutSeconds()).isEqualTo(30);
        assertThat(p.getMaxResponseBytes()).isEqualTo(1024 * 1024);
        assertThat(p.getMaxResultChars()).isEqualTo(16_000);
        assertThat(p.getMaxSpecRedirects()).isEqualTo(3);
    }

    @Test
    void theRedirectLimitFollowsTheProperty() {
        OpenApiToolProperties two = new OpenApiToolProperties();
        two.setMaxSpecRedirects(2);
        assertThatThrownBy(() -> executor(two).fetchSpecText("http://api.example.com:" + port + "/loop"))
                .isInstanceOf(OpenApiToolException.class)
                .hasMessageContaining("redirected more than 2 times");
        OpenApiToolProperties defaults = new OpenApiToolProperties();
        assertThatThrownBy(() -> executor(defaults).fetchSpecText("http://api.example.com:" + port + "/loop"))
                .isInstanceOf(OpenApiToolException.class)
                .hasMessageContaining("redirected more than 3 times");
    }

    @Test
    void theRequestTimeoutFollowsTheProperty() {
        OpenApiToolProperties p = new OpenApiToolProperties();
        p.setRequestTimeoutSeconds(1);
        long started = System.nanoTime();
        assertThatThrownBy(() -> executor(p).fetchSpecText("http://api.example.com:" + port + "/slow"))
                .isInstanceOf(OpenApiToolException.class)
                .hasMessageContaining("could not be reached");
        long tookMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(tookMillis).as("gave up at the 1 s setting, not the 30 s default").isLessThan(3500);
    }
}
