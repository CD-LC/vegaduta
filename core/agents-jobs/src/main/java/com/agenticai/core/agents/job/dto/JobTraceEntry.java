package com.agenticai.core.agents.job.dto;

import java.time.Instant;

/**
 * One event on a job's merged timeline - a progress note or a tool call,
 * flattened into a single shape so a client renders ONE chronological list
 * rather than interleaving two independently-paged streams itself.
 *
 * <p>The fields are deliberately generic ({@code label}, {@code detail}) rather
 * than a union of every column both sources have. A trace view's job is to
 * answer "what happened, in what order, and did anything go wrong"; the full
 * record of a tool call already has a home ({@code GET
 * /api/agents/{id}/tool-calls}) and duplicating its shape here would create two
 * contracts to keep in sync for one screen.
 *
 * @param type          which source this came from
 * @param at            when it happened - {@code created_at} for a note,
 *                      {@code started_at} for a tool call. Both are the moment
 *                      the event BEGAN, which is what makes merging them by this
 *                      field produce a truthful order.
 * @param label         the short name: the note text, or the tool name
 * @param detail        supporting text: null for a note (the label IS the note),
 *                      the tool's input for a tool call
 * @param errorMessage  non-null only for a tool call that failed. Kept as its own
 *                      field rather than folded into {@code detail} so a client can
 *                      style failures without string-matching.
 * @param durationMs    non-null only for a tool call. A progress note is an
 *                      instant, not an interval - and reporting 0 rather than null
 *                      would render as a suspiciously fast operation.
 */
public record JobTraceEntry(
    JobTraceEntryType type,
    Instant at,
    String label,
    String detail,
    String errorMessage,
    Long durationMs
) {

    public static JobTraceEntry progress(Instant at, String note) {
        return new JobTraceEntry(JobTraceEntryType.PROGRESS, at, note, null, null, null);
    }

    public static JobTraceEntry toolCall(Instant at, String toolName, String input, String errorMessage, Long durationMs) {
        return new JobTraceEntry(JobTraceEntryType.TOOL_CALL, at, toolName, input, errorMessage, durationMs);
    }
}
