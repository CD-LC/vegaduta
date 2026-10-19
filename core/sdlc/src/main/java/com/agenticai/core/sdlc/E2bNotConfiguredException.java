package com.agenticai.core.sdlc;

/** Thrown by {@link E2bCredentialService#resolveActiveApiKey()} when no tenant credential is active yet - mirrors {@code SarvamNotConfiguredException}. */
public class E2bNotConfiguredException extends RuntimeException {

    public E2bNotConfiguredException() {
        super("No active E2B credential configured - POST /api/sdlc/credential first.");
    }

    /** Master Roadmap 2026 item #14 - also thrown with a custom message when a credential is active but no code-execution template is configured yet. */
    public E2bNotConfiguredException(String message) {
        super(message);
    }
}
