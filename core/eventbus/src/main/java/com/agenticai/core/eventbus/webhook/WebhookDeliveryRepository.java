package com.agenticai.core.eventbus.webhook;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-schema repository - only ever called with a TenantContext set (request thread or {@code TenantSchemaIterator}). */
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /** The relay's poll: pending rows whose backoff has elapsed, oldest first, capped by the caller's Pageable. */
    List<WebhookDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
        String status, Instant now, Pageable pageable);

    List<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);
}
