package com.agenticai.core.approval;

import java.util.List;
import java.util.UUID;

/**
 * Where a gated tool call came from, beyond the call itself: what the replay envelope records so
 * that, once a human has decided, the platform can CONTINUE the conversation instead of only
 * running the tool.
 *
 * @param memorySessionId the SEC-004 scoped id the originating turn reads its history under; the
 *     executor appends its outcome note there
 * @param goal the user message that started the parked turn (already length-capped by the
 *     recorder), restated to the model when the task continues
 * @param requestedAgentId the agent the conversation was started with (the A/B-routed agent that ran
 *     the tool can differ); the continuation turn is requested under it so it reads the same history
 * @param subjectKey the long-term-memory subject the turn ran under, the third input (with the
 *     tenant, agent and raw session id) of the scoped history id
 * @param runToolNames the run-scoped tool selection the parked turn was narrowed to (the playground's "this run
 *     only" switches), so the turn that continues the task is narrowed the same way; {@code null} when the turn
 *     was not narrowed (an empty list is a turn narrowed to no tools)
 *     Every field may be {@code null}: edge execution, delegated calls and rows recorded before this
 *     existed have none, and such a request is replayed but not continued.
 */
public record ToolCallOrigin(
        String memorySessionId, String goal, UUID requestedAgentId, String subjectKey, List<String> runToolNames) {

    public ToolCallOrigin {
        runToolNames = runToolNames == null ? null : List.copyOf(runToolNames);
    }

    /** An origin of a turn that was not narrowed to a tool selection. */
    public ToolCallOrigin(String memorySessionId, String goal, UUID requestedAgentId, String subjectKey) {
        this(memorySessionId, goal, requestedAgentId, subjectKey, null);
    }

    /** An origin that only names the scoped history (the pre-continuation envelope). */
    public static ToolCallOrigin ofMemorySession(String memorySessionId) {
        return new ToolCallOrigin(memorySessionId, null, null, null);
    }
}
