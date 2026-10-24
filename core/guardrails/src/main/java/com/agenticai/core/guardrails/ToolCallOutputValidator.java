package com.agenticai.core.guardrails;

/**
 * The marker for a tool result that failed a structural check.
 *
 * <p>2026-10-23: the checks this class used to run on every tool result are gone, because both
 * turned a result the tool had already produced into nothing. Output over the length bound is now
 * TRUNCATED to {@link ToolOutputProperties} (with a marker that says how much was cut), and output
 * that merely starts with {@code [} or {@code {} is no longer required to parse as JSON: a log line,
 * a numbered list or a half-written object is still something the model can read, and the
 * provider-side JSON wrapping ({@code McpToolOutputSanitizer.ensureJsonObject}) already makes any
 * text safe to replay. Withholding the result left the model with "try a smaller request" for a call
 * whose side effect had happened.
 *
 * <p>Nothing in this module produces {@link #VALIDATION_FAILURE_PREFIX} now. It stays because {@code
 * LoggingToolCallback} still recognises it (to flag {@code ToolCallLog.validationFailed} and strip it
 * before the text is shown or stored), so rows written before the change and any future structural
 * check keep working without a second marker.
 */
public final class ToolCallOutputValidator {

    /**
     * Prefix a guard puts on a synthetic "this output failed validation" tool result, so {@code
     * LoggingToolCallback} can detect the failure and strip the marker before it is persisted or
     * re-shown: the model and anyone reading the log see a clean sentence, never this internal tag.
     */
    public static final String VALIDATION_FAILURE_PREFIX = "[TOOL_OUTPUT_VALIDATION_FAILED] ";

    private ToolCallOutputValidator() {
    }
}
