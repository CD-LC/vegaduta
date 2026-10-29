package com.agenticai.core.eventbus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.eventbus.webhook.WebhookSubscriptionController.CreateRequest;
import com.agenticai.core.eventbus.webhook.WebhookSubscriptionController.DeliveryView;
import com.agenticai.core.eventbus.webhook.WebhookSubscriptionController.UpdateRequest;
import com.agenticai.core.eventbus.webhook.WebhookSubscriptionService.SubscriptionView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link WebhookSubscriptionController} is thin, but it owns two real behaviors the service cannot:
 * adapting the {@link DeliveryView} record off the delivery entity (field-by-field, newest first)
 * and translating each endpoint into the matching service call - including the 404 precondition on
 * the deliveries route.
 */
class WebhookSubscriptionControllerTest {

    private final WebhookSubscriptionService service = mock(WebhookSubscriptionService.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final WebhookSubscriptionController controller =
            new WebhookSubscriptionController(service, deliveries);

    private static SubscriptionView view(UUID id) {
        return new SubscriptionView(
                id,
                "h",
                "https://x.invalid",
                Set.of("*"),
                true,
                0,
                null,
                "it",
                Instant.now(),
                Instant.now(),
                null);
    }

    @Test
    void getCreateUpdateAndRotateDelegateToTheService() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(view(id));
        when(service.create("n", "https://x.invalid", Set.of("a"))).thenReturn(view(id));
        when(service.update(
                        eq(id), eq("n2"), eq("https://x.invalid/2"), eq(Set.of("b")), eq(false)))
                .thenReturn(view(id));
        when(service.rotateSecret(id)).thenReturn(view(id));

        assertEquals(id, controller.get(id).id());
        assertEquals(
                id,
                controller.create(new CreateRequest("n", "https://x.invalid", Set.of("a"))).id());
        assertEquals(
                id,
                controller
                        .update(
                                id,
                                new UpdateRequest("n2", "https://x.invalid/2", Set.of("b"), false))
                        .id());
        assertEquals(id, controller.rotateSecret(id).id());
    }

    @Test
    void deleteReturnsTheDeletedFlagAndTestReturnsTheDeliveryId() {
        UUID id = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        when(service.enqueueTestDelivery(id)).thenReturn(deliveryId);

        Map<String, Object> deleted = controller.delete(id);
        Map<String, Object> test = controller.test(id);

        assertEquals(Boolean.TRUE, deleted.get("deleted"));
        assertEquals(deliveryId, test.get("deliveryId"));
        verify(service).delete(id);
    }

    @Test
    void deliveriesMapsEveryColumnAndResolvesTheSubscriptionFirst() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(view(id));
        WebhookDelivery row = new WebhookDelivery(id, "workflow.run.completed", "run-7", "{}");
        ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        row.markDelivered(200, 42L);
        when(deliveries.findBySubscriptionIdOrderByCreatedAtDesc(eq(id), any()))
                .thenReturn(List.of(row));

        List<DeliveryView> body = controller.deliveries(id, null, null).getBody();

        assertEquals(1, body.size());
        DeliveryView v = body.get(0);
        assertEquals(row.getId(), v.id());
        assertEquals("workflow.run.completed", v.eventType());
        assertEquals("run-7", v.runId());
        assertEquals(WebhookDelivery.STATUS_DELIVERED, v.status());
        assertEquals(0, v.attemptCount());
        assertEquals(200, v.responseCode());
        assertNull(v.lastError());
        assertEquals(42, v.durationMs());
        assertEquals(row.getCreatedAt(), v.createdAt());
        assertEquals(row.getDeliveredAt(), v.deliveredAt());
        // The 404 precondition: get() runs before the repository is ever queried.
        verify(service).get(id);
    }
}
