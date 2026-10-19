package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The three pin limits (per pin, pins per workflow, total characters per workflow) and the stored-as-given rule. */
class WorkflowPinStoreTest {

    private static final UUID WF = UUID.randomUUID();
    private static final UUID OTHER_WF = UUID.randomUUID();

    private WorkflowPinStore store;

    @BeforeEach
    void setUp() {
        store = new WorkflowPinStore(PinTestSupport.pinRepository());
    }

    private WorkflowNodePin pin(String nodeId, String data) {
        return store.save(WF, nodeId, data, "manual", null, "ann", "hash");
    }

    private static ResponseStatusException rejected(org.junit.jupiter.api.function.Executable e) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, e);
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        return ex;
    }

    @Test
    void aPinOfExactlyTheMaximumSizeIsAcceptedAndOneMoreCharacterIsNot() {
        assertEquals(256_000, pin("n1", "x".repeat(256_000)).getSizeChars());
        ResponseStatusException ex = rejected(() -> pin("n2", "x".repeat(256_001)));
        assertTrue(ex.getReason().contains("256000"), ex.getReason());
    }

    @Test
    void emptyOrBlankDataIsRejected() {
        rejected(() -> pin("n1", ""));
        rejected(() -> pin("n1", "   \n"));
        rejected(() -> pin("n1", null));
    }

    @Test
    void thirtyPinsAreAllowedAndTheThirtyFirstIsNot() {
        for (int i = 0; i < 30; i++) {
            pin("n" + i, "{}");
        }
        ResponseStatusException ex = rejected(() -> pin("n30", "{}"));
        assertTrue(ex.getReason().contains("30"), ex.getReason());
        // Re-pinning a node that already has a pin replaces it: it is not a 31st pin.
        assertEquals("[1]", pin("n5", "[1]").getDataText());
        assertEquals(30, store.list(WF).size());
    }

    @Test
    void theWorkflowTotalIsCappedAtTwoMillionCharactersAndAReplacementIsCountedOnce() {
        for (int i = 0; i < 7; i++) {
            pin("big" + i, "x".repeat(256_000)); // 1,792,000
        }
        pin("fill", "y".repeat(208_000)); // exactly 2,000,000
        ResponseStatusException ex = rejected(() -> pin("one-more", "z"));
        assertTrue(ex.getReason().contains("2000000"), ex.getReason());
        // Growing an existing pin by one character crosses the cap; replacing it with less does not.
        rejected(() -> pin("fill", "y".repeat(208_001)));
        assertEquals(10, pin("fill", "y".repeat(10)).getSizeChars());
        assertEquals("z", pin("one-more", "z").getDataText());
    }

    @Test
    void limitsAreCountedPerWorkflow() {
        for (int i = 0; i < 30; i++) {
            pin("n" + i, "{}");
        }
        assertEquals("ok", store.save(OTHER_WF, "n0", "ok", "manual", null, "ann", null).getDataText());
    }

    @Test
    void dataIsStoredExactlyAsGivenWhetherOrNotItIsJson() {
        String spaced = "{ \"a\" :  1,\n  \"b\":[1, 2] }";
        assertEquals(spaced, pin("n1", spaced).getDataText());
        assertEquals("plain text, not json", pin("n2", "plain text, not json").getDataText());
        assertEquals(spaced, store.find(WF, "n1").orElseThrow().getDataText());
    }

    @Test
    void anUnknownSourceIsRejectedAndEveryDocumentedSourceIsAccepted() {
        rejected(() -> store.save(WF, "n1", "{}", "clipboard", null, "ann", null));
        for (String source : List.of("test", "run", "manual", "trigger_sample")) {
            assertEquals(source, store.save(WF, "n-" + source, "{}", source, "run-1", "ann", null).getSource());
        }
    }

    @Test
    void savingRecordsWhoWhenAndWhereTheDataCameFrom() {
        WorkflowNodePin p = store.save(WF, "n1", "{}", "run", "run-42", "ann", "h1");
        assertEquals("ann", p.getPinnedBy());
        assertEquals("run-42", p.getSourceRunId());
        assertEquals("h1", p.getConfigHash());
        assertTrue(p.getPinnedAt() != null);
    }

    @Test
    void deleteReportsWhetherAPinExisted() {
        pin("n1", "{}");
        assertTrue(store.delete(WF, "n1"));
        assertFalse(store.delete(WF, "n1"));
        assertTrue(store.find(WF, "n1").isEmpty());
    }

    @Test
    void aGraphSavePrunesThePinsOfNodesThatAreGone() {
        pin("keep", "{}");
        pin("gone1", "{}");
        pin("gone2", "{}");

        assertEquals(2, store.deleteForRemovedNodes(WF, List.of("keep", "other")));

        assertEquals(List.of("keep"), store.list(WF).stream().map(WorkflowNodePin::getNodeId).toList());
        assertEquals(0, store.deleteForRemovedNodes(WF, List.of("keep")));
    }
}
