package com.agenticai.core.eventbus.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.ArgumentCaptor;

/**
 * The happy-path half of {@link WebhookDispatchListener} (the tenant-mismatch refusal lives in
 * {@code WebhookDispatchListenerTenantGuardTest}): an event in the thread's own tenant enqueues one
 * {@code webhook_delivery} per enabled subscription whose pattern matches, re-serializing the full
 * envelope as the payload - and nothing, not even a null signal, may throw back into the caller.
 */
class WebhookDispatchListenerTest {

    private final WebhookSubscriptionRepository subscriptions =
            mock(WebhookSubscriptionRepository.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final WebhookDispatchListener listener =
            new WebhookDispatchListener(
                    subscriptions,
                    deliveries,
                    new ObjectMapper().registerModule(new JavaTimeModule()));

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant_a", "u", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static WorkflowEventSignal signal(String eventType) {
        return new WorkflowEventSignal(
                WorkflowEvent.of(eventType, "tenant_a", "run-1", Map.of("k", "v")));
    }

    @Test
    void onlyMatchingSubscriptionsGetADelivery() {
        WebhookSubscription all =
                new WebhookSubscription("a", "https://x.invalid/1", "e", Set.of("*"), "it");
        WebhookSubscription runs =
                new WebhookSubscription(
                        "b", "https://x.invalid/2", "e", Set.of("workflow.run.*"), "it");
        WebhookSubscription other =
                new WebhookSubscription(
                        "c", "https://x.invalid/3", "e", Set.of("approval.requested"), "it");
        WebhookSubscription off =
                new WebhookSubscription("d", "https://x.invalid/4", "e", Set.of("*"), "it");
        off.update("d", "https://x.invalid/4", Set.of("*"), false);
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of(all, runs, other, off));

        listener.onWorkflowEvent(signal("workflow.run.completed"));

        ArgumentCaptor<WebhookDelivery> saved = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveries, times(2)).save(saved.capture());
        List<WebhookDelivery> rows = saved.getAllValues();
        assertEquals("workflow.run.completed", rows.get(0).getEventType());
        assertEquals("run-1", rows.get(0).getRunId());
        // The full envelope is re-serialized: a consumer sees the same facts a Kafka consumer does.
        assertTrue(
                rows.get(0).getPayloadJson().contains("\"eventType\":\"workflow.run.completed\""));
        assertTrue(rows.get(0).getPayloadJson().contains("\"tenantId\":\"tenant_a\""));
    }

    @Test
    void anEventWithNoTypeOrNoSubscriptionsEnqueuesNothing() {
        listener.onWorkflowEvent(signal(null));
        verify(subscriptions, never()).findByEnabledTrue();

        when(subscriptions.findByEnabledTrue()).thenReturn(List.of());
        listener.onWorkflowEvent(signal("workflow.run.failed"));
        verify(deliveries, never()).save(any());
    }

    @Test
    void aNullSignalDegradesToNothingRatherThanThrowing() {
        // The publisher's contract is "fan-out never breaks the business flow" - even a malformed
        // signal must cost one log line, not an exception.
        assertDoesNotThrow(() -> listener.onWorkflowEvent(null));
        verify(deliveries, never()).save(any());
    }
}
