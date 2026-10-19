package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.dto.KnowledgeDocumentResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** X-Total-Count / X-Has-More and ?offset= on GET /api/knowledge/collections/{id}/documents. */
class KnowledgeCollectionControllerListHeadersTest {

    @org.junit.jupiter.api.BeforeEach
    void defaultCapOn() {
        System.setProperty("agenticai.list.default-cap", "true");
    }

    @org.junit.jupiter.api.AfterEach
    void defaultCapOff() {
        System.clearProperty("agenticai.list.default-cap");
    }

    private KnowledgeDocumentService documents;
    private KnowledgeCollectionController controller;
    private final UUID id = UUID.randomUUID();
    private final KnowledgeDocumentResponse row = mock(KnowledgeDocumentResponse.class);

    @BeforeEach
    void setUp() {
        documents = mock(KnowledgeDocumentService.class);
        controller =
                new KnowledgeCollectionController(
                        mock(KnowledgeCollectionService.class), documents);
    }

    @Test
    void shortPageSkipsCountAndReportsNoMore() {
        when(documents.listByCollection(eq(id), eq(5), any())).thenReturn(List.of(row));
        var res = controller.documents(id, 5, null);
        assertEquals("1", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
        verify(documents, never()).countByCollection(any());
    }

    @Test
    void fullPageCountsAndFlagsMore() {
        when(documents.listByCollection(eq(id), eq(1), any())).thenReturn(List.of(row));
        when(documents.countByCollection(id)).thenReturn(900L);
        var res = controller.documents(id, 1, null);
        assertEquals("900", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void offsetIsPassedThrough() {
        when(documents.listByCollection(eq(id), eq(10), eq(500L))).thenReturn(List.of(row));
        when(documents.countByCollection(id)).thenReturn(501L);
        var res = controller.documents(id, 10, 500L);
        assertEquals("501", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }
}
