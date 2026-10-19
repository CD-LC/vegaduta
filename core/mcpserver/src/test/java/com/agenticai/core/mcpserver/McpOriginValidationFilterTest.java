package com.agenticai.core.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpOriginValidationFilterTest {

    private record Outcome(int status, boolean reachedChain, String body) {}

    private static Outcome call(McpOriginValidationFilter filter, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        return new Outcome(response.getStatus(), reached.get(), response.getContentAsString());
    }

    @Test
    void requestWithoutOriginIsAllowed() throws Exception {
        Outcome o = call(new McpOriginValidationFilter(McpOriginValidationFilter.Mode.ENFORCE, ""), null);
        assertThat(o.reachedChain()).isTrue();
    }

    @Test
    void badOriginIs403WithAJsonRpcErrorWithoutAnId() throws Exception {
        Outcome o = call(new McpOriginValidationFilter(McpOriginValidationFilter.Mode.ENFORCE, "https://app.example.com"),
            "https://evil.example");
        assertThat(o.status()).isEqualTo(403);
        assertThat(o.reachedChain()).isFalse();
        assertThat(o.body()).contains("\"jsonrpc\":\"2.0\"").contains("\"error\"").doesNotContain("\"id\"");
    }

    @Test
    void listedOriginIsAllowedCaseAndTrailingSlashInsensitively() throws Exception {
        var filter = new McpOriginValidationFilter(McpOriginValidationFilter.Mode.ENFORCE,
            "https://App.Example.com/, https://other.example:8443");
        assertThat(call(filter, "https://app.example.com").reachedChain()).isTrue();
        assertThat(call(filter, "https://other.example:8443").reachedChain()).isTrue();
        // Same host, different scheme or port is a different origin.
        assertThat(call(filter, "http://app.example.com").status()).isEqualTo(403);
        assertThat(call(filter, "https://other.example").status()).isEqualTo(403);
    }

    @Test
    void theRequestsOwnHostIsNotTrustedSoDnsRebindingFails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Host", "rebound.attacker.example:8080");
        request.addHeader("Origin", "http://rebound.attacker.example:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new McpOriginValidationFilter(McpOriginValidationFilter.Mode.ENFORCE, "")
            .doFilter(request, response, (req, res) -> { throw new AssertionError("must not reach the chain"); });
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void literalNullOriginAndWildcardsAreNotAllowed() throws Exception {
        var filter = new McpOriginValidationFilter(McpOriginValidationFilter.Mode.ENFORCE, "*");
        assertThat(call(filter, "null").status()).isEqualTo(403);
        assertThat(call(filter, "https://anything.example").status()).isEqualTo(403);
    }

    @Test
    void reportModeLetsItThroughAndOffModeDoesNotLook() throws Exception {
        assertThat(call(new McpOriginValidationFilter(McpOriginValidationFilter.Mode.REPORT, ""), "https://evil.example").reachedChain()).isTrue();
        assertThat(call(new McpOriginValidationFilter(McpOriginValidationFilter.Mode.OFF, ""), "https://evil.example").reachedChain()).isTrue();
    }

    @Test
    void modeParsingDefaultsToEnforce() {
        assertThat(McpOriginValidationFilter.Mode.parse(null)).isEqualTo(McpOriginValidationFilter.Mode.ENFORCE);
        assertThat(McpOriginValidationFilter.Mode.parse("garbage")).isEqualTo(McpOriginValidationFilter.Mode.ENFORCE);
        assertThat(McpOriginValidationFilter.Mode.parse("report")).isEqualTo(McpOriginValidationFilter.Mode.REPORT);
        assertThat(McpOriginValidationFilter.Mode.parse("off")).isEqualTo(McpOriginValidationFilter.Mode.OFF);
    }
}
