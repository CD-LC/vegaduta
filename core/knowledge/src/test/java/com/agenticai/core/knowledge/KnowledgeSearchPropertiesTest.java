package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** {@code app.knowledge.search.*}: candidate-pool sizing and the RRF constant, through the real search service. */
class KnowledgeSearchPropertiesTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SidecarClient sidecarClient = mock(SidecarClient.class);
    private final KnowledgeCollectionRepository collections = mock(KnowledgeCollectionRepository.class);
    private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    private final UUID collectionId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private KnowledgeSearchService service(KnowledgeSearchProperties properties, List<SearchResultResponse> vector,
                                           List<SearchResultResponse> keyword) {
        KnowledgeCollection collection = mock(KnowledgeCollection.class);
        when(collection.getId()).thenReturn(collectionId);
        when(collection.getEmbeddingModel()).thenReturn("minilm-l6-v2");
        when(collections.findAll()).thenReturn(List.of(collection));
        when(documents.findAllById(any())).thenReturn(List.of());
        when(sidecarClient.embedQuery("q", "minilm-l6-v2")).thenReturn(new float[] {0.1f});
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("embedding OPERATOR(public.<=>)")),
            any(RowMapper.class), any(Object[].class))).thenReturn(vector);
        when(jdbcTemplate.query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")),
            any(RowMapper.class), any(Object[].class))).thenReturn(keyword);
        KnowledgeSearchService service = new KnowledgeSearchService(sidecarClient, jdbcTemplate, collections, documents, false);
        if (properties != null) {
            service.setSearchProperties(properties);
        }
        return service;
    }

    private SearchResultResponse row(UUID id) {
        return new SearchResultResponse(id, "s.md", 0, "text " + id, collectionId, 0.0, null);
    }

    @SuppressWarnings("unchecked")
    private int keywordPoolSizeFor(KnowledgeSearchProperties properties, int topK) {
        service(properties, List.of(), List.of()).search("q", topK);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(argThat(sql -> sql != null && sql.contains("ts_rank_cd")), any(RowMapper.class), params.capture());
        Object[] last = params.getValue();
        return (Integer) last[last.length - 1];
    }

    @Test
    void theDefaultsAreTheFormulaTheServiceAlwaysUsed() {
        KnowledgeSearchProperties defaults = new KnowledgeSearchProperties();
        assertEquals(20, defaults.candidatePoolSize(1));
        assertEquals(40, defaults.candidatePoolSize(10));
        assertEquals(50, defaults.candidatePoolSize(100));
        assertEquals(60, defaults.getRrfK());
    }

    @Test
    void theConfiguredPoolBoundsAndMultiplierAreWhatTheScansAreAskedFor() {
        KnowledgeSearchProperties properties = new KnowledgeSearchProperties();
        properties.setCandidatePoolMin(8);
        properties.setCandidatePoolMax(90);
        properties.setCandidatePoolMultiplier(10);

        assertEquals(30, keywordPoolSizeFor(properties, 3));
    }

    @Test
    void theRrfConstantChangesTheFusedScoreThatIsReportedAsTheNegatedDistance() {
        UUID only = UUID.randomUUID();
        KnowledgeSearchProperties properties = new KnowledgeSearchProperties();
        properties.setRrfK(10);

        List<SearchResultResponse> results = service(properties, List.of(row(only)), List.of()).search("q", 3);

        assertEquals(-(1.0 / (10 + 1)), results.get(0).distance(), 1e-9);
    }

    @Test
    void withoutBindingTheRrfConstantIsStillSixty() {
        UUID only = UUID.randomUUID();

        List<SearchResultResponse> results = service(null, List.of(row(only)), List.of()).search("q", 3);

        assertEquals(-(1.0 / 61), results.get(0).distance(), 1e-9);
    }
}
