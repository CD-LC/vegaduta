package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunDataStoreTest {

    private final WorkflowRunNodeOutputRepository repository = mock(WorkflowRunNodeOutputRepository.class);
    private final RunDataStore store = new RunDataStore(repository);

    @Test
    void ttlSweepKeepsOutputsOfRunsThatAreParkedAndWillResume() {
        Instant cutoff = Instant.now();
        when(repository.deleteOlderThan(any(), any())).thenReturn(3);

        assertEquals(3, store.deleteOlderThan(cutoff));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<WorkflowRunStatus>> parked = ArgumentCaptor.forClass(Collection.class);
        verify(repository).deleteOlderThan(org.mockito.ArgumentMatchers.eq(cutoff), parked.capture());
        assertTrue(parked.getValue().containsAll(java.util.List.of(
            WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.AWAITING_INPUT,
            WorkflowRunStatus.AWAITING_VOICE_INPUT, WorkflowRunStatus.PAUSED)));
        assertTrue(!parked.getValue().contains(WorkflowRunStatus.COMPLETED));
    }

    @Test
    void oversizedOutputIsStoredCutOffAndFlaggedSoReadsFailLoudly() {
        UUID run = UUID.randomUUID();
        store.record(run, "n1", "Fetch", 0, "OK", "x".repeat(RunDataStore.MAX_OUTPUT_CHARS + 10));

        ArgumentCaptor<WorkflowRunNodeOutput> saved = ArgumentCaptor.forClass(WorkflowRunNodeOutput.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().isTruncated());
        assertEquals(RunDataStore.MAX_OUTPUT_CHARS, saved.getValue().getOutputText().length());
    }

    @Test
    void lookupsOfUnknownNodesReturnNull() {
        UUID run = UUID.randomUUID();
        when(repository.findFirstByRunIdAndNodeLabelOrderBySeqDescCreatedAtDesc(run, "Nope")).thenReturn(Optional.empty());
        assertNull(store.latestByLabel(run, "Nope"));
        assertNull(store.latestByLabel(null, "Nope"));
        assertNull(store.latestById(run, null));
    }
}
