package com.agenticai.core.guardrails;

/**
 * Marker wrapper for a failure in the platform machinery AROUND a tool call -
 * the approval-policy read, the procedure guard, the RIGOROUS verifier, the
 * pending-approval record write, output validation, or the output sanitizer -
 * as opposed to a failure of the tool itself ({@code delegate.call}).
 *
 * <p>{@link GuardedToolCallback} throws this (with the real exception as
 * cause) whenever one of its own checks fails. It exists so {@code
 * LoggingToolCallback} can tell "the tool broke" from "the platform broke":
 * the tool's circuit breaker must only count the former - a Postgres blip or
 * a dead verifier should never trip the breaker of a tool that never ran.
 *
 * <p>The message deliberately mirrors the cause's own message so every
 * downstream consumer ({@code TenantContextPropagatingToolCallback}'s
 * friendly-failure result, the redacted {@code tool_call_log.error} column,
 * the admin Issues portal) sees exactly the text it would have seen had the
 * cause propagated unwrapped. Not in {@code
 * RedactingToolExecutionExceptionProcessor#PROPAGATED_EXCEPTION_TYPES}: it is
 * an ordinary failure result, not a turn-level stop condition.
 */
public class ToolInfrastructureException extends RuntimeException {

    private final String toolName;

    public ToolInfrastructureException(String toolName, Throwable cause) {
        super(cause != null && cause.getMessage() != null ? cause.getMessage() : null, cause);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
