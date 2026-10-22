package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.security.net.GuardedHttpClientBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Remote MCP connections go out through the SSRF-guarded socket (MCP07 / launch issue 07). The
 * guard checks the registered URL, but the MCP SDK used to build its own {@code HttpClient} and
 * resolve the host a second time at connect, so a DNS answer that flipped to an internal address in
 * between reached it. Here the transport is built through the real factory and pointed at a
 * loopback server: with the default (strict) guard nothing may connect, however the URL was
 * checked; with a guard that admits the host, both transports still complete the MCP handshake.
 */
class McpGuardedSocketTest {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|-?\\d+)");
    private static final Pattern VERSION =
            Pattern.compile("\"protocolVersion\"\\s*:\\s*\"([^\"]+)\"");

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> sseOut = new LinkedBlockingQueue<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/mcp", this::streamableHttp);
        server.createContext("/sse", this::sseStream);
        server.createContext("/message", this::sseMessage);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private McpClientTransport transport(
            McpServerType type, String path, GuardedHttpClientBuilder custom) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentAttestationService> attestation = mock(ObjectProvider.class);
        McpTransportFactory factory =
                new McpTransportFactory(
                        mock(AtlassianOAuthService.class),
                        attestation,
                        () -> McpRoutingHeaders.OFF,
                        () -> null);
        if (custom != null) {
            factory.setClientBuilders(() -> custom);
        }
        return factory.buildRemoteTransport(
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "loopback",
                        type,
                        null,
                        List.of(),
                        Map.of(),
                        "http://127.0.0.1:" + server.getAddress().getPort() + path));
    }

    private static GuardedHttpClientBuilder loopbackAllowed() {
        return LoopbackMcpClients.builder();
    }

    private static McpSyncClient client(McpClientTransport transport) {
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void theDefaultGuardRefusesAStreamableHttpServerOnAnInternalAddress() {
        McpSyncClient client = client(transport(McpServerType.STREAMABLE_HTTP, "/mcp", null));

        assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);

        assertThat(hits).as("no request reached the internal address").isEmpty();
        client.close();
    }

    @Test
    void theDefaultGuardRefusesAnSseServerOnAnInternalAddress() {
        McpSyncClient client = client(transport(McpServerType.SSE, "/sse", null));

        assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);

        assertThat(hits).as("no request reached the internal address").isEmpty();
        client.close();
    }

    @Test
    void streamableHttpStillCompletesTheHandshakeThroughTheGuardedSocket() {
        McpSyncClient client =
                client(transport(McpServerType.STREAMABLE_HTTP, "/mcp", loopbackAllowed()));
        try {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("fake-mcp");
            assertThat(hits).contains("POST /mcp");
        } finally {
            client.close();
        }
    }

    @Test
    void sseStillCompletesTheHandshakeThroughTheGuardedSocket() {
        McpSyncClient client = client(transport(McpServerType.SSE, "/sse", loopbackAllowed()));
        try {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("fake-mcp");
            assertThat(hits).contains("GET /sse", "POST /message");
        } finally {
            client.close();
        }
    }

    // ---- a minimal MCP server ---------------------------------------------------------------

    private static String initializeResult(String body) {
        Matcher id = ID.matcher(body);
        Matcher version = VERSION.matcher(body);
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + (id.find() ? id.group(1) : "0")
                + ",\"result\":{\"protocolVersion\":\""
                + (version.find() ? version.group(1) : "2025-03-26")
                + "\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fake-mcp\",\"version\":\"1\"}}}";
    }

    private void streamableHttp(HttpExchange ex) throws IOException {
        hits.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (ex.getRequestMethod().equals("POST") && body.contains("\"initialize\"")) {
            byte[] out = initializeResult(body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
            return;
        }
        ex.sendResponseHeaders(ex.getRequestMethod().equals("POST") ? 202 : 405, -1);
        ex.close();
    }

    private void sseStream(HttpExchange ex) throws IOException {
        hits.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            out.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String event = sseOut.poll(100, TimeUnit.MILLISECONDS);
                out.write(
                        (event != null ? event : ": keep-alive\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException clientGone) {
            // the client closed the stream
        }
    }

    private void sseMessage(HttpExchange ex) throws IOException {
        hits.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.contains("\"initialize\"")) {
            sseOut.add("event: message\ndata: " + initializeResult(body) + "\n\n");
        }
        ex.sendResponseHeaders(202, -1);
        ex.close();
    }
}
