package com.agenticai.core.approval;

import java.util.UUID;

public class ApprovalRequestNotFoundException extends RuntimeException {

    public ApprovalRequestNotFoundException(UUID id) {
        super("No approval request found with id " + id);
    }
}
