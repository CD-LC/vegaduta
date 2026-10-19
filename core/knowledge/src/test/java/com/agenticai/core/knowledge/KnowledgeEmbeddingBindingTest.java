package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.IngestResponse;
import com.agenticai.core.knowledge.dto.SidecarChunk;
import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.ocr.OcrProviderConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The dimension/model-change guard: a collection records which embedding source built its vectors;
 * a different live source is refused on ingest (409, with the way out), skipped by vector search,
 * and an unbound collection binds on first use so existing installs behave as before.
 */
class KnowledgeEmbeddingBindingTest {

    private static final String OLD = "ollama:nomic-embed-text:768";
    private static final String LIVE = "ollama:bge-m3:1024";

    private SidecarClient sidecarClient;
    private JdbcTemplate jdbcTemplate;
    private KnowledgeEmbeddingBinding binding;

    @BeforeEach
    void setUp() {
        sidecarClient = mock(SidecarClient.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(sidecarClient.embeddingFingerprint()).thenReturn(LIVE);
        binding = new KnowledgeEmbeddingBinding(sidecarClient, jdbcTemplate, true);
    }

    private static KnowledgeCollection collection(String fingerprint) {
        KnowledgeCollection c = mock(KnowledgeCollection.class);
        when(c.getId()).thenReturn(UUID.randomUUID());
        when(c.getName()).thenReturn("Policies");
        when(c.getEmbeddingModel()).thenReturn("minilm-l6-v2");
        when(c.getEmbeddingFingerprint()).thenReturn(fingerprint);
        return c;
    }

    // --- the guard itself -------------------------------------------------------------------

    @Test
    void aCollectionBuiltByAnotherSourceIsRefusedWithTheWayOut() {
        KnowledgeCollection c = collection(OLD);

        ResponseStatusException e = assertThrows(EmbeddingModelMismatchException.class, () -> binding.requireWritable(c));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        String msg = e.getReason();
        assertTrue(msg.contains(OLD) && msg.contains(LIVE), msg);
        assertTrue(msg.contains("/reembed") && msg.contains("minilm-l6-v2"), msg);
    }

    @Test
    void theSameSourceAnUnboundCollectionAndAnUnknownLiveSourceAreAllowed() {
        binding.requireWritable(collection(LIVE));
        binding.requireWritable(collection(null));
        binding.requireWritable(collection("  "));
        when(sidecarClient.embeddingFingerprint()).thenReturn(null);
        binding.requireWritable(collection(OLD));
        when(sidecarClient.embeddingFingerprint()).thenThrow(new SidecarCommunicationException("ollama down", null));
        binding.requireWritable(collection(OLD)); // cannot be determined: the embed call itself reports the outage
    }

    @Test
    void disabledGuardNeverBlocksAndNeverFiltersOrBinds() {
        KnowledgeEmbeddingBinding off = new KnowledgeEmbeddingBinding(sidecarClient, jdbcTemplate, false);
        KnowledgeCollection c = collection(OLD);
        off.requireWritable(c);
        assertTrue(off.isSearchable(c));
        List<KnowledgeCollection> all = List.of(c);
        assertSame(all, off.searchable(all));
        off.bindIfUnbound(UUID.randomUUID());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void bindingOnlyEverFillsAnEmptyFingerprint() {
        UUID id = UUID.randomUUID();
        binding.bindIfUnbound(id);
        verify(jdbcTemplate).update(
            argThat((String sql) -> sql.contains("knowledge_collection") && sql.contains("SET embedding_fingerprint = ?")
                && sql.contains("embedding_fingerprint IS NULL")),
            eq(LIVE), eq(id));
    }

    @Test
    void searchSkipsMismatchedCollectionsAndKeepsTheRest() {
        KnowledgeCollection stale = collection(OLD);
        KnowledgeCollection current = collection(LIVE);
        KnowledgeCollection unbound = collection(null);

        List<KnowledgeCollection> kept = binding.searchable(List.of(stale, current, unbound));

        assertEquals(List.of(current, unbound), kept);
        List<KnowledgeCollection> allGood = List.of(current, unbound);
        assertSame(allGood, binding.searchable(allGood), "no copy when nothing is rejected");
    }

    // --- ingestion --------------------------------------------------------------------------

    private KnowledgeIngestionService ingestionFor(KnowledgeCollection collection, UUID collectionId) {
        KnowledgeCollectionRepository collections = mock(KnowledgeCollectionRepository.class);
        when(collections.findById(collectionId)).thenReturn(Optional.of(collection));
        when(collection.getTextSearchConfig()).thenReturn("simple");
        when(sidecarClient.embedPreChunked(anyList(), anyString())).thenAnswer(inv -> {
            List<String> pieces = inv.getArgument(0);
            List<SidecarChunk> chunks = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                chunks.add(new SidecarChunk(i, pieces.get(i), List.of(0.1, 0.2)));
            }
            return new SidecarEmbedResponse("minilm-l6-v2", 1024, 200, 0, chunks);
        });
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
            sidecarClient, jdbcTemplate, collections, mock(KnowledgeDocumentService.class), mock(OcrProviderConfigService.class));
        service.setEmbeddingBinding(binding);
        return service;
    }

