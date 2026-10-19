package com.agenticai.core.guardrails;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (best effort, after the audit row is written) each time a content guardrail blocks a turn, so
 * a consumer that cares WHICH agent was struck - the audit row carries only the session id - can react
 * without polling the audit log. Purely informational: nothing in the chat path waits for, or depends on,
 * a listener.
 *
 * <p>Sessions whose id starts with {@code eval:} or {@code sim:} are quality-tooling replays (regression
 * runs and adversarial simulations); a consumer deciding whether a live agent misbehaved should ignore them.
 *
 * @param inputPath true for a blocked user message, false for a blocked agent answer
 * @param guardrail the guardrail that blocked (its type name), may be null
 * @param reason the block reason, may be null
 */
public record GuardrailViolationEvent(
    UUID agentId,
    String sessionId,
    boolean inputPath,
    String guardrail,
    String reason,
    Instant at
) {
}
