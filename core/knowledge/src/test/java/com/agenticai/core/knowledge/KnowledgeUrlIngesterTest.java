package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.KnowledgeUrlFetcher.FetchedUrl;
import com.agenticai.core.knowledge.dto.IngestResponse;
import com.agenticai.core.storage.FileStorageService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KnowledgeUrlIngester} - the fetch is stubbed via the package-private
 * constructor seam ({@link KnowledgeUrlFetcher} is final; the module's spring-boot-starter-test
 * Mockito 5 inline mockmaker mocks it), so the routing decisions - document vs text, filename
 * derivation, dedup/order-of-operations - are what get pinned, with no socket touched. Same manual
 * {@code mock(...)} convention as {@link AddToKnowledgeBaseToolCallbackTest}.
 */
class KnowledgeUrlIngesterTest {

    private KnowledgeIngestionService ingestionService;
    private KnowledgeCollectionService collectionService;
    private KnowledgeDocumentService documentService;
    private FileStorageService fileStorageService;
    private KnowledgeUrlFetcher fetcher;
    private KnowledgeUrlIngester ingester;
    private UUID collectionId;

    @BeforeEach
    void setUp() {
        ingestionService = mock(KnowledgeIngestionService.class);
        collectionService = mock(KnowledgeCollectionService.class);
        documentService = mock(KnowledgeDocumentService.class);
        fileStorageService = mock(FileStorageService.class);
        fetcher = mock(KnowledgeUrlFetcher.class);
        ingester = new KnowledgeUrlIngester(
            ingestionService, collectionService, documentService, fileStorageService, fetcher);
        collectionId = UUID.randomUUID();
        when(collectionService.resolveChunking(any(), any(), any()))
            .thenReturn(Chunker.Settings.DEFAULTS);
    }

    private FetchedUrl fetched(String url, String contentType, byte[] body) {
        return new FetchedUrl(URI.create(url), contentType, body);
    }

