package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkflowKafkaDisabledNotice} exists to replace a wall of broker-connection warnings with
 * one explanatory line at start-up - and to escalate to WARN when {@code
 * app.workflow-events.enabled=true} is set anyway, because that combination writes to an outbox
 * nothing drains.
 */
class WorkflowKafkaDisabledNoticeTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(WorkflowKafkaDisabledNotice.class);

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void eventsDisabledLogsOnlyTheInfoAnnouncement() {
        new WorkflowKafkaDisabledNotice(false).announce();

        assertEquals(1, logs.list.size());
        assertEquals(Level.INFO, logs.list.get(0).getLevel());
        assertTrue(logs.list.get(0).getFormattedMessage().contains("Kafka is OFF"));
    }

    @Test
    void eventsEnabledAddsTheMisconfigurationWarning() {
        new WorkflowKafkaDisabledNotice(true).announce();

        assertEquals(2, logs.list.size());
        assertEquals(Level.WARN, logs.list.get(1).getLevel());
        assertTrue(logs.list.get(1).getFormattedMessage().contains("outbox"));
    }

    @Test
    void theConstructorToleratesEitherFlag() {
        assertDoesNotThrow(() -> new WorkflowKafkaDisabledNotice(false));
        assertDoesNotThrow(() -> new WorkflowKafkaDisabledNotice(true));
    }
}
