package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Outbound side of ASI07: the client stamps timestamp/nonce/hop and signs for configured peers. */
class A2aClientHmacHeadersTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<com.sun.net.httpserver.Headers> seenHeaders =
            new AtomicReference<>();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>();
    private A2aProperties.Client config;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext(
                "/.well-known/agent-card.json",
                ex -> {
                    String card =
                            "{\"name\":\"R\",\"version\":\"1\",\"protocolVersion\":\"0.3.0\","
                                    + "\"url\":\"http://127.0.0.1:"
                                    + port
                                    + "/a2a\",\"skills\":[]}";
                    reply(ex, card);
                });
        server.createContext(
                "/a2a",
                ex -> {
                    seenHeaders.set(ex.getRequestHeaders());
                    seenBody.set(ex.getRequestBody().readAllBytes());
                    reply(
                            ex,
                            "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"kind\":\"message\","
                                    + "\"role\":\"agent\",\"parts\":[{\"kind\":\"text\",\"text\":\"ok\"}]}}");
                });
        server.start();
        config = new A2aProperties.Client();
        config.setEnabled(true);
        config.setAllowedHosts(new ArrayList<>(List.of("127.0.0.1")));
        config.setAllowInsecureLoopback(true);
        config.setRequireSignedCard(false);
        config.setRequestTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body)
            throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @SuppressWarnings("unchecked")
    private A2aClientService service(A2aReplayProtectionProperties sec) {
        A2aClientService svc =
                new A2aClientService(
                        config,
                        mock(A2aCardVerifier.class),
                        mock(ObjectProvider.class),
                        new A2aEgressGuard(config),
                        HttpClient.newHttpClient());
        svc.setReplayProtection(sec);
        return svc;
    }

    private String header(String name) {
        return seenHeaders.get().getFirst(name);
    }

    private String cardUrl() {
        return "http://127.0.0.1:" + port + "/.well-known/agent-card.json";
    }

    @Test
    void originatingCallCarriesTimestampNonceAndHopZeroButNoSignature() {
        service(new A2aReplayProtectionProperties()).send(cardUrl(), "hi");
        assertNotNull(header(A2aMessageSecurityFilter.TIMESTAMP_HEADER));
        assertTrue(header(A2aMessageSecurityFilter.NONCE_HEADER).length() >= 8);
        assertEquals("0", header(A2aMessageSecurityFilter.HOP_HEADER));
        assertNull(header(A2aMessageSecurityFilter.SIGNATURE_HEADER));
    }

    @Test
    void forwardedCallIncrementsInboundHopAndNoncesDiffer() throws Exception {
        A2aClientService svc = service(new A2aReplayProtectionProperties());
        // Serving an inbound request that was hop 3 on the wire (0-based 2): the forward is 3.
        svc.send(cardUrl(), "hi", new A2aCallContext(3, "trace-1", false));
        assertEquals("3", header(A2aMessageSecurityFilter.HOP_HEADER));
        String first = header(A2aMessageSecurityFilter.NONCE_HEADER);
        svc.send(cardUrl(), "hi again");
        assertTrue(!first.equals(header(A2aMessageSecurityFilter.NONCE_HEADER)));
        // A call that starts a chain is hop 0 again: nothing leaks from the forwarded one.
        assertEquals("0", header(A2aMessageSecurityFilter.HOP_HEADER));
    }

    @Test
    void configuredPeerGetsVerifiableSignature() {
        A2aReplayProtectionProperties sec = new A2aReplayProtectionProperties();
        A2aReplayProtectionProperties.SignedPeer p = new A2aReplayProtectionProperties.SignedPeer();
        p.setHost("127.0.0.1");
        p.setSecret("peer-secret");
        sec.setSignedPeers(List.of(p));
        service(sec).send(cardUrl(), "hi");
        assertTrue(
                A2aHmacSigner.verify(
                        "peer-secret",
                        header(A2aMessageSecurityFilter.SIGNATURE_HEADER),
                        "POST",
                        "/a2a",
                        header(A2aMessageSecurityFilter.TIMESTAMP_HEADER),
                        header(A2aMessageSecurityFilter.NONCE_HEADER),
                        Integer.parseInt(header(A2aMessageSecurityFilter.HOP_HEADER)),
                        seenBody.get()));
    }
}
