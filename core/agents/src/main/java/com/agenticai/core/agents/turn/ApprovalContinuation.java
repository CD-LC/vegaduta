package com.agenticai.core.agents.turn;

import java.util.List;
import java.util.UUID;

/**
 * What the platform knows about a human decision on a gated tool call, in the form the turn that
 * continues the original task needs. Built once by the approval-continuation builder from the
 * approval request's replay envelope and the call's outcome, then used by BOTH continuation paths:
 * a chat session's server-started turn and an agent job's resumed turn.
 *
 * <p>It rides on {@link ChatTurnRequest#continuation()} and {@link TurnContext#continuation()}.
 * The turn pipeline reads from it only what must not be derived from the continuation message
 * itself: the {@link #goal()} (what input screening, tool selection, cost routing and memory recall
 * are decided on - a platform-written message full of tool output is none of those) and the {@link
 * #stickyTools()} (tools the conversation already used, kept on offer).
 *
 * @param requestId the approval request this decision belongs to
 * @param outcome how the gated call ended
 * @param toolName the gated tool
 * @param argumentsJson the exact arguments the model asked for (for display; the gate replays the
 *     recorded ones)
 * @param result the tool's output ({@link Outcome#COMPLETED}), the failure message ({@link
 *     Outcome#FAILED}) or the reviewer's reason ({@link Outcome#DENIED}); may be {@code null}
 * @param decidedBy who decided
 * @param goal the user message that started the parked turn, restated to the model
 * @param requestedAgentId the agent the conversation was started with (the A/B-routed agent can
 *     differ); the continuation turn is requested under THIS id so it reads the same history
 * @param sessionId the raw client session id
 * @param subjectKey the long-term-memory subject the parked turn ran under
 * @param tenantId the tenant the request belongs to
 * @param subject the authenticated subject the parked turn ran as (the tenant context's subject);
 *     the continuation runs as the same person, never as the approver
 * @param memorySessionId the SEC-004 scoped history id the parked turn used (a consistency check:
 *     the continuation must derive the same one)
 * @param stickyTools tool names the conversation already used, offered again whatever the goal says
 * @param otherPending descriptions of other actions of the same conversation that still wait for a
 *     human, so the model does not ask for them again
 * @param runToolNames the run-scoped tool selection the parked turn was narrowed to ({@link
 *     ChatTurnRequest#toolNames()}), so the continuation is narrowed the same way; {@code null} when
 *     the parked turn was not narrowed
 */
public record ApprovalContinuation(
        UUID requestId,
        Outcome outcome,
        String toolName,
        String argumentsJson,
        String result,
        String decidedBy,
        String goal,
        UUID requestedAgentId,
        String sessionId,
        String subjectKey,
        String tenantId,
        String subject,
        String memorySessionId,
        List<String> stickyTools,
        List<String> otherPending,
        List<String> runToolNames) {

    /** A continuation of a turn that was not narrowed to a tool selection. */
    public ApprovalContinuation(
            UUID requestId,
            Outcome outcome,
            String toolName,
            String argumentsJson,
            String result,
            String decidedBy,
            String goal,
            UUID requestedAgentId,
            String sessionId,
            String subjectKey,
            String tenantId,
            String subject,
            String memorySessionId,
            List<String> stickyTools,
            List<String> otherPending) {
        this(
                requestId,
                outcome,
                toolName,
                argumentsJson,
                result,
                decidedBy,
                goal,
                requestedAgentId,
                sessionId,
                subjectKey,
                tenantId,
                subject,
                memorySessionId,
                stickyTools,
                otherPending,
                null);
    }

    /** How the gated call ended. */
    public enum Outcome {
        /** Approved, replayed, and the tool returned. */
        COMPLETED,
        /** Approved, but the replay failed (tool error, tool gone, tool changed). */
        FAILED,
        /** A human said no; the tool never ran. */
        DENIED
    }

    /**
     * The tag every continuation message opens with. The stored conversation keeps the message as
     * an ordinary user turn, so this is how a reader (the web transcript) tells a platform-written
     * continuation from something the person typed; the tag carries the request id, tool and
     * outcome as attributes.
     */
    public static final String MESSAGE_MARKER = "<approval_result";

    public ApprovalContinuation {
        stickyTools = stickyTools == null ? List.of() : List.copyOf(stickyTools);
        otherPending = otherPending == null ? List.of() : List.copyOf(otherPending);
        runToolNames = runToolNames == null ? null : List.copyOf(runToolNames);
    }

    /**
     * The same decision with another goal text. An agent job restates the job's own goal (which the
     * output contract may have extended with its schema instructions) rather than the copy of it
     * the replay envelope keeps, which is cut at {@code app.guardrails.approval-park.max-goal-chars}.
     */
    public ApprovalContinuation withGoal(String newGoal) {
        return new ApprovalContinuation(
                requestId,
                outcome,
                toolName,
                argumentsJson,
                result,
                decidedBy,
                newGoal,
                requestedAgentId,
                sessionId,
                subjectKey,
                tenantId,
                subject,
                memorySessionId,
                stickyTools,
                otherPending,
                runToolNames);
    }

    /** True when {@code message} is a stored continuation message (see {@link #MESSAGE_MARKER}). */
    public static boolean isContinuationMessage(String message) {
        return message != null && message.startsWith(MESSAGE_MARKER);
    }
}
