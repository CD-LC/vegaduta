package com.agenticai.core.eventbus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.eventbus.WorkflowEvent;
import com.agenticai.core.eventbus.WorkflowEventSignal;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The listener's repositories route by the THREAD's tenant, so it must refuse an event whose tenant
 * differs from the thread's (or when the thread has none), and its failure logging must carry no
 * tenant content.
 */
class WebhookDispatchListenerTenantGuardTest {

    private static final String SECRET_CONTENT = "the customer's private reply text";

    private final WebhookSubscriptionRepository subscriptions =
            mock(WebhookSubscriptionRepository.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final WebhookDispatchListener listener =
            new WebhookDispatchListener(
                    subscriptions,
                    deliveries,
                    new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(WebhookDispatchListener.class);

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        TenantContext.clear();
    }

    private static WorkflowEventSignal signalFor(String tenant) {
        return (new WorkflowEventSignal(
                WorkflowEvent.of(
                        "agent.message.completed",
                        tenant,
                        "run-1",
                        Map.of("answerExcerpt", SECRET_CONTENT))));
    }

    private WebhookSubscription matchingSubscription() {
        return new WebhookSubscription("h", "https://x.invalid", "s", Set.of("*"), "it");
    }

    @Test
    void aThreadInAnotherTenantNeverWritesADeliveryForTheEvent() {
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of(matchingSubscription()));
        TenantContext.set(TenantContext.of("tenant_b", "u", Set.of()));

        listener.onWorkflowEvent(signalFor("tenant_victim"));

        verify(subscriptions, never()).findByEnabledTrue();
        verify(deliveries, never()).save(any());
        assertFalse(logs.list.isEmpty(), "the refusal is logged");
        assertTrue(
                logs.list.stream()
                        .noneMatch(e -> e.getFormattedMessage().contains(SECRET_CONTENT)));
    }

    @Test
    void aThreadWithNoTenantNeverWritesADelivery() {
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of(matchingSubscription()));

        listener.onWorkflowEvent(signalFor("tenant_a"));

        verify(deliveries, never()).save(any());
    }

    @Test
    void theMatchingTenantStillWritesTheDelivery() {
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of(matchingSubscription()));
        TenantContext.set(TenantContext.of("tenant_a", "u", Set.of()));

        listener.onWorkflowEvent(signalFor("tenant_a"));

        verify(deliveries).save(any());
    }

    @Test
    void aFailureLogsOnlyTypeAndTenantAtWarnWithoutPayloadOrStackTrace() {
        when(subscriptions.findByEnabledTrue())
                .thenThrow(new IllegalStateException("echoes " + SECRET_CONTENT));
        TenantContext.set(TenantContext.of("tenant_log", "u", Set.of()));

        listener.onWorkflowEvent(signalFor("tenant_log"));
        listener.onWorkflowEvent(signalFor("tenant_log"));

        assertEquals(1, logs.list.size(), "rate limited: one line for the repeated failure");
        ILoggingEvent line = logs.list.get(0);
        assertEquals(Level.WARN, line.getLevel());
        assertTrue(line.getFormattedMessage().contains("agent.message.completed"));
        assertTrue(line.getFormattedMessage().contains("tenant_log"));
        assertFalse(line.getFormattedMessage().contains(SECRET_CONTENT));
        assertEquals(null, line.getThrowableProxy(), "no stack trace");
    }
}
