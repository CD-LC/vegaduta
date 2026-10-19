package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.ParentExpander.ParentLink;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Small-to-big hand-off: parent text out, child span kept, de-dup by parent. */
class ParentExpanderTest {

    private static final String PARENT = "Refunds are issued within 14 days. Contact support first.";

    private static SearchResultResponse child(String content, double distance, UUID docId) {
        return new SearchResultResponse(UUID.randomUUID(), "policy.md", 3, content, UUID.randomUUID(), distance, docId, "https://src");
    }

    @Test
    void swapsInTheParentTextAndKeepsTheChildCitation() {
        UUID docId = UUID.randomUUID();
        SearchResultResponse hit = child("Contact support first.", -0.03, docId);
        UUID parentId = UUID.randomUUID();
        int start = PARENT.indexOf("Contact");

        List<SearchResultResponse> out = ParentExpander.expand(
            List.of(hit), Map.of(hit.id(), new ParentLink(parentId, PARENT, start, PARENT.length())));

        SearchResultResponse r = out.get(0);
        assertEquals(PARENT, r.content());
        assertEquals(hit.id(), r.id(), "the citation still points at the matching child");
        assertEquals(3, r.chunkIndex());
        assertEquals("policy.md", r.source());
        assertEquals(docId, r.documentId());
        assertEquals(hit.collectionId(), r.collectionId());
        assertEquals("https://src", r.sourceUrl());
        assertEquals(-0.03, r.distance());
        assertEquals(start, r.startChar());
        assertEquals(PARENT.length(), r.endChar());
        assertEquals("Contact support first.", r.quote());
        assertEquals(r.quote(), r.content().substring(r.startChar(), r.endChar()));
    }

    @Test
    void laterChildrenOfTheSameParentAreDropped() {
        UUID parentId = UUID.randomUUID();
        SearchResultResponse best = child("Refunds are issued within 14 days.", -0.05, null);
        SearchResultResponse sibling = child("Contact support first.", -0.04, null);
        SearchResultResponse other = child("Unrelated flat chunk.", -0.03, null);
        UUID otherParent = UUID.randomUUID();
        SearchResultResponse elsewhere = child("Shipping takes 5 days.", -0.02, null);

        List<SearchResultResponse> out = ParentExpander.expand(
            List.of(best, sibling, other, elsewhere),
            Map.of(
                best.id(), new ParentLink(parentId, PARENT, 0, 34),
                sibling.id(), new ParentLink(parentId, PARENT, 35, PARENT.length()),
                elsewhere.id(), new ParentLink(otherParent, "Shipping takes 5 days. Express takes 2.", 0, 22)));

        assertEquals(3, out.size());
        assertEquals(best.id(), out.get(0).id());
        assertEquals(other.id(), out.get(1).id());
        assertEquals(elsewhere.id(), out.get(2).id());
        assertEquals(PARENT, out.get(0).content());
    }

    @Test
    void aFlatChunkWithNoParentPassesThroughUntouchedAndIsNeverDeduped() {
        SearchResultResponse a = child("flat one", -0.1, null);
        SearchResultResponse b = child("flat two", -0.09, null);

        List<SearchResultResponse> out = ParentExpander.expand(List.of(a, b), Map.of(UUID.randomUUID(), new ParentLink(UUID.randomUUID(), "x", 0, 1)));

        assertEquals(2, out.size());
        assertSame(a, out.get(0));
        assertSame(b, out.get(1));
    }

    @Test
    void noLinksReturnsTheInputUnchanged() {
        List<SearchResultResponse> in = List.of(child("c", -0.1, null));
        assertSame(in, ParentExpander.expand(in, Map.of()));
        assertSame(in, ParentExpander.expand(in, null));
    }

    @Test
    void anOutOfRangeSpanIsDroppedButTheParentTextIsStillReturned() {
        SearchResultResponse hit = child("c", -0.1, null);

        SearchResultResponse r = ParentExpander.expand(
            List.of(hit), Map.of(hit.id(), new ParentLink(UUID.randomUUID(), "short", 3, 99))).get(0);

        assertEquals("short", r.content());
        assertNull(r.startChar());
        assertNull(r.endChar());
        assertNull(r.quote());
    }
}
