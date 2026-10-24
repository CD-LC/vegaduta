package com.agenticai.core.security.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GuardedHttpClientBuilder}: the guarded socket behind a plain {@code HttpClient.Builder}
 * (the shape the MCP SDK accepts), including the streaming body subscriber the SDK's SSE transport
 * uses.
 */
class GuardedHttpClientBuilderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static SsrfGuard loopbackAllowed() {
        return SsrfGuard.of(Set.of("127.0.0.1", "localhost"), Set.of(), InetAddress::getAllByName);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void buildsAGuardedClientThatNeverFollowsRedirectsByDefault() {
        HttpClient client = new GuardedHttpClientBuilder(Duration.ofSeconds(5)).build();

        assertThat(client).isInstanceOf(GuardedJdkHttpClient.class);
        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void honoursTheConnectTimeoutAndRedirectPolicyTheSdkSets() {
        HttpClient.Builder builder =
                new GuardedHttpClientBuilder(Duration.ofSeconds(5))
                        .connectTimeout(Duration.ofSeconds(7))
                        .followRedirects(HttpClient.Redirect.NEVER);

        HttpClient client = builder.build();

        assertThat(client.connectTimeout()).hasValue(Duration.ofSeconds(7));
        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void reusesOnePooledClientPerConfigurationInsteadOfLeakingOnePerConnect() {
        HttpClient first = new GuardedHttpClientBuilder(Duration.ofSeconds(5)).build();
        HttpClient second = new GuardedHttpClientBuilder(Duration.ofSeconds(5)).build();
        HttpClient other = new GuardedHttpClientBuilder(Duration.ofSeconds(6)).build();

        assertThat(second).isSameAs(first);
        assertThat(other).isNotSameAs(first);
    }

    @Test
    void refusesSettingsThatWouldBypassOrIgnoreTheGuardedTransport() {
        HttpClient.Builder builder = new GuardedHttpClientBuilder(Duration.ofSeconds(5));

        assertThatThrownBy(() -> builder.proxy(java.net.ProxySelector.getDefault()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> builder.sslContext(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> builder.executor(Runnable::run))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> builder.authenticator(null))
                .isInstanceOf(UnsupportedOperationException.class);
        // HTTP/1.1 is all the guarded transport speaks, so asking for it is fine.
        builder.version(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void theDefaultBuilderRefusesALoopbackTargetWhateverWasCheckedBefore() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> hits = new CopyOnWriteArrayList<>();
        server.createContext(
                "/",
                ex -> {
                    hits.add(ex.getRequestURI().getPath());
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                });
        server.start();
        HttpClient client = new GuardedHttpClientBuilder(Duration.ofSeconds(5)).build();

        assertThatThrownBy(
                        () ->
                                client.send(
                                        HttpRequest.newBuilder(url("/x")).GET().build(),
                                        BodyHandlers.ofString()))
                .hasMessageContaining("refused outbound connection");
        assertThat(hits).as("no socket reached the internal address").isEmpty();
    }

    @Test
    void aStreamingLineSubscriberReceivesEventsAsTheyArriveAndCancelEndsTheConnection()
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        CountDownLatch serverSawClientLeave = new CountDownLatch(1);
        server.createContext(
                "/sse",
                ex -> {
                    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write("data: one\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        // Hold the stream open, as an SSE server does, until the client goes away.
                        while (true) {
                            Thread.sleep(50);
                            out.write(": keep-alive\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException | InterruptedException clientGone) {
                        serverSawClientLeave.countDown();
                    }
                });
        server.start();
        HttpClient client =
                GuardedHttpClientBuilder.forGuard(loopbackAllowed(), Duration.ofSeconds(30))
                        .build();

        List<String> lines = new CopyOnWriteArrayList<>();
        CountDownLatch gotFirst = new CountDownLatch(1);
        Flow.Subscription[] subscription = new Flow.Subscription[1];
        Flow.Subscriber<String> subscriber =
                new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        subscription[0] = s;
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(String line) {
                        lines.add(line);
                        if (line.equals("data: one")) {
                            gotFirst.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onComplete() {}
                };
        CompletableFuture<HttpResponse<Void>> response =
                client.sendAsync(
                        HttpRequest.newBuilder(url("/sse")).GET().build(),
                        info -> BodySubscribers.fromLineSubscriber(subscriber));

        assertThat(gotFirst.await(5, TimeUnit.SECONDS))
                .as("the first event arrives while the stream is still open")
                .isTrue();
        assertThat(response).as("the exchange is still running").isNotDone();

        subscription[0].cancel();

        assertThat(serverSawClientLeave.await(5, TimeUnit.SECONDS))
                .as("cancelling the subscription closes the connection")
                .isTrue();
    }
}
