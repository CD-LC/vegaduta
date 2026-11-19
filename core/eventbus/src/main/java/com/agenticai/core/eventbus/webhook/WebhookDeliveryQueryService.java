package com.agenticai.core.eventbus.webhook;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read facade for a subscription's delivery history; maps {@link WebhookDelivery} rows to {@link DeliveryView}. */
@Service
public class WebhookDeliveryQueryService {

    public record DeliveryView(
            UUID id,
            String eventType,
            String runId,
            String status,
            int attemptCount,
            Integer responseCode,
            String lastError,
            Instant createdAt,
            Instant deliveredAt,
            Integer durationMs) {}

    private final WebhookSubscriptionService subscriptionService;
    private final WebhookDeliveryRepository deliveryRepository;

    public WebhookDeliveryQueryService(
            WebhookSubscriptionService subscriptionService,
            WebhookDeliveryRepository deliveryRepository) {
        this.subscriptionService = subscriptionService;
        this.deliveryRepository = deliveryRepository;
    }

    /** Newest first, capped at 100. Throws (404) when the subscription isn't this tenant's / doesn't exist. */
    @Transactional(readOnly = true)
    public List<DeliveryView> recentDeliveries(UUID subscriptionId) {
        subscriptionService.get(subscriptionId);
        return deliveryRepository
                .findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId, PageRequest.of(0, 100))
                .stream()
                .map(
                        d ->
                                new DeliveryView(
                                        d.getId(),
                                        d.getEventType(),
                                        d.getRunId(),
                                        d.getStatus(),
                                        d.getAttemptCount(),
                                        d.getResponseCode(),
                                        d.getLastError(),
                                        d.getCreatedAt(),
                                        d.getDeliveredAt(),
                                        d.getDurationMs()))
                .toList();
    }
}
