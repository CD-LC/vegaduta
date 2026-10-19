package com.agenticai.core.documentgen.event;

import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import java.util.UUID;

/**
 * Published (plain {@code ApplicationEventPublisher.publishEvent}, not
 * {@code WorkflowEventPublisher}) by {@code DocumentGenerationService
 * #requestGeneration} inside its {@code @Transactional} method, and consumed
 * by {@code DocumentGenerationEventListener}'s {@code
 * @TransactionalEventListener(phase = AFTER_COMMIT)} handler - so the
 * listener only ever sees a job row that is actually durably committed.
 *
 * <p>Carries {@link #request()} in addition to {@link #jobId()}/{@link
 * #tenantId()} - the async listener needs the original {@code
 * DocumentGenerationRequest} to know what to render, and {@code
 * DocumentGenerationJob} does not persist the full request as its own
 * typed field (see that entity's javadoc for {@code contentJson}, the
 * durable fallback for this same data). This event is purely internal,
 * in-process plumbing between two classes in this module - unlike {@code
 * WorkflowEventPublisher}'s outbox-backed events, it is never serialized or
 * delivered across a process boundary, so carrying a non-trivial payload
 * here is safe and ordinary Spring practice.
 */
public record DocumentGenerationRequestedEvent(UUID jobId, String tenantId, DocumentGenerationRequest request) {
}
