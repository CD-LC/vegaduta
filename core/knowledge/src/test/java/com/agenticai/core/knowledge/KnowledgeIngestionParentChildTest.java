package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.IngestResponse;
import com.agenticai.core.knowledge.dto.SidecarChunk;
import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import com.agenticai.core.knowledge.ocr.OcrProviderConfigService;
import com.agenticai.core.knowledge.rag.KnowledgeRagSettingsService;
import com.agenticai.core.knowledge.rag.ParentChildChunker;
import com.agenticai.core.knowledge.rag.ParentChunkStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Ingestion with parent-child on (collection opted in) and the unchanged flat path otherwise. */
class KnowledgeIngestionParentChildTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String TEXT =
            "# Refunds\nRefunds are issued within 14 days. Contact support first.\n\n"
                    + "# Shipping\nShipping takes 5 days. Express takes 2 days.\n\nTracking numbers are emailed.";

    private SidecarClient sidecarClient;
    private JdbcTemplate jdbcTemplate;
    private KnowledgeIngestionService service;
    private UUID collectionId;
    private final List<Object[]> insertedChunks = new ArrayList<>();
    private List<String> embeddedPieces;

    @BeforeEach
    void setUp() {
        sidecarClient = mock(SidecarClient.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeCollectionRepository collections = mock(KnowledgeCollectionRepository.class);
        collectionId = UUID.randomUUID();
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.getEmbeddingModel()).thenReturn("minilm-l6-v2");
        when(collection.getTextSearchConfig()).thenReturn("simple");
        when(collections.findById(collectionId)).thenReturn(Optional.of(collection));

        when(sidecarClient.embedPreChunked(anyList(), anyString()))
                .thenAnswer(
                        inv -> {
                            List<String> pieces = inv.getArgument(0);
                            embeddedPieces = pieces;
                            List<SidecarChunk> chunks = new ArrayList<>();
                            for (int i = 0; i < pieces.size(); i++) {
                                chunks.add(new SidecarChunk(i, pieces.get(i), List.of(0.1, 0.2)));
                            }
                            return new SidecarEmbedResponse("minilm-l6-v2", 768, 200, 0, chunks);
                        });
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenAnswer(
                        inv -> {
                            String sql = inv.getArgument(0);
                            if (sql.startsWith("INSERT INTO")) {
                                insertedChunks.add(inv.getArguments());
                            }
                            return 1;
                        });

        service =
                new KnowledgeIngestionService(
                        sidecarClient,
                        jdbcTemplate,
                        collections,
                        mock(KnowledgeDocumentService.class),
                        mock(OcrProviderConfigService.class));
    }

    @Test
    void flatIngestionIsUnchangedWhenParentChildIsNotWired() {
        IngestResponse r = service.ingest("doc.md", TEXT, 150, 30, collectionId);

        assertEquals(Chunker.chunk(TEXT, new Chunker.Settings(150, 30)), embeddedPieces);
        assertEquals(embeddedPieces.size(), r.chunkCount());
        for (Object[] row : insertedChunks) {
            assertEquals(
                    "{\"source_type\":\"text\"}",
                    row[row.length - 1],
                    "metadata exactly as before");
        }
    }

    @Test
    void flatIngestionIsUnchangedWhenTheCollectionDidNotOptIn() {
        KnowledgeRagSettingsService settings = mock(KnowledgeRagSettingsService.class);
        when(settings.parentChildIngestSettings(collectionId)).thenReturn(Optional.empty());
        ParentChunkStore store = mock(ParentChunkStore.class);
        service.setParentChild(settings, store);

        service.ingest("doc.md", TEXT, 150, 30, collectionId);

        assertEquals(Chunker.chunk(TEXT, new Chunker.Settings(150, 30)), embeddedPieces);
        verify(store, never()).insertAll(any(), any(), any());
        for (Object[] row : insertedChunks) {
            assertEquals("{\"source_type\":\"text\"}", row[row.length - 1]);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void optedInCollectionsEmbedChildrenStoreParentsAndLinkEachChildToItsParent() throws Exception {
        KnowledgeRagSettingsService settings = mock(KnowledgeRagSettingsService.class);
        when(settings.parentChildIngestSettings(collectionId))
                .thenReturn(Optional.of(new ParentChildChunker.Settings(10, 20)));
        ParentChunkStore store = mock(ParentChunkStore.class);
        service.setParentChild(settings, store);

        IngestResponse r = service.ingest("policy.md", TEXT, 150, 30, collectionId);

        // Only the small children are embedded.
        List<ParentChildChunker.Parent> expected =
                ParentChildChunker.chunk(TEXT, new ParentChildChunker.Settings(10, 20));
        List<String> expectedChildren =
                expected.stream()
                        .flatMap(p -> p.children().stream())
                        .map(ParentChildChunker.Child::text)
                        .toList();
        assertEquals(expectedChildren, embeddedPieces);
        assertEquals(expectedChildren.size(), r.chunkCount());

        // The parents are persisted once each, with this collection and no document (raw text
        // ingest).
        ArgumentCaptor<List<ParentChunkStore.ParentRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(store).insertAll(eq(collectionId), eq((UUID) null), rows.capture());
        assertEquals(2, rows.getValue().size());
        assertEquals(expected.get(0).text(), rows.getValue().get(0).content());
        assertEquals(1, rows.getValue().get(1).index());

        // Every child row carries its parent's id and its exact offsets inside the parent text.
        assertEquals(expectedChildren.size(), insertedChunks.size());
        int childNumber = 0;
        for (int p = 0; p < expected.size(); p++) {
            for (ParentChildChunker.Child child : expected.get(p).children()) {
                Object[] row = insertedChunks.get(childNumber++);
                JsonNode metadata = MAPPER.readTree((String) row[row.length - 1]);
                assertEquals("text", metadata.get("source_type").asString());
                assertEquals(
                        rows.getValue().get(p).id().toString(),
                        metadata.get("parent_id").asString());
                assertEquals(child.startInParent(), metadata.get("child_start").asInt());
                assertEquals(child.endInParent(), metadata.get("child_end").asInt());
                String content = (String) row[4]; // sql, id, source, chunk_index, content, ...
                assertEquals(child.text(), content);
                assertEquals(
                        content,
                        rows.getValue()
                                .get(p)
                                .content()
                                .substring(
                                        metadata.get("child_start").asInt(),
                                        metadata.get("child_end").asInt()));
            }
        }
        assertTrue(childNumber > 0);
    }

    @Test
    void anEmptyParentChildResultFallsBackToTheNormalNoChunksError() {
        KnowledgeRagSettingsService settings = mock(KnowledgeRagSettingsService.class);
        when(settings.parentChildIngestSettings(collectionId))
                .thenReturn(Optional.of(new ParentChildChunker.Settings(10, 20)));
        service.setParentChild(settings, mock(ParentChunkStore.class));

        org.junit.jupiter.api.Assertions.assertThrows(
                InvalidKnowledgeUploadException.class,
                () -> service.ingest("empty.md", "   ", 150, 30, collectionId));
    }
}
