package com.agenticai.core.mcp.atlassian;

public enum AtlassianCredentialStatus {
    ACTIVE,
    /** Explicit disconnect, or the refresh token was permanently rejected (invalid_grant) - see {@link AtlassianOAuthService#currentAccessToken}. */
    REVOKED
}
