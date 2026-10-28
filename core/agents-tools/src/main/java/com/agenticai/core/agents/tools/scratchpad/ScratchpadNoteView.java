package com.agenticai.core.agents.tools.scratchpad;

import java.time.Instant;

/**
 * A note as an operator surface sees it - a plain record, deliberately not the {@link
 * ScratchpadNote} entity so REST code never touches JPA (StructureRulesTest F5). {@code scope}
 * mirrors the tool's vocabulary: {@code "agent"} for the long-term pad, {@code "session"}
 * otherwise. {@code body} is null on list views; the single-note view carries it.
 */
public record ScratchpadNoteView(
        String key, String scope, int byteSize, Instant updatedAt, String body) {

    static ScratchpadNoteView of(ScratchpadNote note, boolean withBody) {
        return new ScratchpadNoteView(
                note.getKey(),
                note.getSessionId() == null ? "agent" : "session",
                note.getByteSize(),
                note.getUpdatedAt(),
                withBody ? note.getBody() : null);
    }
}
