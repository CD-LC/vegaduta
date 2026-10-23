package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * How many chunks the knowledge-search tool asks for and how relevant they must be: the agent's retrieval top-k
 * and min-score ({@code AgentRuntimeSettings}), where the tool used to fix them at 3 and "everything".
 * Relevance is {@code -distance}: the hybrid search reports the negated fused / reranked score there.
 */
class KnowledgeSearchToolCallbackLimitsTest {

    private final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private static SearchResultResponse hit(String source, double distance) {
        return new SearchResultResponse(UUID.randomUUID(), source, 0, "text of " + source, UUID.randomUUID(), distance, null);
    }

    @Test
    void theDefaultsAreThreeChunksAndNoCutOffAsBefore() {
        when(search.search(anyString(), anyInt(), eq(Set.of()), anyBoolean())).thenReturn(List.of(hit("a", -0.001)));

        new KnowledgeSearchToolCallback(search, Set.of()).call("{\"query\":\"q\"}");

        verify(search).search("q", 3, Set.of(), false);
    }

    @Test
    void theAgentsTopKIsWhatTheSearchIsAskedFor() {
        when(search.search(anyString(), anyInt(), eq(Set.of()), anyBoolean())).thenReturn(List.of());

        new KnowledgeSearchToolCallback(search, Set.of(), 9, 0.0).call("{\"query\":\"q\"}");

        verify(search).search("q", 9, Set.of(), false);
    }

    @Test
    void chunksBelowTheMinScoreAreDroppedAndTheRestKeepTheirOrder() throws Exception {
        when(search.search(anyString(), anyInt(), eq(Set.of()), anyBoolean()))
            .thenReturn(List.of(hit("strong", -0.5), hit("middling", -0.2), hit("weak", -0.05)));

        String json = new KnowledgeSearchToolCallback(search, Set.of(), 5, 0.1).call("{\"query\":\"q\"}");

        JsonNode results = mapper.readTree(json);
        assertEquals(2, results.size(), json);
        assertEquals("strong", results.get(0).path("source").asText());
        assertEquals("middling", results.get(1).path("source").asText());
    }

    @Test
    void aMinScoreAboveEveryHitReturnsAnEmptyListNotAnError() throws Exception {
        when(search.search(anyString(), anyInt(), eq(Set.of()), anyBoolean())).thenReturn(List.of(hit("weak", -0.01)));

        String json = new KnowledgeSearchToolCallback(search, Set.of(), 3, 0.5).call("{\"query\":\"q\"}");

        assertTrue(mapper.readTree(json).isArray(), json);
        assertEquals(0, mapper.readTree(json).size(), json);
    }

    @Test
    void aZeroMinScoreKeepsEveryHitIncludingOnesWithNoScore() throws Exception {
        when(search.search(anyString(), anyInt(), eq(Set.of()), anyBoolean()))
            .thenReturn(List.of(hit("a", 0.0), hit("b", 0.3)));

        String json = new KnowledgeSearchToolCallback(search, Set.of(), 3, 0.0).call("{\"query\":\"q\"}");

        assertEquals(2, mapper.readTree(json).size(), json);
    }
}
