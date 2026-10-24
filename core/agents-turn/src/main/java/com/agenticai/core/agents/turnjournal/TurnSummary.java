package com.agenticai.core.agents.turnjournal;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the list of a session's journaled turns: enough to pick the turn to open ({@code turnId}) without
 * reading its events. {@code status} is the status of the turn's last COMMIT event ({@code completed}, {@code
 * cancelled}, {@code error:...}, ...), or {@code open} while the turn has not committed (still running, or the
 * process ended before it could).
 */
public record TurnSummary(
        UUID turnId,
        UUID agentId,
        Instant startedAt,
        Instant lastEventAt,
        int events,
        String status) {

    /** The status a turn has until it commits. */
    public static final String OPEN = "open";

    /** Reads {@code status=...} out of a COMMIT event's summary; {@link #OPEN} when there is none. */
    public static String statusOf(String commitSummary) {
        if (commitSummary == null) {
            return OPEN;
        }
        int start = commitSummary.indexOf("status=");
        if (start < 0) {
            return OPEN;
        }
        start += "status=".length();
        int end = commitSummary.indexOf(' ', start);
        return end < 0 ? commitSummary.substring(start) : commitSummary.substring(start, end);
    }
}
