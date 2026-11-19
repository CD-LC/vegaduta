package com.agenticai.core.eventbus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Outbox backlog counts for other feature areas (system status). */
@Service
public class WorkflowEventOutboxStatsService {

    private final WorkflowEventOutboxRepository outboxRepository;

    public WorkflowEventOutboxStatsService(WorkflowEventOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return outboxRepository.countByPublishedAtIsNullAndRetryCountLessThan(
                WorkflowEventOutboxRelay.MAX_RETRIES);
    }

    @Transactional(readOnly = true)
    public long deadLetteredCount() {
        return outboxRepository.countByPublishedAtIsNullAndRetryCountGreaterThanEqual(
                WorkflowEventOutboxRelay.MAX_RETRIES);
    }
}
