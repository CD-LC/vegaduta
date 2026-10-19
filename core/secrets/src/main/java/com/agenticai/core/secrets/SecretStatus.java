package com.agenticai.core.secrets;

/**
 * Lifecycle of a vault row. Rows are never hard-deleted by the normal delete
 * path: revoking keeps the row (and therefore its audit trail and its
 * {@code last_used_at}) while making it unresolvable, so "who used this
 * credential, and when did it stop working" survives the revocation.
 */
public enum SecretStatus {

    /** Resolvable. */
    ACTIVE,

    /** Kept for the audit trail, refused by every resolve path. */
    REVOKED
}
