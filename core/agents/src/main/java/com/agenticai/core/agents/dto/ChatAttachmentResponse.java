package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.modality.ChatAttachmentCategory;

/**
 * Returned by {@code POST /api/agents/{agentId}/chat/attachments}. {@code
 * attachmentKey} is opaque to the frontend - it's echoed back unchanged on
 * {@link ChatRequest#attachmentKey()} for whichever chat turn actually sends
 * this attachment, and resolves back to the stored bytes via {@code
 * FileStorageService.get(attachmentKey)}. No download URL is returned -
 * nothing outside this backend ever needs to fetch the raw bytes directly;
 * the frontend only needs enough to render a local preview chip (which it
 * already has, from the {@code File} object the browser gave it) and to
 * round-trip the key.
 */
public record ChatAttachmentResponse(
    String attachmentKey,
    ChatAttachmentCategory category,
    String mimeType,
    String originalFilename,
    long sizeBytes
) {
}
