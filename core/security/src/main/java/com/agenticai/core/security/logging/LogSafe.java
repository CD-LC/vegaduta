package com.agenticai.core.security.logging;

import java.util.regex.Pattern;

/**
 * Makes a value that came from a request, a webhook or a path variable safe to put in a log line.
 *
 * <p>A logged value that contains a line break can forge a second log entry (CWE-117, log
 * injection): {@code taskId="x\n2026-01-01 INFO admin logged in"} would print as two lines. {@link
 * #clean(Object)} replaces every line break, and every other control character (ESC, NUL, ...),
 * with an underscore, and leaves everything else as it was, so a normal identifier is logged
 * unchanged.
 *
 * <p>Use it on the arguments of a log call, not on the message template:
 *
 * <pre>{@code
 * log.warn("webhook {} rejected for {}", LogSafe.clean(eventKey), LogSafe.clean(gateway));
 * }</pre>
 *
 * Do not pass a {@link Throwable} that you want a stack trace for; SLF4J treats the last argument
 * of that type specially, and this method would turn it into text.
 */
public final class LogSafe {

    /** Every control character that is left once line breaks are gone. */
    private static final Pattern OTHER_CONTROL = Pattern.compile("\\p{Cntrl}");

    private LogSafe() {}

    /**
     * The value as text with line breaks and other control characters replaced by {@code _}; never
     * null.
     */
    public static String clean(Object value) {
        String text = String.valueOf(value);
        text = text.replace("\n", "_").replace("\r", "_");
        return OTHER_CONTROL.matcher(text).replaceAll("_");
    }
}
