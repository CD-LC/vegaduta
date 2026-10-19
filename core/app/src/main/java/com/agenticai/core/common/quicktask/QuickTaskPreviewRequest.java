package com.agenticai.core.common.quicktask;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Preview request - just the message and an optional explicit agent choice,
 * no channel (see QuickTaskPreviewResponse's javadoc: checking what would
 * run shouldn't require picking a delivery channel first). {@code agentId}
 * mirrors {@link QuickTaskRequest#agentId()} - null means "show me what
 * auto-select would pick," non-null previews against that specific agent.
 */
public record QuickTaskPreviewRequest(@NotBlank String message, UUID agentId) {
}
