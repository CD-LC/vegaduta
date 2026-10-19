package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class McpRoutingHeadersTest {

    private static HttpRequest mirrored(String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://mcp.example.com/mcp"));
        McpRoutingHeaders.mirror(b, body);
        return b.POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    @Test
    void toolsCallMirrorsMethodAndToolName() {
        HttpRequest r = mirrored("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_weather\"}}");
        assertThat(r.headers().firstValue("Mcp-Method")).contains("tools/call");
        assertThat(r.headers().firstValue("Mcp-Name")).contains("get_weather");
    }

    @Test
    void resourcesReadMirrorsTheUriAndPromptsGetTheName() {
        assertThat(mirrored("{\"method\":\"resources/read\",\"params\":{\"uri\":\"file:///a/b.json\"}}")
            .headers().firstValue("Mcp-Name")).contains("file:///a/b.json");
        assertThat(mirrored("{\"method\":\"prompts/get\",\"params\":{\"name\":\"summarise\"}}")
            .headers().firstValue("Mcp-Name")).contains("summarise");
    }

    @Test
    void otherMethodsGetOnlyMcpMethod() {
        HttpRequest r = mirrored("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertThat(r.headers().firstValue("Mcp-Method")).contains("tools/list");
        assertThat(r.headers().firstValue("Mcp-Name")).isEmpty();
    }

    @Test
    void responsesAndEmptyBodiesAreLeftAlone() {
        assertThat(mirrored("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}").headers().firstValue("Mcp-Method")).isEmpty();
        assertThat(mirrored(null).headers().firstValue("Mcp-Method")).isEmpty();
        assertThat(mirrored("not json").headers().firstValue("Mcp-Method")).isEmpty();
    }

    @Test
    void unsafeValuesAreBase64EncodedWithTheSentinel() {
        assertThat(McpRoutingHeaders.encode("us-west1")).isEqualTo("us-west1");
        assertThat(McpRoutingHeaders.encode("two words")).isEqualTo("two words");
        String enc = McpRoutingHeaders.encode("Hello, 世界");
        assertThat(enc).startsWith("=?base64?").endsWith("?=");
        assertThat(new String(Base64.getDecoder().decode(enc.substring(9, enc.length() - 2)), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("Hello, 世界");
        assertThat(McpRoutingHeaders.encode(" padded ")).startsWith("=?base64?");
        assertThat(McpRoutingHeaders.encode("line1\nline2")).startsWith("=?base64?");
        // A plain value that looks like the sentinel is itself encoded, to stay unambiguous.
        assertThat(McpRoutingHeaders.encode("=?base64?literal?=")).isEqualTo("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=");
    }

    @Test
    void disabledWrapperIsTheSameObjectSoNothingChangesByDefault() {
        io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer delegate =
            (builder, method, endpoint, body, ctx) -> {};
        assertThat(new McpRoutingHeaders(false).wrap(delegate)).isSameAs(delegate);
        assertThat(McpRoutingHeaders.OFF.wrap(delegate)).isSameAs(delegate);
    }

    @Test
    void enabledWrapperKeepsTheDelegatesHeadersAndAddsRoutingOnes() {
        io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer delegate =
            (builder, method, endpoint, body, ctx) -> builder.setHeader("Authorization", "Bearer t");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://mcp.example.com/mcp"));
        new McpRoutingHeaders(true).wrap(delegate).customize(b, "POST", URI.create("https://mcp.example.com/mcp"),
            "{\"method\":\"tools/call\",\"params\":{\"name\":\"x\"}}", null);
        HttpRequest r = b.POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(r.headers().firstValue("Authorization")).contains("Bearer t");
        assertThat(r.headers().firstValue("Mcp-Method")).contains("tools/call");
    }
}
