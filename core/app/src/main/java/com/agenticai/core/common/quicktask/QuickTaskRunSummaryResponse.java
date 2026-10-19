package com.agenticai.core.common.quicktask;

import java.time.Instant;

/** One row in the "recent tasks" list - just enough to pick which task id to open the full log for. */
public record QuickTaskRunSummaryResponse(
    String taskId, QuickTaskChannel channel, QuickTaskRunStatus status, String requestMessage, Instant startedAt
) {
    static QuickTaskRunSummaryResponse from(QuickTaskRun run) {
        return new QuickTaskRunSummaryResponse(run.getTaskId(), run.getChannel(), run.getStatus(), run.getRequestMessage(), run.getStartedAt());
    }
}
