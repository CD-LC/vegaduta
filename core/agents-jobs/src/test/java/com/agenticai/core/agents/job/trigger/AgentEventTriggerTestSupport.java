package com.agenticai.core.agents.job.trigger;

import com.agenticai.core.eventbus.WorkflowEvent;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Shared fixtures for the event-trigger unit tests.
 *
 * <p>Plain objects and plain Mockito mocks, no Spring context and no database -
 * the standing rule for {@code core/agents} tests, since a full {@code mvn test}
 * in {@code core/app} boots a real Postgres and these have to stay runnable in
 * seconds.
 *
 * <p>Deliberately does NOT reuse {@code AgentJobTestSupport}: that class is
 * package-private in {@code ..agents.job} and its fixtures are about a job's
 * execution, which is exactly the thing a trigger never does.
 */
final class AgentEventTriggerTestSupport {

    static final String TENANT = "t_test";
    static final String CREATED_BY = "alice@example.com";
    static final String WHATSAPP = WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED;

    private AgentEventTriggerTestSupport() {
    }

    static AgentEventTrigger trigger(String eventType, String filterJson, String goalTemplate) {
        return AgentEventTrigger.create(
            UUID.randomUUID(), UUID.randomUUID(), eventType, filterJson, goalTemplate, CREATED_BY, Instant.now());
    }

    static AgentEventTrigger whatsappTrigger() {
        return trigger(WHATSAPP, null, "Triage the new WhatsApp message from {{payload.fromMasked}}.");
    }

    /** A realistic inbound-channel event: the payload shape every channel publisher actually writes. */
    static WorkflowEvent whatsappEvent() {
        return whatsappEvent(UUID.randomUUID().toString());
    }

    static WorkflowEvent whatsappEvent(String runId) {
        return WorkflowEvent.of(
            WHATSAPP, TENANT, runId,
            Map.of("channel", "whatsapp", "fromMasked", "+91*****1234", "bodyLength", 42));
    }
}
