package com.agenticai.core.providers;

import java.time.Instant;

/** Never echoes the decrypted key - same "status only, never the secret" contract every other credential status DTO in this codebase follows. */
public record PlatformToolCredentialStatusResponse(
    String toolKey, boolean configured, String extraConfig, Instant updatedAt, String updatedBy
) {
    public static PlatformToolCredentialStatusResponse notConfigured(String toolKey) {
        return new PlatformToolCredentialStatusResponse(toolKey, false, null, null, null);
    }
}
