package com.agenticai.core.agents.tools;

import com.agenticai.core.security.net.GuardedHttpClientBuilder;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Remote MCP transports connect through the SSRF-guarded socket, which refuses loopback. Tests that
 * run a real MCP server on 127.0.0.1 hand the transport factory this builder instead, which admits
 * only that host.
 */
final class LoopbackMcpClients {

    private LoopbackMcpClients() {}

    static GuardedHttpClientBuilder builder() {
        return GuardedHttpClientBuilder.forGuard(
                SsrfGuard.of(Set.of("127.0.0.1"), Set.of(), InetAddress::getAllByName),
                Duration.ofSeconds(30));
    }

    static Supplier<HttpClient.Builder> supplier() {
        return LoopbackMcpClients::builder;
    }
}
