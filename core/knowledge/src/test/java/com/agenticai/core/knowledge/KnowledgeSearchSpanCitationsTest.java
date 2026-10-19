package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** attachSpans on/off, and the NON_NULL omission of span fields in JSON. */
class KnowledgeSearchSpanCitationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTENT = "Refunds are issued within 14 days. Shipping to Canada takes 5 to 7 business days.";

    private KnowledgeSearchService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeSearchService(mock(SidecarClient.class), mock(JdbcTemplate.class),
            mock(KnowledgeCollectionRepository.class), mock(KnowledgeDocumentRepository.class), false);
    }

    private static SearchResultResponse hit() {
        UUID id = UUID.randomUUID();
        return new SearchResultResponse(id, "doc.pdf", 0, CONTENT, id, 0.2, id, "https://x");
    }

    @Test
    void flagOffLeavesHitsUntouched() {
        List<SearchResultResponse> out = service.attachSpans("shipping to Canada", List.of(hit()));
        assertNull(out.get(0).quote());
        assertNull(out.get(0).startChar());
    }

    @Test
    void flagOnAttachesBestSentenceWithMatchingOffsets() {
        service.setSpanCitationsEnabled(true);
        SearchResultResponse r = service.attachSpans("shipping to Canada", List.of(hit())).get(0);
        assertEquals("Shipping to Canada takes 5 to 7 business days.", r.quote());
        assertEquals(r.quote(), CONTENT.substring(r.startChar(), r.endChar()));
        assertEquals("https://x", r.sourceUrl());
        assertEquals(0.2, r.distance());
    }

    @Test
    void flagOnWithNoOverlapKeepsChunkLevelCitation() {
        service.setSpanCitationsEnabled(true);
        SearchResultResponse r = service.attachSpans("quantum chromodynamics", List.of(hit())).get(0);
        assertNull(r.quote());
    }

    @Test
    void nullSpanFieldsAreOmittedFromJsonAndPresentOnesAreKept() throws Exception {
        JsonNode plain = MAPPER.readTree(MAPPER.writeValueAsString(hit()));
        assertFalse(plain.has("startChar"));
        assertFalse(plain.has("endChar"));
        assertFalse(plain.has("quote"));
        assertTrue(plain.has("sourceUrl"));

        JsonNode spanned = MAPPER.readTree(MAPPER.writeValueAsString(hit().withSpan(0, 7, "Refunds")));
        assertEquals(0, spanned.get("startChar").asInt());
        assertEquals(7, spanned.get("endChar").asInt());
        assertEquals("Refunds", spanned.get("quote").asText());
    }
}
