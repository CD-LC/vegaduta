package com.agenticai.core.sdlc;

/**
 * The E2B credential one {@code run_code} call runs on: an API key, the code-execution template to
 * boot, and whose account pays for it. Resolved per call by {@link E2bAccessResolver}, never
 * cached, never serialised (the key is plaintext - {@link #toString()} redacts it).
 *
 * @param apiKey plaintext E2B API key for the {@code X-API-Key} header
 * @param templateId the E2B template built from {@code core/sdlc/e2b-template}
 * @param source {@link Source#TENANT} = the workspace's own BYOK key (its own E2B bill);
 *     {@link Source#PLATFORM} = the platform-managed fallback key (platform spend, so the caller
 *     must meter it)
 */
public record E2bAccess(String apiKey, String templateId, Source source) {

    /** Whose E2B account a run is billed to. */
    public enum Source {
        TENANT,
        PLATFORM
    }

    public E2bAccess {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
    }

    public boolean platformManaged() {
        return source == Source.PLATFORM;
    }

    @Override
    public String toString() {
        return "E2bAccess[source=" + source + ", templateId=" + templateId + ", apiKey=***]";
    }
}
