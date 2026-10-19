package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link WorkflowEventOutbox#recordFailure} is the one mutator {@link
 * WorkflowEventOutboxRelay} calls on every failed publish attempt, and its
 * retry-counting is what the relay's dead-letter cap ({@link
 * WorkflowEventOutboxRelay#MAX_RETRIES}) is built on top of - a bug here
 * (e.g. not incrementing, or accumulating rather than replacing {@code
 * lastError}) would silently break that cap. Covered indirectly by {@code
 * WorkflowEventOutboxRelayTest}; this suite isolates the entity's own two
 * mutators directly.
 */
class WorkflowEventOutboxTest {

    @Test
    void newRowStartsAtZeroRetriesAndUnpublished() {
        WorkflowEventOutbox row = new WorkflowEventOutbox("type", "run-1", "{}");

        assertEquals(0, row.getRetryCount());
        assertNull(row.getPublishedAt());
        assertNull(row.getLastError());
    }

    @Test
    void recordFailureIncrementsRetryCountEachCall() {
        WorkflowEventOutbox row = new WorkflowEventOutbox("type", "run-1", "{}");

        row.recordFailure("first failure");
        row.recordFailure("second failure");
        row.recordFailure("third failure");

        assertEquals(3, row.getRetryCount());
    }

    @Test
    void recordFailureReplacesLastErrorRatherThanAccumulatingIt() {
        WorkflowEventOutbox row = new WorkflowEventOutbox("type", "run-1", "{}");

        row.recordFailure("first failure");
        row.recordFailure("second failure");

        assertEquals("second failure", row.getLastError());
    }

    @Test
    void markPublishedStampsPublishedAt() {
        WorkflowEventOutbox row = new WorkflowEventOutbox("type", "run-1", "{}");

        row.markPublished();

        assertNotNull(row.getPublishedAt());
    }
}
