package com.agenticai.core.workflow.poll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PollItemExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void bareArrayWithIdPath() {
        List<PollItemExtractor.PolledItem> items = PollItemExtractor.extract(
            MAPPER, "[{\"id\":\"a\",\"v\":1},{\"id\":\"b\",\"v\":2}]", "", "id");
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).id());
        assertEquals("b", items.get(1).id());
        assertEquals(1, items.get(0).item().get("v").asInt());
    }

    @Test
    void nestedItemsPath() {
        String body = "{\"data\":{\"items\":[{\"id\":\"x\"}]},\"total\":1}";
        List<PollItemExtractor.PolledItem> items =
            PollItemExtractor.extract(MAPPER, body, "data.items", "id");
        assertEquals(1, items.size());
        assertEquals("x", items.get(0).id());
    }

    @Test
    void arrayIndexSegment() {
        String body = "{\"results\":[[{\"id\":\"deep\"}]]}";
        List<PollItemExtractor.PolledItem> items =
            PollItemExtractor.extract(MAPPER, body, "results.0", "id");
        assertEquals(1, items.size());
        assertEquals("deep", items.get(0).id());
    }

    @Test
    void singleObjectIsOneItem() {
        List<PollItemExtractor.PolledItem> items =
            PollItemExtractor.extract(MAPPER, "{\"id\":\"solo\"}", "", "id");
        assertEquals(1, items.size());
        assertEquals("solo", items.get(0).id());
    }

    @Test
    void missingIdFallsBackToContentHash() {
        List<PollItemExtractor.PolledItem> items =
            PollItemExtractor.extract(MAPPER, "[{\"name\":\"no-id\"}]", "", "id");
        assertEquals(1, items.size());
        assertTrue(items.get(0).id().startsWith("sha256:"));
        // Same content hashes to the same id - the dedup contract.
        List<PollItemExtractor.PolledItem> again =
            PollItemExtractor.extract(MAPPER, "[{\"name\":\"no-id\"}]", "", "id");
        assertEquals(items.get(0).id(), again.get(0).id());
    }

    @Test
    void missingItemsPathYieldsEmpty() {
        assertTrue(PollItemExtractor.extract(MAPPER, "{\"other\":[]}", "data.items", "id").isEmpty());
    }

    @Test
    void invalidJsonIsAPollFailure() {
        assertThrows(PollFetchException.class,
            () -> PollItemExtractor.extract(MAPPER, "not json", "", "id"));
    }

    @Test
    void emptyBodyYieldsEmpty() {
        assertTrue(PollItemExtractor.extract(MAPPER, "", "", "id").isEmpty());
    }
}
