package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Transport-level companion to {@link SidecarClientTest}, which stubs at the {@code RestClient}
 * layer and therefore cannot see anything about how the request actually goes out on the wire. This
 * suite exists for exactly the things that layer hides, both of which are load-bearing and both of
 * which a plausible-looking edit to {@link SidecarClient}'s constructor would silently destroy.
 *
 * <p><b>1. A real {@code Content-Length}, never chunked transfer-encoding.</b> {@link
 * SidecarClient} used to get this from {@code
 * SimpleClientHttpRequestFactory.setOutputStreaming(false)}; since the virtual-thread pinning fix
 * moved it onto the JDK {@code HttpClient} it comes from wrapping the factory in a {@link
 * BufferingClientHttpRequestFactory} instead (see that constructor's javadoc for the full
 * mechanism). The bug it defends against is specific and was really observed: the FIRST request to
 * the sidecar succeeded and every request after it on the same keep-alive connection arrived at
 * FastAPI/uvicorn with an EMPTY body. So the tests below assert on the headers a real server sees,
 * and deliberately send a second request down the same connection.
 *
 * <p>These assertions are not hypothetical - the pre-change code FAILS them. {@code
 * SimpleClientHttpRequestFactory.setOutputStreaming(boolean)} is a no-op in Spring 6.2.x, so the
 * documented fix had silently stopped working and every sidecar POST was already going out chunked.
 * That is exactly the kind of rot a compile-time-only change cannot catch, which is why this suite
 * asserts on observed wire behaviour rather than on configuration.
 *
 * <p><b>2. The three per-operation read timeouts.</b> They differ by three orders of magnitude on
 * purpose (see {@link SidecarClient}'s class javadoc) and the setter names/types changed with the
 * transport swap - the old factory took millis as an {@code int}, the JDK one takes a {@link
 * Duration} - which is precisely the kind of migration where a value quietly ends up on the wrong
 * client.
 *
 * <p>No network access: the server is {@code com.sun.net.httpserver.HttpServer} bound to the
 * loopback address on an ephemeral port, the same no-external-dependency approach {@link
 * SidecarClientTest}'s connection-refused test already uses.
 */
class SidecarClientTransportTest {

    private static final String EMBED_RESPONSE =
            """
        {"model":"minilm-l6-v2","dimensions":768,"chunk_size":500,"chunk_overlap":0,
         "chunks":[{"index":0,"text":"hello","embedding":[0.1,0.2]}]}
        """;

    private HttpServer server;
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    private SidecarClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/embed", exchange -> respond(exchange, EMBED_RESPONSE));
        server.createContext("/extract", exchange -> respond(exchange, "{\"text\":\"extracted\"}"));
        server.start();
        client =
                new SidecarClient(
                        "http://127.0.0.1:" + server.getAddress().getPort(), "wire-secret");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] requestBody;
        try (InputStream in = exchange.getRequestBody()) {
            requestBody = in.readAllBytes();
        }
        received.add(
                new RecordedRequest(
                        exchange.getRequestHeaders().getFirst("Content-length"),
                        exchange.getRequestHeaders().getFirst("Transfer-encoding"),
                        exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                        requestBody));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // --- the outputStreaming(false) guarantee ---------------------------------

    @Test
    void aPostCarriesARealContentLengthAndNotChunkedTransferEncoding() {
        client.embed("hello", 500, 0);

        assertEquals(1, received.size());
        RecordedRequest request = received.get(0);
        assertNull(
                request.transferEncoding(),
                "the sidecar POST went out chunked - the documented empty-body interop bug against uvicorn is back");
        assertNotNull(request.contentLength(), "no Content-Length header was sent");
        assertEquals(
                request.body().length,
                Integer.parseInt(request.contentLength()),
                "Content-Length did not match the bytes actually sent");
        assertTrue(request.body().length > 0, "the sidecar received an empty request body");
        assertTrue(new String(request.body(), StandardCharsets.UTF_8).contains("hello"));
        // Internal auth (2026-08-07): the sidecar rejects tokenless requests
        // with 401 once SIDECAR_SHARED_SECRET is set, so the header must
        // survive the buffering wrapper onto the real wire request too.
        assertEquals(
                "wire-secret",
                request.internalToken(),
                "X-Internal-Token did not reach the wire - an authenticated sidecar would 401 every call");
    }

    @Test
    void everyRequestAfterTheFirstOnTheSameConnectionStillCarriesItsBody() {
        // The exact shape of the original bug: request #1 fine, request #2 onward
        // arriving at FastAPI with "input": null.
        client.embed("first", 500, 0);
        client.embed("second", 500, 0);
        client.embed("third", 500, 0);

        assertEquals(3, received.size());
        for (RecordedRequest request : received) {
            assertNull(request.transferEncoding(), "a follow-up request went out chunked");
            assertNotNull(request.contentLength());
            assertTrue(request.body().length > 0, "a follow-up request arrived with an empty body");
        }
        assertTrue(new String(received.get(2).body(), StandardCharsets.UTF_8).contains("third"));
    }

    @Test
    void aLargeBase64BodyIsAlsoSentWithAnExactContentLength() {
        // /extract base64-encodes the file, so this is the path where the body
        // is big enough that a buffering mistake would show up as a truncated
        // or chunked upload rather than an empty one.
        byte[] content = new byte[256 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }

        assertEquals("extracted", client.extractText("scan.pdf", content));

        RecordedRequest request = received.get(0);
        assertNull(request.transferEncoding(), "the large base64 body went out chunked");
        assertEquals(request.body().length, Integer.parseInt(request.contentLength()));
        assertTrue(
                request.body().length > content.length, "the base64 payload never arrived in full");
    }

    // --- transport + timeout wiring -------------------------------------------

    @Test
    void everyProfileRunsOnTheNonPinningJdkTransportBehindTheBufferingWrapper() throws Exception {
        for (String field :
                List.of("queryRestClient", "ingestRestClient", "transcriptionRestClient")) {
            var factory = requestFactoryOf(field);
            assertFalse(
                    factory instanceof BufferingClientHttpRequestFactory,
                    field
                            + " wraps responses in BufferingClientHttpResponseWrapper, which pinned virtual-thread carriers under load");
            var buffering =
                    assertInstanceOf(
                            RequestBufferingClientHttpRequestFactory.class,
                            factory,
                            field
                                    + " lost its buffering wrapper - request bodies would go out chunked");
            assertInstanceOf(
                    JdkClientHttpRequestFactory.class,
                    buffering.getDelegate(),
                    field
                            + " is not on java.net.http.HttpClient - HttpURLConnection pins carrier threads on JDK 21");
        }
    }

    @Test
    void theResponseIsNotWrappedInSpringsBufferingResponseWrapper() throws Exception {
        var factory =
                (org.springframework.http.client.ClientHttpRequestFactory)
                        requestFactoryOf("queryRestClient");
        var request =
                factory.createRequest(
                        java.net.URI.create(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/embed"),
                        org.springframework.http.HttpMethod.POST);
        request.getBody().write("{}".getBytes(StandardCharsets.UTF_8));
        try (var response = request.execute()) {
            assertFalse(
                    response.getClass().getSimpleName().contains("Buffering"),
                    "response was wrapped in "
                            + response.getClass()
                            + " - BufferingClientHttpResponseWrapper.getBody pinned carriers");
            assertTrue(
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)
                            .contains("minilm"));
        }
        assertEquals("2", received.get(0).contentLength());
    }

    @Test
    void eachProfileKeepsItsOwnDeliberatelyTunedReadTimeout() throws Exception {
        assertEquals(Duration.ofSeconds(30), readTimeoutOf("queryRestClient"));
        assertEquals(Duration.ofMinutes(5), readTimeoutOf("ingestRestClient"));
        assertEquals(Duration.ofMinutes(30), readTimeoutOf("transcriptionRestClient"));
    }

    @Test
    void allThreeProfilesShareOneHttpClientCarryingTheSingleShortConnectTimeout() throws Exception {
        List<HttpClient> clients = new ArrayList<>();
        for (String field :
                List.of("queryRestClient", "ingestRestClient", "transcriptionRestClient")) {
            HttpClient httpClient = httpClientOf(field);
            assertEquals(
                    Optional.of(Duration.ofSeconds(3)),
                    httpClient.connectTimeout(),
                    field
                            + " lost the 3s connect timeout - the JDK client has no per-request one, it must live here");
            assertEquals(
                    HttpClient.Version.HTTP_1_1,
                    httpClient.version(),
                    "HTTP/1.1 is pinned on purpose; h2c upgrade negotiation against uvicorn is not something to find out about in production");
            clients.add(httpClient);
        }
        assertSame(
                clients.get(0),
                clients.get(1),
                "the three profiles should share one connection pool - they differ only in read timeout");
        assertSame(
                clients.get(1),
                clients.get(2),
                "the three profiles should share one connection pool - they differ only in read timeout");
    }

    // --- reflection helpers ----------------------------------------------------

    private Object requestFactoryOf(String restClientField) throws Exception {
        Field field = SidecarClient.class.getDeclaredField(restClientField);
        field.setAccessible(true);
        RestClient restClient = (RestClient) field.get(client);
        Field factoryField = restClient.getClass().getDeclaredField("clientRequestFactory");
        factoryField.setAccessible(true);
        return factoryField.get(restClient);
    }

    private JdkClientHttpRequestFactory jdkFactoryOf(String restClientField) throws Exception {
        AbstractClientHttpRequestFactoryWrapper wrapper =
                (AbstractClientHttpRequestFactoryWrapper) requestFactoryOf(restClientField);
        return (JdkClientHttpRequestFactory) wrapper.getDelegate();
    }

    private Duration readTimeoutOf(String restClientField) throws Exception {
        Field field = JdkClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        field.setAccessible(true);
        return (Duration) field.get(jdkFactoryOf(restClientField));
    }

    private HttpClient httpClientOf(String restClientField) throws Exception {
        Field field = JdkClientHttpRequestFactory.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        return (HttpClient) field.get(jdkFactoryOf(restClientField));
    }

    private record RecordedRequest(
            String contentLength, String transferEncoding, String internalToken, byte[] body) {}
}
