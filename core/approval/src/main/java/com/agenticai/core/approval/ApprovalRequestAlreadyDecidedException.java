package com.agenticai.core.approval;

import java.util.UUID;

public class ApprovalRequestAlreadyDecidedException extends RuntimeException {

    public ApprovalRequestAlreadyDecidedException(UUID id, ApprovalStatus currentStatus) {
        super("Approval request " + id + " is already " + currentStatus + " and cannot be decided again");
    }
}
