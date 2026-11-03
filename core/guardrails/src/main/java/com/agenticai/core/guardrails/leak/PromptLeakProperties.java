package com.agenticai.core.guardrails.leak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.guardrails.prompt-leak.*}: the output-side system-prompt leak control (OWASP LLM07).
 * Defaults mean "off": {@code mode=off}, {@code action=warn}. Even {@code warn} mode changes the system prompt (it appends a
 * canary block, which defeats provider prompt-prefix caching), so it is opt-in.
 */
@ConfigurationProperties(prefix = "app.guardrails.prompt-leak")
public class PromptLeakProperties {

    /** Minimum canary entropy, in bytes (128 bits). Smaller configured values are raised to this. */
    public static final int MIN_CANARY_BYTES = 16;

    private String mode = "off";
    private String action = "warn";
    private int minChars = 60;
    private int canaryBytes = 18;
    private int streamHoldbackChars = 256;
    private String redactionMarker = "[redacted]";
    private String blockMessage = "[This reply was withheld because it contained confidential instructions.]";

    public PromptLeakMode mode() {
        return PromptLeakMode.parse(mode);
    }

    public PromptLeakAction action() {
        return PromptLeakAction.parse(action);
    }

    /** Letters and digits (after normalisation) a verbatim overlap must span to count; floor 24. */
    public int minChars() {
        return Math.max(24, minChars);
    }

    public int canaryBytes() {
        return Math.max(MIN_CANARY_BYTES, canaryBytes);
    }

    /** Raw characters a stream keeps back so a leak split across chunks is seen before it is sent; floor 128. */
    public int streamHoldbackChars() {
        return Math.max(128, streamHoldbackChars);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public int getMinChars() {
        return minChars;
    }

    public void setMinChars(int minChars) {
        this.minChars = minChars;
    }

    public int getCanaryBytes() {
        return canaryBytes;
    }

    public void setCanaryBytes(int canaryBytes) {
        this.canaryBytes = canaryBytes;
    }

    public int getStreamHoldbackChars() {
        return streamHoldbackChars;
    }

    public void setStreamHoldbackChars(int streamHoldbackChars) {
        this.streamHoldbackChars = streamHoldbackChars;
    }

    public String getRedactionMarker() {
        return redactionMarker;
    }

    public void setRedactionMarker(String redactionMarker) {
        this.redactionMarker = redactionMarker;
    }

    public String getBlockMessage() {
        return blockMessage;
    }

    public void setBlockMessage(String blockMessage) {
        this.blockMessage = blockMessage;
    }
}
