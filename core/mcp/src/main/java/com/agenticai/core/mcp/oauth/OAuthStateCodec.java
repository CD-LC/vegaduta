package com.agenticai.core.mcp.oauth;

import com.agenticai.core.crypto.EncryptionService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Builds and verifies the OAuth {@code state} parameter of the provider flow.
 *
 * <p>The state is an {@link EncryptionService} (AES-256-GCM) blob, so a
 * tampered, forged or foreign value fails to decrypt at all. Inside it:
 * {@code provider | tenant | user | nonce | issuedAt | codeVerifier | returnUrl}
 * - the tenant AND user that started the flow (the callback carries no JWT),
 * a random nonce that makes every state unique and single-use, a hard expiry,
 * the PKCE verifier (so nothing about the verifier is stored server-side) and
 * the already allow-listed console URL to land on. Fields are base64url-encoded
 * individually so no value can inject a delimiter.
 *
 * <p><b>Residual risk (documented, not closed):</b> the state is NOT bound to the browser
 * that started the flow. An admin can start a flow and hand the returned consent URL to
 * someone else; if that person consents, their provider tokens are stored for the
 * INITIATING tenant. Binding needs a short-lived HttpOnly SameSite cookie set by the
 * authorize call, which is a cross-origin fetch from the console (app.* to api.*) and so
 * needs credentialed CORS and fetch changes in the web/security scopes. Mitigations in
 * place: authorize requires PERM_MANAGE_INTEGRATIONS, the state is short-lived and
 * single-use, and tokens only ever flow to the provider's own MCP host. The Atlassian
 * flow has the same gap.
 *
 * <p>Verification order: decrypt, shape, provider-in-URL equals provider-in-state,
 * age (expired / issued in the future), then {@link #consume} makes it single
 * use. Single-use bookkeeping lives in the {@link McpOAuthSharedStore} the owning
 * service injects: per-JVM by default, shared across replicas with {@code
 * app.mcp.oauth.store=redis}; even without it a replay is still bounded by the
 * provider's own one-time authorization code.
 */
public final class OAuthStateCodec {

    private static final String VERSION = "v1";
    private static final int FIELDS = 8;
    private static final long FUTURE_SKEW_SECONDS = 60;

    private final EncryptionService encryption;
    private final Clock clock;
    private final long maxAgeSeconds;
    private final SecureRandom random = new SecureRandom();
    private final McpOAuthSharedStore shared;

    public OAuthStateCodec(EncryptionService encryption, Clock clock, long maxAgeSeconds) {
        this(encryption, clock, maxAgeSeconds, new InMemoryMcpOAuthSharedStore(clock));
    }

    /** Test/service seam: the consumed-nonce set can be shared across replicas. */
    OAuthStateCodec(EncryptionService encryption, Clock clock, long maxAgeSeconds, McpOAuthSharedStore shared) {
        this.encryption = encryption;
        this.clock = clock;
        this.maxAgeSeconds = Math.max(30, maxAgeSeconds);
        this.shared = shared;
    }

    /** What a valid state proves. */
    public record Payload(
        String providerId, String tenantId, String userId, String nonce, long issuedAtEpochSeconds,
        String codeVerifier, String returnUrl
    ) {
    }

    public String encode(String providerId, String tenantId, String userId, String codeVerifier, String returnUrl) {
        byte[] n = new byte[16];
        random.nextBytes(n);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        long now = clock.instant().getEpochSecond();
        String plain = String.join(".",
            VERSION, b64(providerId), b64(tenantId), b64(userId), nonce, Long.toString(now),
            b64(codeVerifier), b64(returnUrl));
        return encryption.encrypt(plain);
    }

    /**
     * @param expectedProviderId the provider named in the callback URL
     * @throws OAuthStateException tampered / malformed / expired / for another provider
     */
    public Payload decode(String state, String expectedProviderId) {
        if (state == null || state.isBlank()) {
            throw new OAuthStateException(OAuthStateException.INVALID, "Missing OAuth state.");
        }
        String plain;
        try {
            plain = encryption.decrypt(state);
        } catch (RuntimeException e) {
            throw new OAuthStateException(OAuthStateException.INVALID, "Could not verify the OAuth state - please try connecting again.");
        }
        String[] parts = plain == null ? new String[0] : plain.split("\\.", -1);
        if (parts.length != FIELDS || !VERSION.equals(parts[0])) {
            throw new OAuthStateException(OAuthStateException.INVALID, "Malformed OAuth state.");
        }
        long issuedAt;
        Payload payload;
        try {
            issuedAt = Long.parseLong(parts[5]);
            payload = new Payload(un64(parts[1]), un64(parts[2]), un64(parts[3]), parts[4], issuedAt, un64(parts[6]), un64(parts[7]));
        } catch (IllegalArgumentException e) {
            throw new OAuthStateException(OAuthStateException.INVALID, "Malformed OAuth state.");
        }
        if (expectedProviderId == null || !expectedProviderId.equals(payload.providerId())) {
            throw new OAuthStateException(OAuthStateException.PROVIDER_MISMATCH, "This authorization belongs to a different provider.");
        }
        long now = clock.instant().getEpochSecond();
        if (issuedAt > now + FUTURE_SKEW_SECONDS) {
            throw new OAuthStateException(OAuthStateException.INVALID, "Malformed OAuth state.");
        }
        if (now - issuedAt > maxAgeSeconds) {
            throw new OAuthStateException(OAuthStateException.EXPIRED, "This connection attempt expired - please try connecting again.");
        }
        if (payload.tenantId().isBlank() || payload.nonce().isBlank()) {
            throw new OAuthStateException(OAuthStateException.INVALID, "Malformed OAuth state.");
        }
        return payload;
    }

    /**
     * Marks the state's nonce used.
     *
     * @throws OAuthStateException if it was already consumed within its lifetime
     */
    public void consume(Payload payload) {
        Instant expiresAt = Instant.ofEpochSecond(payload.issuedAtEpochSeconds() + maxAgeSeconds + FUTURE_SKEW_SECONDS);
        if (!shared.claimNonce(McpOAuthSharedStore.STATE_KIND_PROVIDER, payload.nonce(), expiresAt)) {
            throw new OAuthStateException(OAuthStateException.REPLAYED, "This authorization link was already used - please try connecting again.");
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String un64(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
