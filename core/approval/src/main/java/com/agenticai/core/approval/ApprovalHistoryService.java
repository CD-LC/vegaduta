package com.agenticai.core.approval;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read-only view of what one person decided in the approval inbox, for features that only COUNT decisions (the
 * preferences-from-behavior loop). It cannot approve, reject or change a request.
 */
@Service
public class ApprovalHistoryService {

    private final ApprovalRequestRepository repository;

    ApprovalHistoryService(ApprovalRequestRepository repository) {
        this.repository = repository;
    }

    /** Requests {@code subject} decided since {@code since}, newest first, at most 500. */
    public List<ApprovalRequest> decidedBy(String subject, Instant since) {
        return repository.findTop500ByDecidedByAndDecidedAtAfterOrderByDecidedAtDesc(subject, since);
    }
}
