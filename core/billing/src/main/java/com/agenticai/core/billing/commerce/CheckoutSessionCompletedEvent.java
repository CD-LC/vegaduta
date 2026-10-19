package com.agenticai.core.billing.commerce;

/**
 * In-JVM Spring application event published by {@link CheckoutSessionService#recordGatewayPayment}
 * exactly once per session, on the actual READY/EXPIRED -&gt; COMPLETED transition (never on a
 * redelivered webhook - the state-guarded UPDATE in {@code CheckoutSessionRepository#markCompleted}
 * returning 0 rows suppresses it).
 *
 * <p>Why a plain Spring event and not a direct {@code WorkflowEventPublisher#publish} call: {@code
 * core/eventbus} depends on {@code core/tenancy}, which depends on THIS module, so billing
 * importing eventbus would be a Maven cycle. The bridge onto the workflow eventbus ({@code
 * commerce.payment.received}, see {@link #WORKFLOW_EVENT_TYPE}) therefore lives in {@code
 * core/agents} ({@code
 * com.agenticai.core.agents.tools.commerce.CheckoutPaymentReceivedWorkflowEventBridge}), which
 * already depends on both. Any other in-process subscriber (a future WhatsApp "payment received,
 * order #..." auto-reply in core/omnichannel) can listen to this same event directly.
 *
 * <p>Published from the webhook handler thread, which carries NO {@code TenantContext} - listeners
 * that touch tenant-schema tables must set one from {@link CheckoutSession#tenantId()} themselves
 * (the bridge does).
 *
 * @param session the row as it stands AFTER the transition (status COMPLETED, {@code paidAt}/{@code
 *     paymentId} populated)
 */
public record CheckoutSessionCompletedEvent(CheckoutSession session) {

    /**
     * The workflow eventbus event type the core/agents bridge republishes this as. Defined here
     * (not in {@code WorkflowEventTypes}) purely because of the module-cycle above; add it to
     * {@code WorkflowEventTypes} as a documented constant when that file is next touched, and keep
     * the two strings identical.
     */
    public static final String WORKFLOW_EVENT_TYPE = "commerce.payment.received";
}
