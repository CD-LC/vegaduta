package com.agenticai.core.agents.version;

import java.util.UUID;

/**
 * No {@code agent_version} row with this id belongs to this agent - a bad id, or
 * one belonging to a different agent of the same tenant (a cross-TENANT id cannot
 * reach here at all: schema-per-tenant means the row is not in the caller's
 * schema).
 *
 * <p>Mapped to HTTP 404 by a CONTROLLER-LOCAL {@code @ExceptionHandler} on
 * {@link AgentVersionController}, not by the app-wide {@code GlobalExceptionHandler}
 * that {@code PromptVersionNotFoundException}'s own 404 comes from. Two reasons,
 * and the second is the load-bearing one:
 *
 * <ul>
 *   <li>{@code GlobalExceptionHandler} lives in {@code core/app}, outside this
 *       change's scope.
 *   <li>It declares an {@code @ExceptionHandler(Exception.class)} catch-all, and a
 *       {@code @RestControllerAdvice} resolver runs BEFORE Spring's own
 *       {@code ResponseStatusExceptionResolver}. So a new exception type that is
 *       not explicitly named there does not fall through to a sensible default -
 *       it is answered as an opaque 500 AND recorded as a PlatformIssue, which is
 *       precisely the drowning-the-Issues-portal problem that handler's own
 *       comments describe. A controller-local handler is resolved first of all and
 *       sidesteps both. {@code GuardrailRuleController} already does exactly this.
 * </ul>
 */
public class AgentVersionNotFoundException extends RuntimeException {

    public AgentVersionNotFoundException(UUID versionId, UUID agentId) {
        super("No agent config version found with id " + versionId + " for agent " + agentId);
    }

    /** Ordinal variant for the {@code .../versions/diff?from=&to=} endpoint, which addresses versions as "v3". */
    public AgentVersionNotFoundException(UUID agentId, int versionNumber) {
        super("No agent config version v" + versionNumber + " for agent " + agentId);
    }
}
