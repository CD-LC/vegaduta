package com.agenticai.core.agents.tools.openapi;

/**
 * How an imported API is authenticated on every call. The secret itself is never on this enum - it
 * lives AES-encrypted on {@link OpenApiToolSource} (see {@code encrypted_secret} in V232) and is
 * only ever decrypted inside {@link OpenApiToolExecutor}, right before the header is written.
 */
public enum OpenApiAuthType {
    /** No credential; the API is public. */
    NONE,
    /** {@code <authHeaderName>: <secret>} - e.g. {@code X-API-Key}. */
    API_KEY_HEADER,
    /** {@code Authorization: Bearer <secret>}. */
    BEARER,
    /**
     * OAuth 2.0 {@code client_credentials}: the source carries a token endpoint, client id and
     * scope; {@code encrypted_secret} holds the client SECRET (never the token). {@link
     * OpenApiToolExecutor} POSTs for a short-lived access token, caches it until just before its
     * expiry (Availity issues 5-minute tokens, Optum ~1 hour), retries a call once on a 401, and
     * sends it as {@code Authorization: Bearer}.
     */
    OAUTH2_CLIENT_CREDENTIALS
}
