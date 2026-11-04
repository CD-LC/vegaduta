package com.agenticai.core.agents.tools.scratchpad;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A note held for review (written on a turn that handled untrusted content), as the reviewer sees it.
 * A plain record, not the entity, so REST code never touches JPA. {@code sessionId} is null for the
 * agent-long-term pad; approve and reject address a note by {@code (sessionId, key)}.
 */
public record QuarantinedNoteView(
        String key,
        String scope,
        UUID sessionId,
        String body,
        List<String> sourceTaint,
        Instant updatedAt) {

    static QuarantinedNoteView of(ScratchpadNote note) {
        return new QuarantinedNoteView(
                note.getKey(),
                note.getSessionId() == null ? "agent" : "session",
                note.getSessionId(),
                note.getBody(),
                note.getSourceTaint(),
                note.getUpdatedAt());
    }
}
