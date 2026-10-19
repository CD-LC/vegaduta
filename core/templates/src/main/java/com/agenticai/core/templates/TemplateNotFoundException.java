package com.agenticai.core.templates;

/**
 * Thrown whenever a template can't be shown to the caller - it genuinely
 * doesn't exist, OR it exists but the caller isn't entitled to see it in its
 * current state (a non-approved template requested by someone who isn't its
 * owner/an admin). Mapped to HTTP 404 in {@code core/app}'s
 * {@code GlobalExceptionHandler}, NEVER 403, by deliberate design.
 *
 * <p><b>Anti-enumeration</b> - same posture as {@code core/sharing}'s
 * {@code ResourceAccessDeniedException} and {@code AgentNotFoundException}: a
 * caller with no right to a template must get "not found", never "forbidden",
 * because a 403 would confirm the template exists. The moderation queue and
 * every member's private drafts are invisible to outsiders precisely because a
 * request for one is indistinguishable from a request for a made-up id. The
 * message is a generic constant - never interpolate the id, status, or reason.
 */
public class TemplateNotFoundException extends RuntimeException {

    private static final String GENERIC_MESSAGE = "Template not found.";

    public TemplateNotFoundException() {
        super(GENERIC_MESSAGE);
    }
}
