package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Flux;

/**
 * A chain of REAL A2A instances in one JVM, talking over loopback HTTP: a root caller R calls A, and
 * A's agent turn calls B through its own {@code a2a_call_agent} tool. Each instance has its own
 * signing key, Agent Card, JWKS, inbound guard, task service and outbound client; the only fake is
 * the agent turn itself (a mocked {@code AgentChatService} that optionally calls the next
 * instance). Shows the hop number, trace id and signature travelling A to B without any ThreadLocal,
 * the limit being enforced at both ends, and the negotiation with a peer that lacks the extension.
 */
class A2aTwoHopTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private final List<Node> nodes = new ArrayList<>();
    private Node root;
    private Node a;
    private Node b;

    @BeforeEach
    void start() throws Exception {
        root = node();
        a = node();
        b = node();
        a.nextTarget = b.cardUrl();
    }

    @AfterEach
    void stop() {
        nodes.forEach(n -> n.server.stop(0));
    }

    private Node node() throws Exception {
        Node n = new Node();
        nodes.add(n);
        return n;
    }

    // ------------------------------------------------------------ the chain

    @Test
    void hopTraceAndSignatureTravelAcrossTwoInstances() {
        A2aClientService.Result r = root.client.send(a.cardUrl(), "start");

        assertTrue(r.success(), r.text());
        assertTrue(r.text().contains("end of chain"), r.text());
        assertEquals(1, a.seen.size());
        assertEquals(1, b.seen.size());
        A2aCallContext atA = a.seen.get(0);
        A2aCallContext atB = b.seen.get(0);
        assertNotNull(atA);
        assertNotNull(atB);
        assertEquals(1, atA.hop(), "root -> A is the first agent-to-agent hop");
        assertEquals(2, atB.hop(), "A's tool call, made while serving hop 1, is hop 2");
        assertTrue(atA.signed());
        assertTrue(atB.signed());
        assertEquals(atA.traceId(), atB.traceId(), "one trace id along the whole chain");
        awaitReleased(a);
    }

    @Test
    void theReceivingInstanceRefusesAHopAboveItsLimit() {
        b.security.setMaxHops(1);

        A2aClientService.Result r = root.client.send(a.cardUrl(), "start");

        assertTrue(r.text().contains("-32013"), "B's HopLimitExceeded reaches A's tool: " + r.text());
        assertTrue(b.seen.isEmpty(), "B never ran a turn for the refused request");
    }

    @Test
    void theCallingInstanceRefusesToExceedItsOwnLimit() {
        a.security.setMaxHops(1); // A may serve hop 1 but may not make hop 2

        A2aClientService.Result r = root.client.send(a.cardUrl(), "start");

        assertTrue(r.text().contains("above the limit of 1"), r.text());
        assertTrue(b.seen.isEmpty(), "nothing was sent to B");
        assertEquals(1, a.seen.size());
    }

    @Test
    void aPeerWithoutTheExtensionGetsNeitherSignatureNorHopHeaders() {
        b.security.getServer().setVerifySignatures(false); // B no longer advertises the extension

        A2aClientService.Result r = root.client.send(a.cardUrl(), "start");

        assertTrue(r.success(), r.text());
        assertEquals(1, b.seen.size());
        A2aCallContext atB = b.seen.get(0);
        assertEquals(0, atB.hop(), "a legacy peer is sent no hop, so it sees none");
        assertFalse(atB.signed());
        assertFalse(b.requestHeaders.get(0).containsKey("x-vegaduta-a2a-signature"));
        assertFalse(b.requestHeaders.get(0).containsKey("x-vegaduta-a2a-hop"));
    }

    @Test
    void aPeerThatRequiresSignaturesRejectsAnUnsignedLegacyCaller() throws Exception {
        b.security.getServer().setRequireSignedMessages(true);
        String body =
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\",\"params\":{\"message\":"
                        + "{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hi\"}]}}}";

        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(b.endpoint())
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(body))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "JSON-RPC errors travel in the envelope");
        assertEquals(
                A2aError.MESSAGE_SIGNATURE_REQUIRED,
                JSON.readTree(response.body()).path("error").path("code").asInt());
        assertTrue(b.seen.isEmpty());
    }

    @Test
    void aReplayedSignedRequestIsRefusedByTheReceiver() throws Exception {
        // Capture one real signed request R sends to B, then send the very same bytes again.
        root.client.send(b.cardUrl(), "start");
        Map<String, String> sent = b.requestHeaders.get(0);
        String body = b.requestBodies.get(0);
        assertNotNull(sent.get("x-vegaduta-a2a-signature"));

        HttpRequest.Builder replay =
                HttpRequest.newBuilder(b.endpoint()).header("Content-Type", "application/json");
        sent.forEach(
                (k, v) -> {
                    if (k.startsWith("x-vegaduta-a2a-")) {
                        replay.header(k, v);
                    }
                });
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                replay.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                                HttpResponse.BodyHandlers.ofString());

        assertEquals(
                A2aError.MESSAGE_REPLAYED,
                JSON.readTree(response.body()).path("error").path("code").asInt());
    }

    /** The hop entry is released when the task completes, a moment after the poller sees it. */
    private static void awaitReleased(Node n) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (n.hops.lookup(n.lastSession).isPresent() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(n.hops.lookup(n.lastSession).isEmpty(), "released when the task ended");
    }

    // ------------------------------------------------------------ one instance

    /** One A2A instance: server side (card, JWKS, JSON-RPC bridge) and client side. */
    private final class Node {
        final HttpServer server;
        final int port;
        final A2aProperties properties = new A2aProperties();
        final A2aMessageSecurityProperties security = new A2aMessageSecurityProperties();
        final A2aCardSigningService signing;
        final A2aHopRegistry hops = new A2aHopRegistry();
        final A2aClientService client;
        final A2aJsonRpcController controller;
        final A2aAgentCardService cards;
        final List<A2aCallContext> seen = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> requestHeaders = new CopyOnWriteArrayList<>();
        final List<String> requestBodies = new CopyOnWriteArrayList<>();
        volatile String nextTarget;
        volatile String lastSession;

        Node() throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            port = server.getAddress().getPort();
            properties.setPublicBaseUrl("http://127.0.0.1:" + port);
            properties.setBlockingTimeout(Duration.ofSeconds(10));
            signing = new A2aCardSigningService(properties);
            cards = new A2aAgentCardService(properties, signing, security);

            A2aCardVerifier verifier =
                    new A2aCardVerifier(
                            signing,
                            url ->
                                    HttpClient.newHttpClient()
                                            .send(
                                                    HttpRequest.newBuilder(java.net.URI.create(url))
                                                            .GET()
                                                            .build(),
                                                    HttpResponse.BodyHandlers.ofString())
                                            .body());

            A2aProperties.Client cfg = new A2aProperties.Client();
            cfg.setEnabled(true);
            cfg.setAllowedHosts(new ArrayList<>(List.of("127.0.0.1")));
            cfg.setAllowInsecureLoopback(true);
            cfg.setPollInterval(Duration.ofMillis(20));
            cfg.setMaxWait(Duration.ofSeconds(10));
            cfg.setRequestTimeout(Duration.ofSeconds(10));
            ObjectProvider<A2aTaskStore> noStore =
                    new StaticListableBeanFactory(Map.<String, Object>of())
                            .getBeanProvider(A2aTaskStore.class);
            client =
                    new A2aClientService(
                            cfg,
                            verifier,
                            noStore,
                            new A2aEgressGuard(cfg),
                            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                            new A2aMessageSigner(signing, properties),
                            security,
                            hops);

            A2aInboundGuard guard =
                    new A2aInboundGuard(
                            security,
                            new A2aNonceCache(Duration.ofMinutes(10), 100, java.time.Clock.systemUTC(), null),
                            new A2aCardPeerKeyResolver(verifier, security));

            AgentChatService chat = mock(AgentChatService.class);
            when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                    .thenAnswer(inv -> turn(((ChatTurnRequest) inv.getArgument(1)).sessionId()));
            AgentService agents = mock(AgentService.class);
            when(agents.requireAccess(AGENT_ID, PermissionLevel.RUNNER)).thenReturn(mock(Agent.class));
            A2aTaskService tasks =
                    new A2aTaskService(
                            chat,
                            new A2aTaskStore(Duration.ofHours(1), 100),
                            mock(AuditLogService.class),
                            properties,
                            hops);
            controller = new A2aJsonRpcController(tasks, agents, JSON, guard);

            Agent agent = mock(Agent.class);
            when(agent.getId()).thenReturn(AGENT_ID);
            when(agent.getName()).thenReturn("node-" + port);
            when(agent.getSystemPrompt()).thenReturn("test agent");
            server.createContext(
                    "/.well-known/agent-card.json",
                    ex -> reply(ex, JSON.writeValueAsString(cards.agentCard(agent, base()))));
            server.createContext(
                    "/.well-known/jwks.json",
                    ex ->
                            reply(
                                    ex,
                                    JSON.writeValueAsString(
                                            Map.of("keys", List.of(signing.publicJwk())))));
            server.createContext("/api/agents/" + AGENT_ID + "/a2a", this::rpc);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + port;
        }

        String cardUrl() {
            return base() + "/.well-known/agent-card.json";
        }

        java.net.URI endpoint() {
            return java.net.URI.create(base() + "/api/agents/" + AGENT_ID + "/a2a");
        }

        /** The agent turn: records the call context it runs under, optionally calls the next node. */
        Flux<String> turn(String sessionId) {
            lastSession = sessionId;
            seen.add(hops.lookup(sessionId).orElse(null));
            if (nextTarget == null) {
                return Flux.just("end of chain");
            }
            try {
                String input =
                        JSON.writeValueAsString(
                                Map.of("agent_card_url", nextTarget, "message", "ping"));
                return Flux.just(client.newCallAgentTool(sessionId).call(input));
            } catch (Exception e) {
                return Flux.error(e);
            }
        }

        /** Bridges the HTTP request into the real controller, as the servlet container would. */
        private void rpc(HttpExchange ex) throws IOException {
            byte[] bytes = ex.getRequestBody().readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            ex.getRequestHeaders()
                    .forEach(
                            (k, v) -> {
                                if (!v.isEmpty()) {
                                    headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0));
                                }
                            });
            requestHeaders.add(headers);
            requestBodies.add(body);
            A2aInboundMeta meta =
                    new A2aInboundMeta(
                            ex.getRequestMethod(),
                            ex.getRequestURI().getRawPath(),
                            name -> headers.get(name.toLowerCase(java.util.Locale.ROOT)));
            MockHttpServletResponse response = new MockHttpServletResponse();
            controller.handle(AGENT_ID, body, headers.get("a2a-version"), () -> "alice", meta, response);
            reply(ex, response.getContentAsString(StandardCharsets.UTF_8));
        }

        private void reply(HttpExchange ex, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }
}
