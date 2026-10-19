package com.agenticai.core.eventbus.webhook;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-schema repository - only ever called with a TenantContext set (request thread or {@code TenantSchemaIterator}). */
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByEnabledTrue();

    List<WebhookSubscription> findAllByOrderByCreatedAtDesc();

    long countByEnabledTrue();
}
