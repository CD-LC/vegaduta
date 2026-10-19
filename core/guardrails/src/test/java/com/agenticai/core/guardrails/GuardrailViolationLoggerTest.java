package com.agenticai.core.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.audit.AuditLogService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** The logger's audit behaviour is unchanged; on top of it a block is announced as an event, best effort. */
class GuardrailViolationLoggerTest {

    private final AuditLogService audit = mock(AuditLogService.class);
    private final GuardrailViolationLogger logger = new GuardrailViolationLogger(audit);
    private final UUID agent = UUID.randomUUID();

    @Test
    void aBlockedInputPublishesAnEventNamingTheAgentAndSession() {
        List<Object> events = new ArrayList<>();
        logger.setEventPublisher(events::add);

        logger.logInputViolation(agent, "session-1", ContentGuardrailCheckResult.blocked(ContentGuardrailType.PROFANITY, "no", 90));

        assertThat(events).hasSize(1);
        GuardrailViolationEvent event = (GuardrailViolationEvent) events.get(0);
        assertThat(event.agentId()).isEqualTo(agent);
        assertThat(event.sessionId()).isEqualTo("session-1");
        assertThat(event.inputPath()).isTrue();
        assertThat(event.guardrail()).isEqualTo("PROFANITY");
        assertThat(event.reason()).isEqualTo("no");
    }

    @Test
    void aBlockedOutputIsMarkedAsTheOutputPath() {
        List<Object> events = new ArrayList<>();
        logger.setEventPublisher(events::add);

        logger.logOutputViolation(agent, "session-2", ContentGuardrailCheckResult.blocked(ContentGuardrailType.PII, "pii", 80));

        assertThat(((GuardrailViolationEvent) events.get(0)).inputPath()).isFalse();
    }

    @Test
    void aPassedCheckPublishesNothingAndWritesNoAudit() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        logger.setEventPublisher(publisher);

        logger.logInputViolation(agent, "s", ContentGuardrailCheckResult.pass());

        verify(publisher, never()).publishEvent(any(Object.class));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFailingListenerNeverBreaksTheTurn() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("listener down")).when(publisher).publishEvent(any(Object.class));
        logger.setEventPublisher(publisher);

        logger.logInputViolation(agent, "s", ContentGuardrailCheckResult.blocked(ContentGuardrailType.JAILBREAK, "j", 70));

        verify(audit).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void withoutAPublisherTheLoggerBehavesAsBefore() {
        logger.logInputViolation(agent, "s", ContentGuardrailCheckResult.blocked(ContentGuardrailType.JAILBREAK, "j", 70));

        verify(audit).record(any(), any(), any(), any(), any(), any());
    }
}
