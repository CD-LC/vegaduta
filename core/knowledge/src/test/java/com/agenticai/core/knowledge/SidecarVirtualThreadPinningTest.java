package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Reads a slow sidecar response on a virtual thread and counts {@code jdk.VirtualThreadPinned} JFR
 * events. The pinned traces of the 32-user benchmark showed the monitor held in {@code
 * BufferingClientHttpResponseWrapper.getBody} (its {@code synchronized (this)} around {@code
 * StreamUtils.copyToByteArray}), parked in the JDK client's body stream.
 *
 * <p>The control run proves the test can see the pin: Spring's buffering factory must pin. JDK 24+
 * (JEP 491) no longer pins on monitors, so there the control does not pin and the test is skipped
 * rather than passing vacuously.
 */
class SidecarVirtualThreadPinningTest {

    private HttpServer server;

    @BeforeEach
    void startSlowServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/embed",
                ex -> {
                    ex.getRequestBody().readAllBytes();
                    byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().flush();
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ex.getResponseBody().write(body);
                    ex.close();
                });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sidecarTransportDoesNotPinCarrierWhileReadingASlowResponse() throws Exception {
        long controlPins = pinnedEventsDuring(new BufferingClientHttpRequestFactory(jdkFactory()));
        assumeTrue(
                controlPins > 0,
                "this JVM does not pin on synchronized (JDK 24+, JEP 491), so the pin cannot be reproduced here");
        assertEquals(
                0,
                pinnedEventsDuring(new RequestBufferingClientHttpRequestFactory(jdkFactory())),
                "SidecarClient's transport pinned a virtual-thread carrier");
    }

    private ClientHttpRequestFactory jdkFactory() {
        return new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
    }

    private long pinnedEventsDuring(ClientHttpRequestFactory factory) throws Exception {
        RestClient client = RestClient.builder().requestFactory(factory).build();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/embed";
        Path dump = Files.createTempFile("pin", ".jfr");
        try (Recording rec = new Recording()) {
            rec.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            rec.start();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread vt =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            client.post()
                                                    .uri(url)
                                                    .body("{}")
                                                    .retrieve()
                                                    .body(String.class);
                                        } catch (Throwable t) {
                                            failure.set(t);
                                        }
                                    });
            vt.join();
            rec.stop();
            if (failure.get() != null) {
                throw new AssertionError("request failed", failure.get());
            }
            rec.dump(dump);
        }
        try {
            long n = 0;
            for (RecordedEvent e : RecordingFile.readAllEvents(dump)) {
                if (e.getEventType().getName().equals("jdk.VirtualThreadPinned")) {
                    n++;
                }
            }
            return n;
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}
