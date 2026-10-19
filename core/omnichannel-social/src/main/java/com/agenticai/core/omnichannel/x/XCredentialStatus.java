package com.agenticai.core.omnichannel.x;

public enum XCredentialStatus {
    /**
     * App registered (clientId/secret stored) but no account has completed the OAuth consent yet -
     * see {@code XCredential}'s javadoc.
     */
    APP_REGISTERED,
    ACTIVE,
    REVOKED
}