    @Test
    void pdfByExtensionGoesThroughTheDocumentPipeline() {
        byte[] pdf = "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch("https://example.com/report.pdf"))
            .thenReturn(fetched("https://example.com/report.pdf", "application/pdf", pdf));
        UUID docId = UUID.randomUUID();
        when(documentService.create(eq(collectionId), eq("report.pdf"), eq((long) pdf.length),
            eq(KnowledgeDocumentStatus.UPLOADED), anyString()))
            .thenReturn(new KnowledgeDocument(collectionId, "report.pdf", ".pdf",
                pdf.length, KnowledgeDocumentStatus.UPLOADED, "hash"));
        when(ingestionService.ingestFile(eq("report.pdf"), eq(pdf), anyInt(), anyInt(),
            eq(collectionId), any()))
            .thenReturn(new IngestResponse(9, 12345, List.of(UUID.randomUUID())));

        KnowledgeUrlIngester.UrlIngestResult result = ingester.ingestUrl(
            "https://example.com/report.pdf", collectionId, "source", null, null);

        assertTrue(result.document());
        assertEquals("report.pdf", result.filename());
        assertEquals(9, result.chunkCount());
        // Same order as the upload path: dedup check, row, stored original, then extraction.
        verify(documentService).requireNoDuplicate(eq(collectionId), anyString());
        verify(fileStorageService).put(anyString(), eq(pdf), eq("application/pdf"));
        verify(ingestionService, never()).ingest(anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void htmlByContentTypeWithExtensionlessUrlGoesThroughTheDocumentPipeline() {
        byte[] html = "<html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch("https://example.com/about"))
            .thenReturn(fetched("https://example.com/about", "text/html", html));
        when(documentService.create(eq(collectionId), eq("fetched-document.html"), eq((long) html.length),
            eq(KnowledgeDocumentStatus.UPLOADED), anyString()))
            .thenReturn(new KnowledgeDocument(collectionId, "fetched-document.html", ".html",
                html.length, KnowledgeDocumentStatus.UPLOADED, "hash"));
        when(ingestionService.ingestFile(anyString(), eq(html), anyInt(), anyInt(), eq(collectionId), any()))
            .thenReturn(new IngestResponse(3, 100, List.of(UUID.randomUUID())));

        KnowledgeUrlIngester.UrlIngestResult result = ingester.ingestUrl(
            "https://example.com/about", collectionId, "source", null, null);

        assertTrue(result.document());
        assertEquals("fetched-document.html", result.filename());
        verify(ingestionService, never()).ingest(anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void pdfMagicWithOctetStreamContentTypeIsStillTreatedAsDocument() {
        byte[] pdf = "%PDF-1.5 ".getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch("https://example.com/download?id=9"))
            .thenReturn(fetched("https://example.com/download?id=9", "application/octet-stream", pdf));
        when(documentService.create(eq(collectionId), eq("fetched-document.pdf"), anyLong(),
            eq(KnowledgeDocumentStatus.UPLOADED), anyString()))
            .thenReturn(new KnowledgeDocument(collectionId, "fetched-document.pdf", ".pdf",
                pdf.length, KnowledgeDocumentStatus.UPLOADED, "hash"));
        when(ingestionService.ingestFile(anyString(), eq(pdf), anyInt(), anyInt(), eq(collectionId), any()))
            .thenReturn(new IngestResponse(2, 10, List.of(UUID.randomUUID())));

        KnowledgeUrlIngester.UrlIngestResult result = ingester.ingestUrl(
            "https://example.com/download?id=9", collectionId, "source", null, null);

        assertTrue(result.document());
        assertEquals("fetched-document.pdf", result.filename());
    }

    @Test
    void plainTextIsIngestedAsTextWithTheCallersSourceLabel() {
        byte[] text = "plain text facts".getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch("https://example.com/notes.txt"))
            .thenReturn(fetched("https://example.com/notes.txt", "text/plain", text));
        when(ingestionService.ingest(eq("Agent memory: x - fetched from https://example.com/notes.txt"),
            eq("plain text facts"), anyInt(), anyInt(), eq(collectionId)))
            .thenReturn(new IngestResponse(4, 20, List.of(UUID.randomUUID())));

        KnowledgeUrlIngester.UrlIngestResult result = ingester.ingestUrl(
            "https://example.com/notes.txt", collectionId,
            "Agent memory: x - fetched from https://example.com/notes.txt", null, null);

        assertEquals(4, result.chunkCount());
        assertTrue(!result.document());
        verify(ingestionService, never()).ingestFile(anyString(), any(), anyInt(), anyInt(), any(), any());
        verify(documentService, never()).create(any(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void unknownBinaryThatIsNotDecodableIsRejectedCleanly() {
        byte[] binary = new byte[] {0, 1, 2, -1, -2, 100, 50, 9};
        when(fetcher.fetch("https://example.com/blob.bin"))
            .thenReturn(fetched("https://example.com/blob.bin", "application/octet-stream", binary));

        assertThrows(InvalidKnowledgeUploadException.class,
            () -> ingester.ingestUrl("https://example.com/blob.bin", collectionId, "s", null, null));
        verify(ingestionService, never()).ingest(anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void extractionFailureMarksTheDocumentFailedLikeUploadDoes() {
        byte[] pdf = "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch("https://example.com/report.pdf"))
            .thenReturn(fetched("https://example.com/report.pdf", "application/pdf", pdf));
        KnowledgeDocument doc = new KnowledgeDocument(collectionId, "report.pdf", ".pdf",
            pdf.length, KnowledgeDocumentStatus.UPLOADED, "hash");
        when(documentService.create(any(), anyString(), anyLong(), any(), anyString()))
            .thenReturn(doc);
        when(ingestionService.ingestFile(anyString(), eq(pdf), anyInt(), anyInt(), eq(collectionId), any()))
            .thenThrow(new RuntimeException("sidecar unavailable"));

        assertThrows(RuntimeException.class,
            () -> ingester.ingestUrl("https://example.com/report.pdf", collectionId, "s", null, null));
        verify(documentService).markFailed(any(), anyString());
    }
}
