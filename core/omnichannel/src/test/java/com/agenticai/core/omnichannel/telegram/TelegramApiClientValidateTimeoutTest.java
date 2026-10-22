package com.agenticai.core.omnichannel.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SE-69: Bot Token validation gives up quickly and says why, instead of a 7 s spinner. */
class TelegramApiClientValidateTimeoutTest {

    private HttpServer server;
    private volatile long delayMs;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        Thread.sleep(delayMs);
                        byte[] body =
                                "{\"ok\":true,\"result\":{\"id\":42,\"username\":\"b\"}}"
                                        .getBytes();
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } catch (Exception ignored) {
                        // client gave up
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TelegramApiClient client() {
        TelegramApiClient c =
                new TelegramApiClient(
                        com.agenticai.core.json.JsonMappers.plain(),
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/bot",
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        c.validateTimeout = Duration.ofMillis(300);
        return c;
    }

    @Test
    void slowTelegramFailsFastWithAClearMessage() {
        delayMs = 2000;
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> client().fetchBotInfo("123:abc"))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("did not answer");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void healthyTelegramStillValidates() {
        delayMs = 0;
        assertThat(client().fetchBotInfo("123:abc").username()).isEqualTo("b");
    }
}
