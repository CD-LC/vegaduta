package com.agenticai.core.approval;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only approval queries for other feature areas (keeps {@link ApprovalRequestRepository}
 * private).
 */
@Service
public class ApprovalQueryService {

    private final ApprovalRequestRepository approvalRepository;

    public ApprovalQueryService(ApprovalRequestRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    /** PENDING approval requests, newest first. */
    @Transactional(readOnly = true)
    public List<ApprovalRequest> pending() {
        return approvalRepository.findByStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING);
    }
}
