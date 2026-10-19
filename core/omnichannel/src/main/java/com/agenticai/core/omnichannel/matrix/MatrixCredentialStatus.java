package com.agenticai.core.omnichannel.matrix;

public enum MatrixCredentialStatus {
    ACTIVE,
    // The homeserver rejected the access token (M_UNKNOWN_TOKEN): needs a new token, not a retry.
    INVALID,
    // Tenant-initiated disconnect, distinct from INVALID: the token may still be valid.
    DISCONNECTED
}
