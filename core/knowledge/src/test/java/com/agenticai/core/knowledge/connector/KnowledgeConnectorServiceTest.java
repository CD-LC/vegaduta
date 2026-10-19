package com.agenticai.core.knowledge.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.knowledge.KnowledgeCollection;
import com.agenticai.core.knowledge.KnowledgeCollectionRepository;
import com.agenticai.core.knowledge.KnowledgeDocument;
import com.agenticai.core.knowledge.KnowledgeDocumentRepository;
import com.agenticai.core.knowledge.KnowledgeDocumentService;
import com.agenticai.core.knowledge.KnowledgeDocumentStatus;
import com.agenticai.core.knowledge.KnowledgeIngestionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sync semantics: re-fetch replaces (never appends), upstream-deleted docs
 * are pruned, secrets are encrypted at rest and redacted on read.
 */
class KnowledgeConnectorServiceTest {

    private KnowledgeConnectorRepository connectorRepository;
    private KnowledgeCollectionRepository collectionRepository;
    private KnowledgeDocumentRepository documentRepository;
    private KnowledgeDocumentService documentService;
    private KnowledgeIngestionService ingestionService;
    private EncryptionService encryption;
    private ConnectorFetcher fetcher;
    private KnowledgeConnectorService service;

    @BeforeEach
    void setUp() {
        connectorRepository = mock(KnowledgeConnectorRepository.class);
        collectionRepository = mock(KnowledgeCollectionRepository.class);
        documentRepository = mock(KnowledgeDocumentRepository.class);
        documentService = mock(KnowledgeDocumentService.class);
        ingestionService = mock(KnowledgeIngestionService.class);
        encryption = mock(EncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).substring(4));
        fetcher = mock(ConnectorFetcher.class);
        when(fetcher.getType()).thenReturn(ConnectorType.WEB_CRAWL);
        service = new KnowledgeConnectorService(
            connectorRepository, collectionRepository, documentRepository, documentService,
            ingestionService, encryption, List.of(fetcher));
    }

    private KnowledgeConnector connector() {
        KnowledgeConnector c = new KnowledgeConnector();
        c.setType(ConnectorType.WEB_CRAWL);
        c.setCollectionId(UUID.randomUUID());
        c.setConfigJson("{}");
        c.setEnabled(true);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", CONNECTOR_ID);
        return c;
    }

    private static final UUID CONNECTOR_ID = UUID.randomUUID();

    private KnowledgeDocument doc(String externalRef) {
        KnowledgeDocument d = new KnowledgeDocument(
            UUID.randomUUID(), "f.html", "html", 10, KnowledgeDocumentStatus.READY, null);
        d.attachConnectorSource("https://x/" + externalRef, externalRef, CONNECTOR_ID);
        org.springframework.test.util.ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        return d;
    }

    @Test
    void createEncryptsSecretKeys_butLeavesPlainOnes() {
        when(collectionRepository.existsById(any())).thenReturn(true);
        when(connectorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeConnector c = service.create(
            UUID.randomUUID(), ConnectorType.WEB_CRAWL, "docs",
            Map.of("urls", "https://a.example.com", "token", "sekrit"), "admin");

        assertTrue(c.getConfigJson().contains("\"urls\":\"https://a.example.com\""));
        assertTrue(c.getConfigJson().contains("\"token\":\"enc:sekrit\""));
        assertFalse(c.getConfigJson().contains("\"token\":\"sekrit\""));
    }

    @Test
    void redactedConfig_masksSecrets() {
        KnowledgeConnector c = connector();
        c.setConfigJson("{\"urls\":\"https://a.example.com\",\"token\":\"enc:sekrit\"}");
        Map<String, String> view = service.redactedConfig(c);
        assertEquals("https://a.example.com", view.get("urls"));
        assertEquals("***", view.get("token"));
    }

    @Test
    void sync_replacesExistingDocsAndPrunesMissing() {
        KnowledgeConnector c = connector();
        when(connectorRepository.findById(any())).thenReturn(Optional.of(c));

        KnowledgeDocument stale = doc("stale-page");
        KnowledgeDocument kept = doc("page-a");
        when(documentRepository.findByCollectionIdOrderByCreatedAtDesc(any()))
            .thenReturn(List.of(stale, kept));
        when(documentRepository.findByConnectorIdAndExternalRef(any(), eq("page-a")))
            .thenReturn(Optional.of(kept));
        when(documentRepository.findByConnectorIdAndExternalRef(any(), eq("page-b")))
            .thenReturn(Optional.empty());
        when(documentService.create(any(), anyString(), anyLong(), any(), any()))
            .thenReturn(doc("new"));

        when(fetcher.fetch(any(), any())).thenReturn(List.of(
            new ConnectorDocument("page-a", "A", "https://x/a", "a.md", "aa".getBytes(StandardCharsets.UTF_8)),
            new ConnectorDocument("page-b", "B", "https://x/b", "b.md", "bb".getBytes(StandardCharsets.UTF_8))
        ));
        when(connectorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeConnectorService.ConnectorSyncResult result = service.sync(CONNECTOR_ID);

        assertEquals(1, result.created());      // page-b is new
        assertEquals(1, result.refreshed());    // page-a was re-ingested
        assertEquals(1, result.pruned());       // stale-page vanished upstream
        assertEquals(0, result.failed());
        verify(documentService).delete(kept.getId());   // replaced before re-ingest
        verify(documentService).delete(stale.getId());  // pruned
        verify(ingestionService, org.mockito.Mockito.times(2))
            .ingestFile(anyString(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void sync_marksLastError_andRethrows_onFetchFailure() {
        KnowledgeConnector c = connector();
        when(connectorRepository.findById(any())).thenReturn(Optional.of(c));
        when(connectorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fetcher.fetch(any(), any())).thenThrow(new ConnectorFetchException("401 from provider"));

        org.junit.jupiter.api.Assertions.assertThrows(
            ConnectorFetchException.class, () -> service.sync(UUID.randomUUID()));
        assertEquals("401 from provider", c.getLastError());
        verify(ingestionService, never()).ingestFile(anyString(), any(), anyInt(), anyInt(), any(), any());
    }
}
