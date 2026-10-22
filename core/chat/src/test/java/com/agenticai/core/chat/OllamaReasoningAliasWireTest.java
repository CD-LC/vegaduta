package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The streamed alias filter through a real WebClient, not just on strings: on 2026-10-18 it was
 * attached to the local pool row's client and every reply failed with "The client response body
 * can only be consumed once", which no string-level test could see.
 */
class OllamaReasoningAliasWireTest {

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse", exchange -> {
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"reasoning\":\"let me think\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                            + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aStreamedReplyThroughTheFilterIsReadOnceAndAliased() {
        WebClient client = WebClient.builder()
                .clientConnector(new JdkClientHttpConnector())
                .filter(OllamaReasoningAlias.streamFilter())
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        List<String> lines = client.get()
                .uri("/sse")
                .retrieve()
                .bodyToFlux(String.class)
                .collectList()
                .block();
        String all = String.join("\n", lines);
        assertThat(all).contains("reasoning_content").doesNotContain("\"reasoning\":");
        assertThat(all).contains("\"content\":\"hi\"");
    }
}
