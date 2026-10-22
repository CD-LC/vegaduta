package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link WorkflowEventPublisher} is, per its own javadoc, "the single entry point any part of this
 * codebase calls to emit a workflow event" - real call sites (Twilio/Meta/Slack/Discord webhooks)
 * call it unconditionally, so the {@code app.workflow-events.enabled} flag check happening INSIDE
 * this class rather than at every call site is what makes the feature safe to be off by default.
 * Had zero test coverage before this suite despite that "never break the caller" contract sitting
 * right next to a real failure mode (a payload Jackson can't serialize) whose whole point is to be
 * swallowed, not surfaced.
 */
class WorkflowEventPublisherTest {

    private WorkflowEventOutboxRepository outboxRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(WorkflowEventOutboxRepository.class);
        objectMapper = com.agenticai.core.json.JsonMappers.plain();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private WorkflowEventPublisher publisher(boolean enabled) {
        return new WorkflowEventPublisher(outboxRepository, objectMapper, enabled);
    }

    @Test
    void publishIsANoOpWhenTheFlagIsDisabledAndNeverTouchesTheOutbox() {
        publisher(false).publish("some.event", "run-1", Map.of("k", "v"));

        verifyNoInteractions(outboxRepository);
    }

    @Test
    void publishWritesAnOutboxRowWhenEnabled() {
        publisher(true)
                .publish("channel.whatsapp.message.received", "run-1", Map.of("from", "+1555"));

        org.mockito.ArgumentCaptor<WorkflowEventOutbox> captor =
                org.mockito.ArgumentCaptor.forClass(WorkflowEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals("channel.whatsapp.message.received", captor.getValue().getEventType());
        assertEquals("run-1", captor.getValue().getRunId());
        assertTrue(
                captor.getValue().getPayloadJson().contains("channel.whatsapp.message.received"));
    }

    @Test
    void publishDefaultsToThePublicSchemaWhenNoTenantContextIsSet() {
        publisher(true).publish("type", "run-1", Map.of());

        org.mockito.ArgumentCaptor<WorkflowEventOutbox> captor =
                org.mockito.ArgumentCaptor.forClass(WorkflowEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertTrue(captor.getValue().getPayloadJson().contains("\"public\""));
    }

    @Test
    void publishUsesTheCurrentTenantContextsSchemaWhenSet() {
        TenantContext.set(TenantContext.of("tenant_acme", "user-1", Set.of()));

        publisher(true).publish("type", "run-1", Map.of());

        org.mockito.ArgumentCaptor<WorkflowEventOutbox> captor =
                org.mockito.ArgumentCaptor.forClass(WorkflowEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertTrue(captor.getValue().getPayloadJson().contains("tenant_acme"));
    }

    @Test
    void publishSwallowsASerializationFailureRatherThanBreakingTheCallingBusinessFlow() {
        // Documented contract: "an inbound WhatsApp message should still
        // reach the agent even if event publishing itself is broken."
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new JacksonException("boom") {});
        } catch (JacksonException impossible) {
            throw new AssertionError(impossible);
        }
        WorkflowEventPublisher publisher =
                new WorkflowEventPublisher(outboxRepository, brokenMapper, true);

        assertDoesNotThrow(() -> publisher.publish("type", "run-1", Map.of("k", "v")));

        verify(outboxRepository, never()).save(any());
    }

    // --- publishNewRun() -------------------------------------------------------

    @Test
    void publishNewRunGeneratesAFreshRunIdAndReturnsIt() {
        String runId = publisher(true).publishNewRun("type", Map.of());

        assertTrue(runId != null && !runId.isBlank());
        org.mockito.ArgumentCaptor<WorkflowEventOutbox> captor =
                org.mockito.ArgumentCaptor.forClass(WorkflowEventOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals(runId, captor.getValue().getRunId());
    }

    @Test
    void publishNewRunGeneratesADifferentRunIdEachCall() {
        WorkflowEventPublisher publisher = publisher(true);

        String first = publisher.publishNewRun("type", Map.of());
        String second = publisher.publishNewRun("type", Map.of());

        assertNotEquals(first, second);
    }

    @Test
    void publishNewRunWhenDisabledStillReturnsAGeneratedRunIdEvenThoughNothingIsQueued() {
        // publishNewRun's own contract ("Returns the generated run id so a
        // caller... can reuse it") must hold regardless of the flag - a
        // caller building up a multi-event run shouldn't get a null/blank
        // id just because the feature happens to be off.
        String runId = publisher(false).publishNewRun("type", Map.of());

        assertTrue(runId != null && !runId.isBlank());
        verifyNoInteractions(outboxRepository);
    }
}
