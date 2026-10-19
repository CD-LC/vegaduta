package com.agenticai.core.google;

/** ACTIVE while connected; REVOKED after an explicit disconnect (see GoogleOAuthService#disconnect). */
public enum GoogleCredentialStatus {
    ACTIVE,
    REVOKED
}
