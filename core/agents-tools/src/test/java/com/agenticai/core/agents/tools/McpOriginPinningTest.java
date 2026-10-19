package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.transport.InvalidSseMessageEndpointException;
import io.modelcontextprotocol.client.transport.SseMessageEndpointValidator;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** SEC-026: the remote MCP transports cannot be steered off the registered origin. */
class McpOriginPinningTest {

    private static final String REGISTERED = "https://mcp.example.com/sse";

    @Test
    void schemeRelativeEndpointEventIsRejected() {
        SseMessageEndpointValidator v = McpOriginPinning.sseEndpointValidator(REGISTERED);
        assertThatThrownBy(() -> v.validate(URI.create(REGISTERED), "//core:8080/x"))
                .isInstanceOf(InvalidSseMessageEndpointException.class);
        assertThatThrownBy(
                        () ->
                                v.validate(
                                        URI.create(REGISTERED),
                                        "http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(InvalidSseMessageEndpointException.class);
        assertThatThrownBy(
                        () ->
                                v.validate(
                                        URI.create(REGISTERED),
                                        "https://mcp.example.com:9000/messages"))
                .isInstanceOf(InvalidSseMessageEndpointException.class);
    }

    @Test
    void sameOriginEndpointEventIsAccepted() {
        SseMessageEndpointValidator v = McpOriginPinning.sseEndpointValidator(REGISTERED);
        assertThatCode(() -> v.validate(URI.create(REGISTERED), "/messages?sessionId=42"))
                .doesNotThrowAnyException();
        assertThatCode(() -> v.validate(URI.create(REGISTERED), "https://mcp.example.com/messages"))
                .doesNotThrowAnyException();
    }

    @Test
    void requestToAnotherOriginIsRefusedBeforeHeadersAreAttached() {
        AtomicInteger delegateCalls = new AtomicInteger();
        McpSyncHttpClientRequestCustomizer headers =
                (b, m, e, body, ctx) -> delegateCalls.incrementAndGet();
        McpSyncHttpClientRequestCustomizer pinned =
                McpOriginPinning.requestCustomizer(REGISTERED, headers);

        assertThatThrownBy(
                        () ->
                                pinned.customize(
                                        HttpRequest.newBuilder(),
                                        "POST",
                                        URI.create("http://core:8080/x"),
                                        "{}",
                                        McpTransportContext.EMPTY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delegateCalls.get()).isZero();

        pinned.customize(
                HttpRequest.newBuilder(),
                "POST",
                URI.create("https://mcp.example.com/messages?sessionId=1"),
                "{}",
                McpTransportContext.EMPTY);
        assertThat(delegateCalls.get()).isEqualTo(1);
    }
}
