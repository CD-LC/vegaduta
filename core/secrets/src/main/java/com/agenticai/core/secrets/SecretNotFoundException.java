package com.agenticai.core.secrets;

/**
 * No secret with this id is visible to the caller (unknown, already purged, or another user's
 * USER-scoped secret - deliberately indistinguishable). Mapped to HTTP 404. Extends
 * {@link IllegalArgumentException} so callers that already catch that keep working.
 */
public class SecretNotFoundException extends IllegalArgumentException {

    public SecretNotFoundException() {
        super("No such secret");
    }
}
