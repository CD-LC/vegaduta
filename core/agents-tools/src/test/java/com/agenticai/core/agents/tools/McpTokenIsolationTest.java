package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * MCP authorization spec: "MCP clients MUST NOT send tokens to the MCP server other than ones issued
 * by the MCP server's authorization server" - i.e. a token obtained for server A must never travel to
 * server B. Each registration's transport carries its own customizer pinned to that registration's
 * origin; this pins the negative case (token passthrough attempt) at that boundary.
 */
class McpTokenIsolationTest {

    @Test
    void aCustomizerForServerARefusesToBuildARequestForServerB() {
        AtomicInteger delegateCalls = new AtomicInteger();
        var customizer = McpOriginPinning.requestCustomizer("https://server-a.example/mcp", (builder, method, endpoint, body, ctx) -> {
            delegateCalls.incrementAndGet();
            builder.setHeader("Authorization", "Bearer token-for-A");
        });
        HttpRequest.Builder toB = HttpRequest.newBuilder(URI.create("https://server-b.example/mcp"));

        assertThatThrownBy(() -> customizer.customize(toB, "POST", URI.create("https://server-b.example/mcp"), "{}", null))
            .isInstanceOf(IllegalStateException.class);

        assertThat(delegateCalls).as("the token supplier must not even run for a foreign origin").hasValue(0);
        assertThat(toB.POST(HttpRequest.BodyPublishers.noBody()).build().headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void sameOriginRequestsStillCarryTheRegistrationsOwnToken() {
        var customizer = McpOriginPinning.requestCustomizer("https://server-a.example/mcp",
            (builder, method, endpoint, body, ctx) -> builder.setHeader("Authorization", "Bearer token-for-A"));
        HttpRequest.Builder toA = HttpRequest.newBuilder(URI.create("https://server-a.example/mcp/messages"));
        customizer.customize(toA, "POST", URI.create("https://server-a.example/mcp/messages"), "{}", null);
        assertThat(toA.POST(HttpRequest.BodyPublishers.noBody()).build().headers().firstValue("Authorization")).contains("Bearer token-for-A");
    }

    @Test
    void anSseEndpointEventPointingAtAnotherHostIsRefused() {
        var validator = McpOriginPinning.sseEndpointValidator("https://server-a.example/sse");
        assertThatThrownBy(() -> validator.validate(URI.create("https://server-a.example/sse"), "https://server-b.example/messages"))
            .isInstanceOf(io.modelcontextprotocol.client.transport.InvalidSseMessageEndpointException.class);
        assertThatThrownBy(() -> validator.validate(URI.create("https://server-a.example/sse"), "//server-b.example/messages"))
            .isInstanceOf(io.modelcontextprotocol.client.transport.InvalidSseMessageEndpointException.class);
    }
}
