package com.agenticai.core.agents.job;

import java.util.Optional;
import java.util.UUID;

/**
 * The one place that knows how a job id becomes a chat {@code sessionId}, and
 * back.
 *
 * <p><b>Why this exists at all.</b> Everything downstream of a chat turn already
 * keys its attribution on {@code sessionId}: {@code tool_call_log.session_id},
 * {@code llm_call_log}, the Langfuse trace's session id, {@code rl_episode}.
 * By deriving a job's session id deterministically from its own primary key,
 * every one of those surfaces becomes queryable per job with ZERO new plumbing -
 * no new correlation column, no join table, no change to
 * {@code AgentChatService}, {@code LoggingToolCallback} or {@code
 * GuardedToolCallback}. The observability PR's whole trace endpoint is a reuse
 * of {@code ToolCallLogService.findByAgentIdAndSessionId} with the string this
 * class builds.
 *
 * <p>{@code "job:" + uuid} is 40 characters, comfortably inside {@code
 * tool_call_log.session_id}'s {@code VARCHAR(128)}.
 *
 * <p><b>A client may never supply a job's session id.</b> Two jobs sharing a
 * session would cross-correlate each other's tool logs, and (once the
 * approval-parking PR lands) each other's approval blockers. The id is derived,
 * never accepted.
 */
public final class AgentJobSession {

    /** Namespace marker distinguishing a job session from a console/omnichannel one. */
    public static final String PREFIX = "job:";

    private AgentJobSession() {
        // Static helpers only.
    }

    /** The chat {@code sessionId} every turn of {@code jobId} runs under. */
    public static String sessionIdFor(UUID jobId) {
        return PREFIX + jobId;
    }

    /**
     * Inverse of {@link #sessionIdFor}. Null-safe and total: anything that is
     * not a well-formed job session (a console session id, a channel session
     * id, {@code null}, a malformed UUID after the prefix) returns empty rather
     * than throwing, because callers use this to ASK whether an arbitrary
     * session belongs to a job.
     */
    public static Optional<UUID> jobIdOf(String sessionId) {
        if (!isJobSession(sessionId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(sessionId.substring(PREFIX.length())));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /** Cheap prefix test; does not validate the UUID (see {@link #jobIdOf} for that). */
    public static boolean isJobSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(PREFIX);
    }

    /**
     * The {@code requestedBy} label a tool call made during this job is
     * attributed to in the approval/guardrail layer.
     *
     * <p><b>This is a stringly-typed contract with a hot-path file this
     * workstream deliberately does not edit.</b> {@code GuardedToolCallback}
     * builds the very same string itself ({@code "agent:" + agentId +
     * ":session:" + sessionId}) when it records an approval request, and we
     * need to be able to find those rows for a given job. Rather than reach
     * into that file, this method reproduces the format and its unit test
     * asserts the literal - so if the two ever drift apart, the build fails
     * here instead of an operator discovering it in production as approvals
     * that cannot be matched back to their job.
     */
    public static String requestedByLabel(UUID agentId, UUID jobId) {
        return "agent:" + agentId + ":session:" + sessionIdFor(jobId);
    }
}