    @Test
    void ingestIntoACollectionBuiltByAnotherSourceIsRefusedBeforeAnyEmbeddingOrWrite() {
        UUID id = UUID.randomUUID();
        KnowledgeIngestionService service = ingestionFor(collection(OLD), id);

        assertThrows(EmbeddingModelMismatchException.class, () -> service.ingest("doc.md", "some text here", 150, 30, id));

        verify(sidecarClient, never()).embedPreChunked(anyList(), anyString());
        verify(jdbcTemplate, never()).update(argThat((String sql) -> sql.startsWith("INSERT INTO")), any(Object[].class));
    }

    @Test
    void firstIngestBindsAnUnboundCollectionToTheLiveSource() {
        UUID id = UUID.randomUUID();
        KnowledgeIngestionService service = ingestionFor(collection(null), id);

        IngestResponse r = service.ingest("doc.md", "some text here", 150, 30, id);

        assertNotNull(r);
        verify(jdbcTemplate).update(argThat((String sql) -> sql.contains("SET embedding_fingerprint = ?")), eq(LIVE), eq(id));
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
            .update(argThat((String sql) -> sql.startsWith("INSERT INTO")), any(Object[].class));
    }

    @Test
    void ingestIntoACollectionOnTheSameSourceProceedsWithoutRebinding() {
        UUID id = UUID.randomUUID();
        KnowledgeIngestionService service = ingestionFor(collection(LIVE), id);

        service.ingest("doc.md", "some text here", 150, 30, id);

        // The bind statement is issued (it is a guarded no-op in SQL), but nothing is refused.
        verify(sidecarClient).embedPreChunked(anyList(), anyString());
    }

    // --- vector search ----------------------------------------------------------------------

    @Test
    void vectorSearchOverOnlyMismatchedCollectionsReturnsNothingWithoutEmbeddingTheQuery() {
        KnowledgeChunkScanner scanner = new KnowledgeChunkScanner(sidecarClient, jdbcTemplate, null, 0, "off");
        scanner.setEmbeddingBinding(binding);

        List<SearchResultResponse> hits = scanner.vectorSearch(
            "q", List.of(collection(OLD)), 20, "t.knowledge_chunks", false, null);

        assertTrue(hits.isEmpty());
        verify(sidecarClient, never()).embedQuery(anyString(), anyString());
    }

    @Test
    void vectorSearchStillEmbedsAndScansTheCompatibleCollections() {
        when(sidecarClient.embedQuery(anyString(), anyString())).thenReturn(new float[] {0.5f, 0.25f});
        List<Object> firstParam = new ArrayList<>();
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenAnswer(inv -> {
                firstParam.add(inv.getArguments()[2]); // first bind parameter = the query vector literal
                return List.of();
            });
        KnowledgeChunkScanner scanner = new KnowledgeChunkScanner(sidecarClient, jdbcTemplate, null, 0, "off");
        scanner.setEmbeddingBinding(binding);

        scanner.vectorSearch("q", List.of(collection(OLD), collection(LIVE)), 20, "t.knowledge_chunks", false, null);

        verify(sidecarClient).embedQuery("q", "minilm-l6-v2");
        assertEquals(List.of("[0.5,0.25]"), firstParam);
        assertFalse(binding.isSearchable(collection(OLD)));
    }

    @Test
    void vectorLiteralFromFloatsMatchesThePgvectorTextFormat() {
        assertEquals("[0.5,0.25,-1.0]", KnowledgeIngestionService.toVectorLiteral(new float[] {0.5f, 0.25f, -1f}));
        assertEquals("[]", KnowledgeIngestionService.toVectorLiteral(new float[0]));
        assertEquals("[0.1,0.2]", KnowledgeIngestionService.toVectorLiteral(new float[] {0.1f, 0.2f}));
    }
}
