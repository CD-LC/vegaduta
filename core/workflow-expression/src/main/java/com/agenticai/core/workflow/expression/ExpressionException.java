package com.agenticai.core.workflow.expression;

/**
 * The only exception the expression library lets escape. {@code start}/{@code end} are character
 * offsets into the expression source (or the template, when raised by the template engine); {@code
 * -1} means "position not known yet" and is filled in by the evaluator as the exception unwinds
 * past the node that caused it.
 */
public final class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Messages can quote user data (a bad date, a path); they end up in traces and notifications,
     * so keep them short.
     */
    static final int MAX_MESSAGE_CHARS = 200;

    private final int start;
    private final int end;

    public ExpressionException(String message, int start, int end) {
        // No stack trace: these are routine user-input errors and can be thrown in bulk by hostile
        // input.
        super(clip(message), null, false, false);
        this.start = start;
        this.end = end;
    }

    private static String clip(String message) {
        return message != null && message.length() > MAX_MESSAGE_CHARS
                ? message.substring(0, MAX_MESSAGE_CHARS) + "..."
                : message;
    }

    static ExpressionException of(String message) {
        return new ExpressionException(message, -1, -1);
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    /** Same error with positions moved by {@code base} (expression offset -> template offset). */
    ExpressionException shifted(int base) {
        return start < 0 ? this : new ExpressionException(getMessage(), start + base, end + base);
    }
}
