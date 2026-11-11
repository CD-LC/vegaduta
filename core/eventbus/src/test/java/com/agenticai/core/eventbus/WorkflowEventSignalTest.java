package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkflowEventSignal} is the in-JVM envelope {@code WorkflowEventPublisher} hands to
 * synchronous listeners - the record must carry the exact {@link WorkflowEvent} instance that is
 * about to hit the outbox, so subscriber and Kafka consumer see identical facts.
 */
class WorkflowEventSignalTest {

    @Test
    void theRecordCarriesTheSameEventInstance() {
        WorkflowEvent event =
                WorkflowEvent.of("webhook.test", "tenant_a", "run-1", Map.of("k", "v"));

        WorkflowEventSignal signal = new WorkflowEventSignal(event);

        assertSame(event, signal.event());
        assertEquals("webhook.test", signal.event().eventType());
        assertEquals("tenant_a", signal.event().tenantId());
        assertEquals("run-1", signal.event().runId());
    }
}
