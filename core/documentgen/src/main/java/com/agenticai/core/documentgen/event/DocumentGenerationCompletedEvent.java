package com.agenticai.core.documentgen.event;

import java.util.UUID;

/**
 * Published by {@code DocumentGenerationEventListener} once it has finished
 * processing a {@link DocumentGenerationRequestedEvent} - {@code
 * success=true} once the file is stored and the job marked {@code COMPLETE},
 * {@code success=false} if generation threw and the job was marked {@code
 * FAILED}. No listener consumes this today; it exists as the seam a future
 * notification/webhook feature can hang off (same "publish now, wire a
 * listener later" posture {@code WorkflowEventTypes}'s channel-message
 * constants already document).
 */
public record DocumentGenerationCompletedEvent(UUID jobId, String tenantId, boolean success) {
}
