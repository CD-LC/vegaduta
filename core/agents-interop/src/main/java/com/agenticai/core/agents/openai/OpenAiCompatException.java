package com.agenticai.core.agents.openai;

/** A failure with an explicit OpenAI-shaped status/type/code, thrown by the compat controller itself. */
public class OpenAiCompatException extends RuntimeException {

    private final int status;
    private final String type;
    private final String code;
    private final long retryAfterSeconds;

    public OpenAiCompatException(int status, String type, String code, String message) {
        this(status, type, code, message, 0L);
    }

    public OpenAiCompatException(int status, String type, String code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }
    public String type() { return type; }
    public String code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
