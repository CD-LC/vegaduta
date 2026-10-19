package com.agenticai.core.agents.version;

/**
 * The body of {@code POST /api/agents/{id}/versions} (C1 publish).
 *
 * @param version the version the draft now corresponds to - the newly
 *     appended row, or the existing newest row when the publish was a
 *     byte-identical no-op.
 * @param createdNew false on a hash-deduped publish - "you asked for a
 *     version of exactly the configuration already stored". True only when
 *     a new row exists, so the console can say "Nothing changed - still v7"
 *     honestly instead of implying a snapshot was written.
 */
public record AgentPublishResponse(AgentVersionResponse version, boolean createdNew) {
}
