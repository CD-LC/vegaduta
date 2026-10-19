package com.agenticai.core.mcp.oauth;

import com.agenticai.core.crypto.EncryptionService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * The OAuth {@code state} of the generic remote-MCP flow ({@link McpOAuthService}); the sibling of
 * {@link OAuthStateCodec}, which is bound to a provider id instead of a server.
 *
 * <p>The state is an {@link EncryptionService} (AES-256-GCM) blob, so a tampered, forged or foreign value fails
 * to decrypt at all. Inside it, every value the callback must NOT take from the (attacker-reachable) request:
 * <ul>
 *   <li><b>tenant + user</b> that started the flow - the callback carries no JWT, so this is the only identity;</li>
 *   <li><b>the canonical MCP resource URL</b> the flow is for - tokens are stored under it and nowhere else;</li>
 *   <li><b>the PKCE verifier</b> - nothing about it is stored server-side;</li>
 *   <li><b>the expected issuer</b> and whether the AS promised an {@code iss} response parameter (RFC 9207
 *       mix-up defence);</li>
 *   <li><b>the client id and redirect URI</b> the authorization request used, so the code exchange is made with
 *       exactly the same pair and a client re-registered in between cannot be mixed in;</li>
 *   <li>a random nonce (single use) and a hard expiry.</li>
 * </ul>
 * Each field is base64url-encoded individually so no value can inject a delimiter.
 *
 * <p>Single-use bookkeeping lives in the {@link McpOAuthSharedStore} the owning service injects:
 * per-JVM by default, shared across replicas with {@code app.mcp.oauth.store=redis} (an atomic
 * {@code SET NX EX}). Even without it a replay is still bounded by the authorization server's own
 * one-time authorization code and the PKCE binding.
 */
public final class McpOAuthStateCodec {

    private static final String VERSION = "m1";
    private static final int FIELDS = 12;
    private static final long FUTURE_SKEW_SECONDS = 60;

    private final EncryptionService encryption;
    private final Clock clock;
    private final long maxAgeSeconds;
    private final SecureRandom random = new SecureRandom();
    private final McpOAuthSharedStore shared;

    public McpOAuthStateCodec(EncryptionService encryption, Clock clock, long maxAgeSeconds) {
        this(encryption, clock, maxAgeSeconds, new InMemoryMcpOAuthSharedStore(clock));
    }

    /** Test/service seam: the consumed-nonce set can be shared across replicas. */
    McpOAuthStateCodec(EncryptionService encryption, Clock clock, long maxAgeSeconds, McpOAuthSharedStore shared) {
        this.encryption = encryption;
        this.clock = clock;
        this.maxAgeSeconds = Math.max(30, maxAgeSeconds);
        this.shared = shared;
    }

    /** What a valid state proves. */
    public record Payload(
        String tenantId,
        String userId,
        String resourceUrl,
        String nonce,
        long issuedAtEpochSeconds,
        String codeVerifier,
        String returnUrl,
        String issuer,
        boolean issRequired,
        String clientId,
        String redirectUri
    ) {
    }

    public String encode(
        String tenantId, String userId, String resourceUrl, String codeVerifier, String returnUrl,
        String issuer, boolean issRequired, String clientId, String redirectUri
    ) {
        byte[] n = new byte[16];
        random.nextBytes(n);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        long now = clock.instant().getEpochSecond();
        String plain = String.join(".",
            VERSION, b64(tenantId), b64(userId), b64(resourceUrl), nonce, Long.toString(now), b64(codeVerifier),
            b64(returnUrl), b64(issuer), issRequired ? "1" : "0", b64(clientId), b64(redirectUri));
        return encryption.encrypt(plain);
    }

    /**
     * @throws OAuthStateException tampered / malformed / expired
     */
    public Payload decode(String state) {
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
            payload = new Payload(un64(parts[1]), un64(parts[2]), un64(parts[3]), parts[4], issuedAt, un64(parts[6]),
                un64(parts[7]), un64(parts[8]), "1".equals(parts[9]), un64(parts[10]), un64(parts[11]));
        } catch (IllegalArgumentException e) {
            throw new OAuthStateException(OAuthStateException.INVALID, "Malformed OAuth state.");
        }
        long now = clock.instant().getEpochSecond();
        if (issuedAt > now + FUTURE_SKEW_SECONDS) {
            throw new OAuthStateException(OAuthStateException.INVALID, "Malformed OAuth state.");
        }
        if (now - issuedAt > maxAgeSeconds) {
            throw new OAuthStateException(OAuthStateException.EXPIRED, "This connection attempt expired - please try connecting again.");
        }
        if (payload.tenantId().isBlank() || payload.nonce().isBlank() || payload.resourceUrl().isBlank()
            || payload.codeVerifier().isBlank() || payload.issuer().isBlank()) {
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
        if (!shared.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, payload.nonce(), expiresAt)) {
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
