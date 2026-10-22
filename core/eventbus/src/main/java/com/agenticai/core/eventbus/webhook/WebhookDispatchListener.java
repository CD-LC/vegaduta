package com.agenticai.core.eventbus.webhook;

import com.agenticai.core.eventbus.WorkflowEvent;
import com.agenticai.core.eventbus.WorkflowEventSignal;
import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Tenant-webhook fan-out: listens on {@link WorkflowEventSignal}, the in-JVM publish that {@code
 * WorkflowEventPublisher} fires unconditionally for EVERY event (independent of the Kafka outbox
 * path and its feature flag), and enqueues a {@code webhook_delivery} row for each enabled
 * subscription in the event's tenant whose pattern set matches the event type.
 *
 * <p>Runs on the publishing thread - per {@link WorkflowEventSignal}'s contract it must stay cheap,
 * bounded and never throw. Cost per event is one indexed SELECT plus one INSERT per matching
 * subscription; a tenant with no subscriptions pays a single findByEnabledTrue. The slow/fragile
 * part (the actual HTTPS POST) is deliberately NOT here - it is {@link WebhookDeliveryRelay}'s job,
 * off the business thread, with retries.
 */
@Component
public class WebhookDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchListener.class);

    /** One line per tenant per minute: a streamed reply fires this per message. */
    private static final RateLimitedLog LIMITED = new RateLimitedLog(log, Duration.ofMinutes(1));

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    public WebhookDispatchListener(
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookDeliveryRepository deliveryRepository,
            ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onWorkflowEvent(WorkflowEventSignal signal) {
        try {
            WorkflowEvent event = signal.event();
            if (event == null || event.eventType() == null) {
                return;
            }
            // The signal contract says this thread holds the event's TenantContext, and the
            // repositories below route by THIS THREAD's context, not by event.tenantId(). Enforce
            // the contract (same guard as AgentEventTriggerDispatcher): a thread with no context,
            // or in another tenant, must never write a delivery row carrying this envelope.
            String threadTenant =
                    TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            if (threadTenant == null || !threadTenant.equals(event.tenantId())) {
                LIMITED.warn(
                        "tenant-mismatch:" + event.tenantId(),
                        "Webhook dispatch refused for event "
                                + event.eventType()
                                + ": event tenant '"
                                + event.tenantId()
                                + "' but this thread is in tenant "
                                + (threadTenant == null ? "(none)" : "'" + threadTenant + "'"));
                return;
            }
            for (WebhookSubscription sub : subscriptionRepository.findByEnabledTrue()) {
                if (!sub.matches(event.eventType())) {
                    continue;
                }
                deliveryRepository.save(
                        new WebhookDelivery(
                                sub.getId(),
                                event.eventType(),
                                event.runId(),
                                // Re-serialize the full envelope, not just the payload -
                                // a webhook consumer needs the same facts a Kafka
                                // consumer sees (type, tenant, run, timestamp, version).
                                objectMapper.writeValueAsString(event)));
            }
        } catch (Exception e) {
            // The publisher's own javadoc commits to never letting event
            // fan-out break the calling business flow; a listener bug must
            // degrade to a log line, not a failed inbound message.
            // Only the type and the tenant: the signal carries tenant content (the reply excerpt),
            // and the exception message can echo it, so neither is logged.
            WorkflowEvent failed = signal == null ? null : signal.event();
            LIMITED.warn(
                    "dispatch-failed:" + (failed == null ? "" : failed.tenantId()),
                    "Webhook dispatch failed for event "
                            + (failed == null ? "(none)" : failed.eventType())
                            + " in tenant "
                            + (failed == null ? "(none)" : "'" + failed.tenantId() + "'")
                            + " ("
                            + e.getClass().getSimpleName()
                            + ") - continuing");
        }
    }
}
