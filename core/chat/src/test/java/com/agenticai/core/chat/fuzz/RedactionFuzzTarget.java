package com.agenticai.core.chat.fuzz;

import com.agenticai.core.chat.RedactingToolExecutionExceptionProcessor;
import java.nio.charset.StandardCharsets;

/**
 * Jazzer entry point for {@link
 * com.agenticai.core.chat.RedactingToolExecutionExceptionProcessor#redact}. Feeds arbitrary bytes
 * (as UTF-8) through the same regex-redaction chain a tool exception takes, so a pathological input
 * that hangs a pattern surfaces as a Jazzer timeout and a stored crash input.
 */
public final class RedactionFuzzTarget {

    private RedactionFuzzTarget() {}

    public static void fuzzerTestOneInput(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        String input = new String(data, StandardCharsets.UTF_8);
        try {
            RedactingToolExecutionExceptionProcessor.redact(input);
        } catch (RuntimeException | StackOverflowError expected) {
            // Redaction is allowed to reject input; only hangs/crashes are findings.
        }
    }
}
