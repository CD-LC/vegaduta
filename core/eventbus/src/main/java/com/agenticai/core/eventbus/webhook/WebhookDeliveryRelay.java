package com.agenticai.core.eventbus.webhook;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The background half of tenant webhooks: polls {@code webhook_delivery} for PENDING rows whose
 * backoff has elapsed and POSTs each to its subscription's URL, HMAC-signed. Same operational shape
 * as {@code WorkflowEventOutboxRelay}: {@link TenantSchemaIterator} so the job sees every tenant
 * schema, {@link PgAdvisoryJobLock} so two replicas never double-deliver.
 *
 * <p>Delivery semantics: at-least-once. 2xx = {@code DELIVERED}; anything else retries on an
 * exponential backoff (30s doubling, capped at 30min) up to {@link #MAX_ATTEMPTS}, after which the
 * row is DEAD. Each dead row also ticks the subscription's {@code consecutive_failures} - {@link
 * WebhookSubscription#AUTO_DISABLE_FAILURE_THRESHOLD} of them and the subscription switches itself
 * off.
 *
 * <p>SSRF: the URL was guarded at save time by {@link McpRemoteUrlGuard#check}, and is re-checked
 * with {@link McpRemoteUrlGuard#checkResolved} at delivery time - a hostname whose DNS was
 * repointed at a private IP between save and send is still refused (same DNS-rebinding posture the
 * MCP client uses). The POST itself goes through a {@link SafeHttpClients} client (security review
 * SEC-027): it resolves the host once more, validates every address with the shared {@link
 * SsrfGuard} and connects only to one of those addresses, so there is no rebinding window between
 * that check and the socket.
 *
 * <p>NOT profile-gated, deliberately: subscriptions are a product feature, not the workflow-profile
 * experiment the Kafka outbox relay is. When a tenant has no pending rows this costs one indexed
 * query per schema per poll.
 */
@Component
public class WebhookDeliveryRelay {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryRelay.class);

    private static final String JOB_LOCK_NAME = "webhook-delivery-relay";
    private static final int MAX_ATTEMPTS = 10;
    private static final int BATCH_PER_TENANT = 50;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long BASE_BACKOFF_SECONDS = 30;
    private static final long MAX_BACKOFF_SECONDS = 1800;

    private final TenantSchemaIterator schemaIterator;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final EncryptionService encryptionService;
    private final McpRemoteUrlGuard urlGuard;
    private final PgAdvisoryJobLock jobLock;
    private final CloseableHttpClient httpClient;

    public WebhookDeliveryRelay(
            TenantSchemaIterator schemaIterator,
            WebhookDeliveryRepository deliveryRepository,
            WebhookSubscriptionRepository subscriptionRepository,
            EncryptionService encryptionService,
            McpRemoteUrlGuard urlGuard,
            PgAdvisoryJobLock jobLock,
            SsrfGuard ssrfGuard) {
        this.schemaIterator = schemaIterator;
        this.deliveryRepository = deliveryRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.encryptionService = encryptionService;
        this.urlGuard = urlGuard;
        this.jobLock = jobLock;
        // Address-pinned (SsrfGuard's resolver), proxy-free, and redirects off:
        // a redirect would re-sign nothing - a 3xx is a delivery failure worth surfacing.
        this.httpClient =
                SafeHttpClients.create(ssrfGuard, CONNECT_TIMEOUT, REQUEST_TIMEOUT, false);
    }

    @PreDestroy
    void close() throws java.io.IOException {
        httpClient.close();
    }

    @Scheduled(fixedDelay = 10_000L, initialDelay = 15_000L)
    public void deliverPending() {
        if (!jobLock.tryRunExclusive(JOB_LOCK_NAME, this::deliverPendingExclusively)) {
            return;
        }
    }

    private void deliverPendingExclusively() {
        schemaIterator.forEachActiveTenantSchemaSkippingIdle(
                JOB_LOCK_NAME,
                schema -> {
                    List<WebhookDelivery> due;
                    try {
                        due =
                                deliveryRepository
                                        .findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                                                WebhookDelivery.STATUS_PENDING,
                                                Instant.now(),
                                                PageRequest.of(0, BATCH_PER_TENANT));
                    } catch (InvalidDataAccessResourceUsageException e) {
                        // Boot race, seen in prod 2026-09-23 (46 ERROR lines in one
                        // sweep): this job starts 15s after context refresh and
                        // sweeps every tenant schema, while
                        // ExistingTenantSchemaMigrationRunner is still applying the
                        // migration that CREATED these tables to schemas one by
                        // one. "relation webhook_delivery does not exist" against a
                        // schema the migrator simply hasn't reached yet is not a
                        // fault - skip it quietly; the next poll sees the finished
                        // schema. Any OTHER grammar error (a real code/schema
                        // mismatch) still propagates to the iterator's ERROR log.
                        if (e.getMessage() != null && e.getMessage().contains("webhook_")) {
                            log.warn(
                                    "Skipping tenant schema '{}' - webhook tables not migrated yet"
                                            + " ({})",
                                    schema,
                                    e.getMessage());
                            return true;
                        }
                        throw e;
                    }
                    for (WebhookDelivery delivery : due) {
                        try {
                            deliver(delivery);
                        } catch (RuntimeException e) {
                            // Per-row isolation: one poisoned row must not stall the
                            // rest of the tenant's batch.
                            log.error(
                                    "Webhook delivery {} failed unexpectedly", delivery.getId(), e);
                        }
                    }
                    return !due.isEmpty();
                });
    }

    private void deliver(WebhookDelivery delivery) {
        WebhookSubscription sub =
                subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
        if (sub == null) {
            // Subscription deleted since enqueue - nothing to deliver to;
            // DELIVERED would lie, PENDING would retry forever.
            delivery.recordFailure(null, "Subscription deleted", Instant.now(), 1);
            deliveryRepository.save(delivery);
            return;
        }
        if (!sub.isEnabled()) {
            // Leave PENDING rows alone while a subscription is paused - they
            // resume when it is re-enabled, which is the least surprising
            // semantics for a temporary disable.
            return;
        }

        String urlError = urlGuard.checkResolved(sub.getUrl()).orElse(null);
        if (urlError != null) {
            failDelivery(delivery, sub, null, "SSRF guard refused delivery: " + urlError);
            return;
        }

        long started = System.currentTimeMillis();
        try {
            String secret = encryptionService.decrypt(sub.getSecretEncrypted());
            long timestamp = Instant.now().getEpochSecond();
            HttpPost request = new HttpPost(URI.create(sub.getUrl()));
            request.addHeader("User-Agent", "VegaDuta-Webhooks/1.0");
            request.addHeader("X-VegaDuta-Event", delivery.getEventType());
            request.addHeader("X-VegaDuta-Delivery", String.valueOf(delivery.getId()));
            request.addHeader(
                    WebhookSignature.HEADER,
                    WebhookSignature.sign(timestamp, secret, delivery.getPayloadJson()));
            request.setEntity(
                    new StringEntity(delivery.getPayloadJson(), ContentType.APPLICATION_JSON));

            // SEC-027: the guarded client re-resolves and re-validates the host at
            // connect time and connects only to the addresses it validated, so a
            // DNS answer that changed since checkResolved above cannot reach a
            // private address. Redirects are off: a 3xx is a delivery failure.
            int statusCode =
                    httpClient.execute(
                            request,
                            response -> {
                                EntityUtils.consume(response.getEntity());
                                return response.getCode();
                            });
            if (statusCode >= 200 && statusCode < 300) {
                delivery.markDelivered(statusCode, System.currentTimeMillis() - started);
                deliveryRepository.save(delivery);
                sub.recordDeliverySuccess();
                subscriptionRepository.save(sub);
            } else {
                failDelivery(delivery, sub, statusCode, "HTTP " + statusCode);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            failDelivery(delivery, sub, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void failDelivery(
            WebhookDelivery delivery, WebhookSubscription sub, Integer responseCode, String error) {
        delivery.recordFailure(
                responseCode, error, nextAttemptAt(delivery.getAttemptCount()), MAX_ATTEMPTS);
        deliveryRepository.save(delivery);
        if (delivery.getStatus().equals(WebhookDelivery.STATUS_DEAD)) {
            sub.recordDeliveryFailure(error);
            subscriptionRepository.save(sub);
            log.warn(
                    "Webhook delivery {} to {} dead-lettered after {} attempts: {}",
                    delivery.getId(),
                    sub.getUrl(),
                    delivery.getAttemptCount(),
                    error);
        }
    }

    /**
     * Exponential backoff for attempt n (0-indexed failure count): 30s, 60s, 120s, ... capped at
     * 30min.
     */
    private static Instant nextAttemptAt(int attemptCount) {
        long delay =
                Math.min(MAX_BACKOFF_SECONDS, BASE_BACKOFF_SECONDS << Math.min(attemptCount, 10));
        return Instant.now().plusSeconds(delay);
    }
}
