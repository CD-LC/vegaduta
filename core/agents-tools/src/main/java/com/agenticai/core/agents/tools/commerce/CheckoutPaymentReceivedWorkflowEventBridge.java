package com.agenticai.core.agents.tools.commerce;

import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionCompletedEvent;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Re-publishes core/billing's in-JVM {@link CheckoutSessionCompletedEvent} onto the workflow
 * eventbus as {@code commerce.payment.received} ({@link
 * CheckoutSessionCompletedEvent#WORKFLOW_EVENT_TYPE}), so a workflow's event trigger ({@code
 * AgentEventTriggerDispatcher}, an {@code @EventListener} on {@code WorkflowEventSignal} in this
 * same module) can react to "the customer paid" - e.g. hand the conversation back to the agent to
 * confirm the order, or run a fulfilment workflow.
 *
 * <p>Lives HERE, not in core/billing, for the same reason that module's event record documents:
 * {@code core/eventbus -> tenancy -> billing}, so billing cannot import {@link
 * WorkflowEventPublisher} without a Maven cycle. core/agents already depends on both (billing
 * transitively via tenancy; eventbus directly).
 *
 * <p><b>Tenant context is set explicitly around the publish.</b> The event is raised on the gateway
 * webhook thread, which has no {@code TenantContext} (the webhook is unauthenticated by nature),
 * while {@link WorkflowEventPublisher#publish} reads {@code TenantContext.current()} for the
 * envelope's {@code tenantId} AND its outbox row lands in a tenant-schema table ({@code
 * workflow_event_outbox}, tenant/V19) via the schema-per-tenant resolver. Without this, the event
 * would be stamped with the public schema and either land in the wrong outbox or be refused
 * outright under {@code app.tenancy.fail-on-missing-context}. The row's own {@code tenantId} (the
 * schema name) is exactly what the context needs; the previous context - normally none - is
 * restored afterwards, the same capture-and-re-set discipline {@code
 * TenantContextPropagatingToolCallback} documents.
 *
 * <p>Best-effort by the same contract as every eventbus caller: a failure here is logged and
 * swallowed - the checkout session is already COMPLETED in the database and must stay that way
 * regardless of what any listener does.
 */
@Component
public class CheckoutPaymentReceivedWorkflowEventBridge {

    private static final Logger log =
            LoggerFactory.getLogger(CheckoutPaymentReceivedWorkflowEventBridge.class);

    /**
     * The {@code subject} stamped on the synthetic context - a label, never an authenticated
     * principal.
     */
    static final String SYSTEM_SUBJECT = "system:billing-webhook";

    private final WorkflowEventPublisher workflowEventPublisher;

    public CheckoutPaymentReceivedWorkflowEventBridge(
            WorkflowEventPublisher workflowEventPublisher) {
        this.workflowEventPublisher = workflowEventPublisher;
    }

    @EventListener
    public void onCheckoutSessionCompleted(CheckoutSessionCompletedEvent event) {
        CheckoutSession session = event.session();
        if (session == null || session.tenantId() == null || session.tenantId().isBlank()) {
            log.warn(
                    "CheckoutSessionCompletedEvent without a tenant id - not bridged to the workflow eventbus");
            return;
        }
        TenantContext previous = TenantContext.current().orElse(null);
        TenantContext.set(TenantContext.of(session.tenantId(), SYSTEM_SUBJECT, Set.of()));
        try {
            String runId =
                    workflowEventPublisher.publishNewRun(
                            CheckoutSessionCompletedEvent.WORKFLOW_EVENT_TYPE, payloadFor(session));
            log.info(
                    "COMMERCE_PAYMENT_RECEIVED_EVENT checkoutSession={} tenant={} amountPaise={} run={}",
                    session.id(),
                    session.tenantId(),
                    session.amountPaise(),
                    runId);
        } catch (RuntimeException e) {
            log.error(
                    "Failed to publish {} for checkout_session {} (tenant {}) - the session IS completed; "
                            + "no workflow trigger fired",
                    CheckoutSessionCompletedEvent.WORKFLOW_EVENT_TYPE,
                    session.id(),
                    session.tenantId(),
                    e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * Flat, JSON-friendly, and deliberately complete: everything a workflow node needs to confirm
     * an order or message the customer without a second lookup. Nulls are omitted so a template
     * referencing {@code payload.customerPhone} sees "absent", not the string "null".
     */
    static Map<String, Object> payloadFor(CheckoutSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checkoutSessionId", session.id().toString());
        payload.put("tenantId", session.tenantId());
        putIfPresent(
                payload,
                "agentId",
                session.agentId() == null ? null : session.agentId().toString());
        putIfPresent(payload, "sessionId", session.sessionId());
        payload.put("channel", session.channel());
        payload.put("status", session.status().wireValue());
        payload.put("currency", session.currency());
        payload.put("amountPaise", session.amountPaise());
        payload.put("amountInr", session.amountMajor().toPlainString());
        payload.put("description", session.description());
        putIfPresent(payload, "customerPhone", session.customerPhone());
        putIfPresent(payload, "customerName", session.customerName());
        payload.put("paymentProvider", session.paymentProvider());
        putIfPresent(payload, "gatewayRefId", session.gatewayRefId());
        putIfPresent(payload, "paymentId", session.paymentId());
        putIfPresent(
                payload, "paidAt", session.paidAt() == null ? null : session.paidAt().toString());
        return payload;
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
