package com.agenticai.core.workflow.expression;

/**
 * One lint finding. {@code start}/{@code end} are character offsets into the template string that
 * was linted. Codes: PARSE_ERROR, UNKNOWN_FUNCTION, WRONG_ARGUMENT_COUNT, UNKNOWN_ROOT,
 * UNKNOWN_NODE, AMBIGUOUS_NODE, LEGACY_UNKNOWN_NAME.
 */
public record Diagnostic(Severity severity, String code, String message, int start, int end) {

    public enum Severity {
        ERROR,
        WARNING
    }
}
