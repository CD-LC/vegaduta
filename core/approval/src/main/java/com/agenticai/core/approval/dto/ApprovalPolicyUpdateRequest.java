package com.agenticai.core.approval.dto;

import jakarta.validation.constraints.NotNull;

public record ApprovalPolicyUpdateRequest(@NotNull Boolean requiresApproval) {
}
