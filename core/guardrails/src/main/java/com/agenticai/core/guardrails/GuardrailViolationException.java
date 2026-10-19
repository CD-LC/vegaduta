package com.agenticai.core.guardrails;

/**
 * A tool's output tripped a hard-block guardrail (e.g. an embedded
 * prompt-injection marker attempting to redirect the agent) - the output is
 * withheld from the model entirely rather than passed through, redacted or
 * not. Distinct from {@link ToolStepLimitExceededException}: this is a
 * content-safety stop, not a runaway-loop stop.
 */
public class GuardrailViolationException extends RuntimeException {

    private final String toolName;

    public GuardrailViolationException(String toolName, String reason) {
        super("Guardrail blocked output from tool '" + toolName + "': " + reason);
        this.toolName = toolName;
    }

    /**
     * The tool whose output tripped the guardrail - kept as its own field
     * (rather than re-parsed out of {@link #getMessage()}) so {@code
     * DiagnosticService} (core/app) can key a {@code PlatformIssue}'s {@code
     * sourceId} on it directly. See {@code
     * com.agenticai.core.audit.IssueSource#GUARDRAIL_FALSE_POSITIVE}'s javadoc.
     */
    public String getToolName() {
        return toolName;
    }
}
