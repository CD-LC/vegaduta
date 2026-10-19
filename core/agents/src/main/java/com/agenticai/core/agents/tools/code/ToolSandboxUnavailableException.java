package com.agenticai.core.agents.tools.code;

/**
 * The tool-sandbox service could not be reached, or answered with a non-2xx status. Strictly a
 * TRANSPORT/service fault - a validation or execution <em>verdict</em> ("your code doesn't
 * compile", "your program timed out") always comes back as HTTP 200 with a structured body and is
 * never this exception. Mapped to 502 in {@code GlobalExceptionHandler}, next to {@code
 * SidecarCommunicationException}, for the same reason: our server made an outbound call that
 * failed, most commonly because the service isn't running.
 */
public class ToolSandboxUnavailableException extends RuntimeException {

    public ToolSandboxUnavailableException(String message) {
        super(message);
    }

    public ToolSandboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
