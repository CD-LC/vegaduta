package com.agenticai.core.approval;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The one way an {@link ApprovalActionExecutor} of ANOTHER feature area leaves the result of what it did
 * on the request it ran for: stores the text on the request, saves it, and announces that the outcome is
 * durable ({@link ApprovalRequestExecutionRecordedEvent}) so a parked job or conversation resumes at once.
 *
 * <p>It exists so those executors depend on the approval area's service, not on its repository (the
 * structure rules forbid one feature area reaching into another's tables).
 */
@Component
public class ApprovalExecutionResultWriter {

    private final ApprovalRequestRepository requestRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ApprovalExecutionResultWriter(
        ApprovalRequestRepository requestRepository, ApplicationEventPublisher eventPublisher
    ) {
        this.requestRepository = requestRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Records {@code result} as the request's execution result, saves the request and announces it. */
    public void record(ApprovalRequest request, String result) {
        request.recordExecutionResult(result);
        requestRepository.save(request);
        eventPublisher.publishEvent(
            new ApprovalRequestExecutionRecordedEvent(this, request.getId(), request.getRequestedBy()));
    }
}
