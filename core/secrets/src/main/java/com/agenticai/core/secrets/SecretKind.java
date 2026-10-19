package com.agenticai.core.secrets;

/**
 * What a secret is, so the UI can label and mask it correctly and so
 * injection sites can pick a sane default carrier (header vs env vs form
 * field). Deliberately coarse - this is a presentation and defaulting hint,
 * never an authorization decision.
 */
public enum SecretKind {

    /** Long-lived provider/service key, e.g. {@code sk_live_...}. */
    API_KEY,

    /**
     * A password for a third-party site the agent acts on.
     *
     * <p>Highest-risk kind in the vault: unlike an API key it usually cannot be
     * scoped down or revoked independently, so {@link SecretVaultService}
     * refuses to expose it through any read path and the tool layer may only
     * ever receive it through a resolved reference.
     */
    PASSWORD,

    /** Bearer/OAuth/session token - typically short-lived, so expect {@code expiresAt}. */
    TOKEN,

    /** Username + password pair, stored as {@code user:pass} and carried as HTTP Basic. */
    BASIC_AUTH,

    /** Anything else: a connection string, a webhook signing secret, a private key. */
    CUSTOM
}
