package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link DoclingConversionService} itself and the routing {@link SidecarClient#extractText}
 * does around it: Docling-first for the formats it handles, sidecar fallback on transport failure,
 * and a 4xx surfacing as the file's fault without a fallback call. Two loopback {@link HttpServer}s
 * stand in for the docling adapter and the sidecar - the same no-external-dependency fixture {@link
 * SidecarClientTransportTest} uses.
 */
class DoclingConversionServiceTest {

    private final List<String> sidecarHits = new CopyOnWriteArrayList<>();
    private final List<String> doclingHits = new CopyOnWriteArrayList<>();
    private final AtomicInteger doclingStatus = new AtomicInteger(200);

    private HttpServer doclingServer;
    private HttpServer sidecarServer;

    private HttpServer start(List<String> hits, AtomicInteger status) throws IOException {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/extract",
                exchange -> {
                    try (InputStream in = exchange.getRequestBody()) {
                        in.readAllBytes();
                    }
                    hits.add(exchange.getRequestURI().getPath());
                    int code = status.get();
                    byte[] bytes =
                            (code == 200 ? "{\"text\":\"converted\"}" : "{\"detail\":\"boom\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(code, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        return server;
    }

    private void startBoth() throws IOException {
        doclingServer = start(doclingHits, doclingStatus);
        sidecarServer = start(sidecarHits, new AtomicInteger(200));
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServers() {
        if (doclingServer != null) {
            doclingServer.stop(0);
        }
        if (sidecarServer != null) {
            sidecarServer.stop(0);
        }
    }

    // --- bean-level behaviour ------------------------------------------------

    @Test
    void disabledWhenBaseUrlBlank() {
        DoclingConversionService service = new DoclingConversionService("  ", "secret", true);
        assertFalse(service.enabled());
        assertFalse(service.handles("report.pdf"));
        assertTrue(service.extraExtensions().isEmpty());
        assertTrue(service.extraExtensionsSorted().isEmpty());
    }

    @Test
    void handlesOnlyDoclingExtensions() {
        DoclingConversionService service =
                new DoclingConversionService("http://127.0.0.1:1", "secret", true);
        assertTrue(service.enabled());
        for (String name :
                List.of("a.pdf", "B.PPTX", "c.xlsx", "d.csv", "e.tiff", "f.bmp", "g.docx")) {
            assertTrue(service.handles(name), name);
        }
        for (String name : List.of("a.mp3", "b.md", "c.txt", "d", "e.PDFX")) {
            assertFalse(service.handles(name), name);
        }
        assertFalse(service.handles(null));
    }

    @Test
    void extraExtensionsAreExactlyTheSidecarGaps() {
        DoclingConversionService service =
                new DoclingConversionService("http://127.0.0.1:1", "secret", true);
        assertEquals(
                List.of(".bmp", ".csv", ".pptx", ".tif", ".tiff", ".xlsx"),
                service.extraExtensionsSorted());
    }

    // --- routing through SidecarClient#extractText ----------------------------

    @Test
    void doclingHandlesSupportedFileAndSidecarIsNotTouched() throws IOException {
        startBoth();
        SidecarClient client = new SidecarClient(url(sidecarServer), "sidecar-secret");
        client.setDoclingConversionService(
                new DoclingConversionService(url(doclingServer), "docling-secret", true));

        assertEquals("converted", client.extractText("quarterly.pptx", "bytes".getBytes()));
        assertEquals(1, doclingHits.size());
        assertTrue(sidecarHits.isEmpty());
    }

    @Test
    void transportFailureFallsBackToSidecar() throws IOException {
        startBoth();
        doclingStatus.set(500);
        SidecarClient client = new SidecarClient(url(sidecarServer), "sidecar-secret");
        client.setDoclingConversionService(
                new DoclingConversionService(url(doclingServer), "docling-secret", true));

        assertEquals("converted", client.extractText("scan.pdf", "bytes".getBytes()));
        assertEquals(1, doclingHits.size());
        assertEquals(1, sidecarHits.size());
    }

    @Test
    void transportFailurePropagatesWhenFallbackDisabled() throws IOException {
        startBoth();
        doclingStatus.set(500);
        SidecarClient client = new SidecarClient(url(sidecarServer), "sidecar-secret");
        client.setDoclingConversionService(
                new DoclingConversionService(url(doclingServer), "docling-secret", false));

        assertThrows(
                SidecarCommunicationException.class,
                () -> client.extractText("scan.pdf", "bytes".getBytes()));
        assertTrue(sidecarHits.isEmpty());
    }

    @Test
    void fileErrorPropagatesWithoutFallback() throws IOException {
        startBoth();
        doclingStatus.set(400);
        SidecarClient client = new SidecarClient(url(sidecarServer), "sidecar-secret");
        client.setDoclingConversionService(
                new DoclingConversionService(url(doclingServer), "docling-secret", true));

        assertThrows(
                InvalidKnowledgeUploadException.class,
                () -> client.extractText("corrupt.pdf", "bytes".getBytes()));
        assertTrue(sidecarHits.isEmpty());
    }

    @Test
    void doclingServesExtractionWhenSidecarDisabled() throws IOException {
        startBoth();
        SidecarClient client = new SidecarClient(url(sidecarServer), "sidecar-secret");
        client.setDoclingConversionService(
                new DoclingConversionService(url(doclingServer), "docling-secret", true));
        client.setSidecarEnabled(false);

        assertEquals("converted", client.extractText("deck.pptx", "bytes".getBytes()));
        assertEquals(1, doclingHits.size());
        assertTrue(sidecarHits.isEmpty());
    }
}
