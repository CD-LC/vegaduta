package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkflowEvent#of} and {@link WorkflowEvent#partitionKey()} are the
 * two behaviors every publisher/relay call in this module relies on - a
 * wrong partition key silently breaks the PRD's documented ordering
 * guarantee ("ordering guaranteed per run") since Kafka only orders records
 * within one partition. Zero coverage before this suite despite being pure,
 * dependency-free logic - the cheapest kind of bug to lock down.
 */
class WorkflowEventTest {

    @Test
    void ofStampsCurrentVersionAndLeavesWorkflowIdAndNodeIdNullForPhaseZero() {
        WorkflowEvent event = WorkflowEvent.of("channel.whatsapp.message.received", "tenant_a", "run-1", Map.of("k", "v"));

        assertEquals(WorkflowEvent.CURRENT_VERSION, event.version());
        assertNull(event.workflowId());
        assertNull(event.nodeId());
        assertEquals("tenant_a", event.tenantId());
        assertEquals("run-1", event.runId());
    }

    @Test
    void ofStampsATimestampCloseToNow() {
        Instant before = Instant.now();

        WorkflowEvent event = WorkflowEvent.of("type", "tenant", "run", Map.of());

        Instant after = Instant.now();
        assertTrue(!event.timestamp().isBefore(before) && !event.timestamp().isAfter(after));
    }

    @Test
    void partitionKeyCombinesTenantAndRunIdWithAColonSeparator() {
        WorkflowEvent event = WorkflowEvent.of("type", "tenant_a", "run-123", Map.of());

        assertEquals("tenant_a:run-123", event.partitionKey());
    }
}
