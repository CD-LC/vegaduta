package com.agenticai.core.agents.job.dto;

import com.agenticai.core.agents.job.progress.AgentJobProgressNote;
import com.agenticai.core.agents.job.progress.ProgressSource;
import java.time.Instant;
import java.util.UUID;

/**
 * One progress note, as the API returns it.
 *
 * <p>{@code source} is exposed rather than hidden as an internal detail: the
 * client needs to render a model's CLAIM about its own work differently from the
 * platform's OBSERVATION of it (see {@link ProgressSource}), and a UI cannot make
 * that distinction if the API flattens it away.
 *
 * <p>{@code createdAt} is what a polling client sends back as the {@code
 * ?after=} cursor, which is why the value is passed through unrounded.
 */
public record AgentJobProgressNoteView(
    UUID id,
    String note,
    Integer percent,
    ProgressSource source,
    Instant createdAt
) {

    /** {@code Short} widened to {@code Integer} so JSON clients see a plain number, null preserved. */
    public static AgentJobProgressNoteView from(AgentJobProgressNote note) {
        return new AgentJobProgressNoteView(
            note.getId(),
            note.getNote(),
            note.getPercent() == null ? null : note.getPercent().intValue(),
            note.getSource(),
            note.getCreatedAt()
        );
    }
}
