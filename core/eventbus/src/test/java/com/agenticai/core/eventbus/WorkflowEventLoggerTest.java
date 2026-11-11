package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Phase 0's proof-of-pipe consumer: {@link WorkflowEventLogger#onEvent} must log whatever envelope
 * arrives verbatim (it is the end of the Postgres -&gt; Kafka -&gt; consumer chain) and its
 * consumer-group constant is what the Kafka dashboard reports.
 */
class WorkflowEventLoggerTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(WorkflowEventLogger.class);

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
    void onEventLogsTheEnvelopeVerbatim() {
        new WorkflowEventLogger().onEvent("{\"eventType\":\"x\"}");

        assertEquals(1, logs.list.size());
        assertTrue(logs.list.get(0).getFormattedMessage().contains("{\"eventType\":\"x\"}"));
    }

    @Test
    void theConsumerGroupIsTheDocumentedIdAndNullEnvelopesDoNotThrow() {
        assertEquals("workflow-event-logger", WorkflowEventLogger.CONSUMER_GROUP);
        assertDoesNotThrow(() -> new WorkflowEventLogger().onEvent(null));
    }
}
