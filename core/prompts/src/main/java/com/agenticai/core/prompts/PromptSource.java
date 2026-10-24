package com.agenticai.core.prompts;

/** Where a resolved text came from. */
public enum PromptSource {
    /** The classpath resource file. */
    DEFAULT,
    PLATFORM,
    TENANT,
    AGENT,
    /**
     * The default text, served because looking up the overrides failed (database down, broken row).
     * A prompt problem never breaks a turn; it is logged and shows up here.
     */
    FALLBACK;

    public static PromptSource of(PromptScope scope) {
        return switch (scope) {
            case AGENT -> AGENT;
            case TENANT -> TENANT;
            case PLATFORM -> PLATFORM;
        };
    }
}
