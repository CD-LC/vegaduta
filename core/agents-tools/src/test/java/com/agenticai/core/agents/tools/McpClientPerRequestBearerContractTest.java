package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * P0 characterisation (ADR-0012, section 6 P0.5, client side): the {@code McpRemoteHeaders}
 * invariant, asserted ON THE WIRE. An OAuth-backed remote MCP connection must re-read the bearer
 * token for EVERY HTTP request, never freeze the one that existed when the connection was built
 * (the pre-2026-09-28 bug: an expired token 401'd every later call until reconnect).
 *
 * <p>{@code McpRemoteHeadersTest} pins the customizer in isolation; this drives a real MCP client
 * (transport built by {@code DynamicMcpConnectionManager#buildRemoteTransport}) against a local
 * Streamable-HTTP fake server and reads the {@code Authorization} header the server actually
 * received on each request, so it also proves the customizer is attached to the transport. It is
 * the test that must keep passing when SDK 2.0 removes {@code McpSyncHttpClientRequestCustomizer}
 * and the per-request hook moves to the transport builder's {@code httpRequestCustomizer}.
 */
class McpClientPerRequestBearerContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> authorizationPerRequest = new CopyOnWriteArrayList<>();
    private final List<String> methodPerRequest = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startFakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/mcp",
                exchange -> {
                    try {
                        String httpMethod = exchange.getRequestMethod();
                        if (!"POST".equals(httpMethod)) {
                            // No server->client SSE stream and no explicit session teardown in this
                            // fake.
                            exchange.sendResponseHeaders(405, -1);
                            return;
                        }
                        JsonNode request =
                                mapper.readTree(exchange.getRequestBody().readAllBytes());
                        String method = request.path("method").asText();
                        authorizationPerRequest.add(
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        methodPerRequest.add(method);
                        String result =
                                switch (method) {
                                    case "initialize" ->
                                            "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},"
                                                    + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}";
                                    case "tools/list" ->
                                            "{\"tools\":[{\"name\":\"echo\",\"description\":\"d\","
                                                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
                                    case "tools/call" ->
                                            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}";
                                    default -> null;
                                };
                        if (result == null || !request.has("id")) {
                            exchange.sendResponseHeaders(202, -1); // notifications
                            return;
                        }
                        byte[] body =
                                ("{\"jsonrpc\":\"2.0\",\"id\":"
                                                + request.get("id").toString()
                                                + ",\"result\":"
                                                + result
                                                + "}")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.getResponseHeaders().add("Mcp-Session-Id", "fake-session");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
    }

    @AfterEach
    void stopFakeServer() {
        server.stop(0);
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path cacheBase;

    private DynamicMcpConnectionManager manager(AtlassianOAuthService oauth) {
        return new DynamicMcpConnectionManager(
                mock(McpRegistryService.class),
                mock(TenantSchemaIterator.class),
                new McpRemoteUrlGuard(""),
                new McpStdioCommandGuard(""),
                mock(McpStdioSelfHealer.class),
                new McpCatalogService(),
                5,
                new McpStdioSidecarProperties(),
                emptyProvider(),
                oauth,
                new SimpleMeterRegistry(),
                emptyProvider(),
                cacheBase.toString());
    }

    @Test
    void everyRequestOnTheWireCarriesAFreshlyReadBearerNotTheConnectTimeOne() {
        AtomicInteger issued = new AtomicInteger();
        AtlassianOAuthService oauth = mock(AtlassianOAuthService.class);
        when(oauth.currentAccessToken()).thenAnswer(inv -> "tok-" + issued.incrementAndGet());
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "atlassian",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        null,
                        Map.of(),
                        url,
                        false,
                        McpReviewStatus.NOT_REQUIRED,
                        null,
                        "ATLASSIAN");
        TenantContext.set(TenantContext.of("tenant_a", "user-1", Set.of()));
        McpClientTransport transport = manager(oauth).buildRemoteTransport(config);
        TenantContext.clear(); // the SDK calls the customizer on a transport thread with no tenant

        try (McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            client.initialize();
            client.listTools();
            client.callTool(new McpSchema.CallToolRequest("echo", Map.of()));
            client.callTool(new McpSchema.CallToolRequest("echo", Map.of()));
        }

        assertThat(methodPerRequest)
                .containsSubsequence("initialize", "tools/list", "tools/call", "tools/call");
        assertThat(authorizationPerRequest).hasSizeGreaterThanOrEqualTo(4).doesNotContainNull();
        assertThat(authorizationPerRequest)
                .as("the token minted when the transport was built (tok-1) must never be sent")
                .doesNotContain("Bearer tok-1");
        assertThat(authorizationPerRequest)
                .as("each HTTP request re-reads the token, so no two requests share a bearer")
                .doesNotHaveDuplicates();
        assertThat(authorizationPerRequest).allMatch(h -> h.startsWith("Bearer tok-"));
        assertThat(issued.get())
                .as("one read at build + one per request")
                .isGreaterThanOrEqualTo(1 + 4);
    }

    @Test
    void staticHeadersAreSentOnEveryRequestAlongsideNoBearer() {
        AtlassianOAuthService oauth = mock(AtlassianOAuthService.class);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "static-remote",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        List.of(),
                        Map.of("Authorization", "Bearer static-key"),
                        url);
        McpClientTransport transport = manager(oauth).buildRemoteTransport(config);

        try (McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            client.initialize();
            client.listTools();
        }

        assertThat(authorizationPerRequest).isNotEmpty().allMatch("Bearer static-key"::equals);
        org.mockito.Mockito.verifyNoInteractions(oauth);
    }
}
