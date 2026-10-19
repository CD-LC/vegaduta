package com.agenticai.core.eventbus;

/**
 * In-JVM fan-out of a workflow event, published by {@link
 * WorkflowEventPublisher#publish} at the MOMENT OF EMIT and deliberately
 * INDEPENDENT of Kafka, the outbox table and {@code
 * app.workflow-events.enabled}.
 *
 * <p><b>Why this exists alongside the outbox.</b> The outbox -&gt; relay -&gt;
 * Kafka path is the durable, cross-service pipe and is unchanged. But it is
 * gated on {@code app.workflow-events.enabled}, which is {@code false} in
 * production, and the relay's consumers live behind the {@code workflow}
 * profile, which is inactive both in production and in unit tests. An in-process
 * subscriber gated on any of that would be dead code the day it shipped and
 * would have no CI coverage. This signal is synchronous, works with or without a
 * broker, and is testable without one.
 *
 * <p><b>Contract for subscribers.</b> A {@code @EventListener} for this record
 * runs on the PUBLISHING thread - a webhook request thread, a document-generation
 * async thread - which already holds the correct {@code TenantContext} (it is how
 * {@code publish} resolved {@code event.tenantId()} in the first place). A
 * listener therefore must NOT hop to another thread or be {@code @Async}: doing
 * so drops that plain-{@code ThreadLocal} context and reads the empty {@code
 * public} schema. It must be cheap, bounded, and must never throw - the publisher
 * swallows exceptions to honour its own "never break the calling business flow"
 * contract, so a throwing listener is silently useless rather than loudly broken.
 *
 * @param event the same envelope that is about to be written to the outbox (when
 *     the outbox path is enabled) - identical {@code eventType}, {@code
 *     tenantId}, {@code runId}, {@code timestamp} and {@code payload}, so a
 *     subscriber and a Kafka consumer see the same facts.
 */
public record WorkflowEventSignal(WorkflowEvent event) {
}
