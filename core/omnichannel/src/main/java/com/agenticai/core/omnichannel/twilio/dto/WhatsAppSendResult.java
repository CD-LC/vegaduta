package com.agenticai.core.omnichannel.twilio.dto;

import java.util.UUID;

/**
 * {@code approved=true} means the message was sent immediately (auto-
 * approved by current policy). {@code approved=false} means it's sitting in
 * the approval queue - {@code pendingApprovalId} is what
 * {@code POST /api/approvals/{id}/approve} needs to actually send it.
 */
public record WhatsAppSendResult(boolean approved, UUID pendingApprovalId) {
}
