package com.agenticai.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SEC-026 / SEC-027: the MCP guard now classifies addresses with the shared
 * SsrfGuard list, and the SSE message endpoint must stay on the registered
 * origin.
 */
class McpRemoteUrlGuardSharedRangesTest {

    private final McpRemoteUrlGuard guard = McpRemoteUrlGuard.withDenylist(McpRemoteUrlGuard.DEFAULT_DENYLIST);

    @ParameterizedTest
    @ValueSource(strings = {"168.63.129.16", "192.0.0.8", "198.18.0.1", "240.0.0.1", "224.0.0.1", "255.255.255.255"})
    void rangesTheOldListMissedAreRefusedAsLiterals(String ip) {
        assertThat(guard.check("http://" + ip + "/mcp")).as(ip).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"168.63.129.16", "192.0.0.8", "198.18.0.1", "240.0.0.1", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1"})
    void rangesTheOldListMissedAreRefusedWhenAHostnameResolvesToThem(String ip) {
        McpRemoteUrlGuard.HostResolver rebinding = host -> new InetAddress[] {InetAddress.getByName(ip)};
        assertThat(guard.checkResolved("https://mcp.attacker.example/mcp", rebinding))
            .as(ip)
            .isPresent()
            .get().asString().doesNotContain(ip);
    }

    @Test
    void ipv6LiteralsEmbeddingInternalV4AreRefused() {
        assertThat(guard.check("http://[64:ff9b::a9fe:a9fe]/mcp")).isPresent();
        assertThat(guard.check("http://[2002:a9fe:a9fe::1]/mcp")).isPresent();
    }

    @Test
    void publicAddressStillPasses() {
        McpRemoteUrlGuard.HostResolver publicDns = host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        assertThat(guard.checkResolved("https://mcp.example.com/mcp", publicDns)).isEmpty();
    }

    @Test
    void schemeRelativeEndpointSwitchingHostIsRefused() {
        URI base = URI.create("https://mcp.example.com/sse");
        URI hostile = base.resolve("//core:8080/x");
        assertThat(hostile.getHost()).isEqualTo("core"); // what the SDK's resolution produces
        assertThat(McpRemoteUrlGuard.checkSameOrigin("https://mcp.example.com/sse", hostile)).isPresent();
    }

    @Test
    void sameOriginEndpointIsAccepted() {
        URI base = URI.create("https://mcp.example.com/sse");
        assertThat(McpRemoteUrlGuard.checkSameOrigin("https://mcp.example.com/sse",
            base.resolve("/messages?sessionId=abc"))).isEmpty();
        assertThat(McpRemoteUrlGuard.checkSameOrigin("https://mcp.example.com/sse",
            URI.create("https://MCP.example.com:443/messages"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://mcp.example.com/messages",        // scheme downgrade
        "https://mcp.example.com:8443/messages",  // other port
        "https://evil.example/messages",          // other host
        "https://169.254.169.254/latest",
        "https://user@mcp.example.com/messages"   // userinfo confusion
    })
    void crossOriginEndpointsAreRefused(String endpoint) {
        assertThat(McpRemoteUrlGuard.checkSameOrigin("https://mcp.example.com/sse", URI.create(endpoint)))
            .as(endpoint).isPresent();
    }
}
