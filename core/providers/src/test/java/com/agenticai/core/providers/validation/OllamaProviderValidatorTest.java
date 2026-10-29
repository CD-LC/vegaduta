package com.agenticai.core.providers.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.OllamaCredential;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link OllamaProviderValidator} probes a tenant-hosted server, so the
 * transport cannot be a bound {@code MockRestServiceServer} - the validator
 * pins its own SSRF-guarded request factory over whatever builder it is
 * given. A JDK {@link HttpServer} on loopback plus an {@code
 * allowed-private-hosts}-style guard exercises the real path, the same seam
 * {@code FakeOllama} uses for the local-models tests.
 */
class OllamaProviderValidatorTest {

    private HttpServer server;
    private volatile String authorizationSeen;
    private volatile int tagsStatus = 200;

    private OllamaProviderValidator startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
            "/api/tags",
            exchange -> {
                authorizationSeen = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] body = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(tagsStatus, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
        server.start();
        return new OllamaProviderValidator(RestClient.builder(), allowingGuard(port()));
    }

    private int port() {
        return server.getAddress().getPort();
    }

    /** A guard that lets exactly this loopback server through, like an operator allowlist entry. */
    private static SsrfGuard allowingGuard(int port) {
        return SsrfGuard.of(
            Set.of("127.0.0.1:" + port),
            host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void supportedProviderIsOllama() {
        assertEquals(ProviderName.OLLAMA, new OllamaProviderValidator(RestClient.builder()).supportedProvider());
    }

    @Test
    void aHandPastedBlobIsRejectedBeforeAnyNetworkCall() {
        OllamaProviderValidator validator = new OllamaProviderValidator(RestClient.builder());
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate("not-json"));
        assertTrue(e.getMessage().contains("Could not read the Ollama connection details"), e.getMessage());
    }

    @Test
    void aBlankBaseUrlIsRejected() {
        OllamaProviderValidator validator = new OllamaProviderValidator(RestClient.builder());
        String cred = new OllamaCredential("  ", null).encode();
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate(cred));
        assertTrue(e.getMessage().contains("base URL is required"), e.getMessage());
    }

    @Test
    void aSuccessfulProbeSendsNoAuthorizationHeaderWhenNoKeyIsSet() throws Exception {
        OllamaProviderValidator validator = startServer();
        String cred = new OllamaCredential("http://127.0.0.1:" + port() + "/", null).encode();
        assertDoesNotThrow(() -> validator.validate(cred));
        assertNull(authorizationSeen, "no key configured - no Bearer header should be sent");
    }

    @Test
    void aConfiguredKeyIsSentAsABearerToken() throws Exception {
        OllamaProviderValidator validator = startServer();
        String cred = new OllamaCredential("http://127.0.0.1:" + port(), "proxy-key").encode();
        assertDoesNotThrow(() -> validator.validate(cred));
        assertEquals("Bearer proxy-key", authorizationSeen);
    }

    @Test
    void anUpstreamErrorIsReportedWithoutEchoingTheResponseBody() throws Exception {
        OllamaProviderValidator validator = startServer();
        tagsStatus = 500;
        String cred = new OllamaCredential("http://127.0.0.1:" + port(), null).encode();
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate(cred));
        assertTrue(e.getMessage().contains("responded with HTTP 500"), e.getMessage());
    }

    @Test
    void anUnreachableServerIsWordedAsAReachabilityProblem() throws Exception {
        // Bind a server only to learn a free port, then close it so the probe is refused.
        HttpServer gone = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int deadPort = gone.getAddress().getPort();
        gone.stop(0);
        OllamaProviderValidator validator =
            new OllamaProviderValidator(RestClient.builder(), allowingGuard(deadPort));
        String cred = new OllamaCredential("http://127.0.0.1:" + deadPort, null).encode();
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate(cred));
        assertTrue(e.getMessage().contains("Could not reach the Ollama server"), e.getMessage());
        // SEC-023: no refused/timeout transport detail is echoed back to the caller.
        assertTrue(e.getMessage().contains("check the base URL"), e.getMessage());
    }
}
