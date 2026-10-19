package com.agenticai.core.agents.turn;

import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;

/**
 * One resolved attachment for a chat turn, extracted from {@link AgentChatService}
 * (extract-only split). {@code fileName} is null for the Stage-4 stored-attachment path
 * (only the key/mime round-trip).
 */
record ChatAttachmentPayload(MimeType mimeType, Resource resource, String fileName) {
}
