package com.agenticai.core.security.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GuardedJdkHttpClient} against a loopback HTTP server. The guard is
 * constructed with an explicit private-host allow-list because the test server
 * IS loopback - exactly the address class the guard refuses by default.
 */
class GuardedJdkHttpClientTest {

    private HttpServer server;
    private GuardedJdkHttpClient client;

    private static SsrfGuard loopbackGuard() {
        // Loopback is a private address, so the default guard refuses it;
        // allowedPrivateHosts admits the test server's host explicitly.
        return SsrfGuard.of(Set.of("127.0.0.1", "localhost"), Set.of(), host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (java.net.UnknownHostException e) {
                throw e;
            }
        });
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/echo", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("X-Method", ex.getRequestMethod());
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        client = GuardedJdkHttpClient.create(loopbackGuard(), Duration.ofSeconds(2), Duration.ofSeconds(5), false);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void getReturnsStatusHeadersAndBody() throws Exception {
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(url("/ok")).GET().build(), BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("hello");
        assertThat(resp.uri()).isEqualTo(url("/ok"));
        assertThat(resp.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }

    @Test
    void postSendsBodyAndHeaders() throws Exception {
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(url("/echo"))
                .header("Content-Type", "text/plain")
                .POST(BodyPublishers.ofString("payload-123"))
                .build(),
            BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("payload-123");
        assertThat(resp.headers().firstValue("x-method")).hasValue("POST");
    }

    @Test
    void inputStreamHandlerStillWorks() throws Exception {
        HttpResponse<InputStream> resp = client.send(
            HttpRequest.newBuilder(url("/ok")).GET().build(), BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void sendAsyncDelivers() throws Exception {
        HttpResponse<String> resp = client
            .sendAsync(HttpRequest.newBuilder(url("/ok")).GET().build(), BodyHandlers.ofString())
            .get();
        assertThat(resp.body()).isEqualTo("hello");
    }

    @Test
    void redirectPolicyIsNever() {
        assertThat(client.followRedirects()).isEqualTo(Redirect.NEVER);
        assertThat(client.connectTimeout()).hasValue(Duration.ofSeconds(2));
    }

    @Test
    void byteArrayHandlerWorks() throws Exception {
        HttpResponse<byte[]> resp = client.send(
            HttpRequest.newBuilder(url("/ok")).GET().build(), BodyHandlers.ofByteArray());
        assertThat(resp.body()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }
}
