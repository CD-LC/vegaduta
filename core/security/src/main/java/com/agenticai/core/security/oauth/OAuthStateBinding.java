package com.agenticai.core.security.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * SEC-018: binds a third-party OAuth "connect" flow (Google, X, Atlassian) to the authenticated
 * session that STARTED it, so an attacker cannot mint a consent URL in their own tenant, hand it to a
 * victim, and have the victim's account tokens land in the attacker's tenant.
 *
 * <p>At {@code /authorize-url} (JWT-authenticated) the provider service calls {@link #issue}: a fresh
 * 256-bit nonce is generated, stored server-side single-use with a {@link #TTL} of 10 minutes, keyed by
 * nonce and bound to (provider, tenant, subjectKey, initiator), and embedded in the encrypted
 * {@code state}. A {@code HttpOnly; Secure; SameSite=Lax} cookie carrying SHA-256(nonce), path-scoped to
 * the provider's callback, is returned for the caller to set. At the {@code permitAll} callback
 * {@link #consume} requires: a nonce in the state, the nonce still pending (removed atomically, so a
 * replay fails), not expired, the tenant/subject/provider in the state equal to the stored binding, and
 * - when the cookie is present - the cookie equal to SHA-256(nonce).
 *
 * <p><b>Browser-cookie constraint.</b> The web console calls {@code /authorize-url} with a cross-origin
 * {@code fetch} without {@code credentials: "include"}, and core's CORS config has
 * {@code allowCredentials(false)}, so today the browser DISCARDS the Set-Cookie and the cookie never
 * reaches the callback. The cookie is therefore enforced only when it is present, unless
 * {@code app.oauth.state.require-browser-cookie=true} (default {@code false}), which makes a missing
 * cookie a rejection. Flip it on only after the web fetch sends credentials and CORS allows them for the
 * console origin. Until then the binding is: encrypted + authenticated state, single-use, 10-minute TTL,
 * server-side tenant/user binding. That shrinks the phishing window to one use within 10 minutes of the
 * attacker minting the URL but does not by itself prove that the browser completing consent is the one
 * that started it.
 *
 * <p><b>Store.</b> An in-memory TTL map (per JVM). Core runs as a single replica today; with several
 * replicas behind a non-sticky ingress a callback may land on a pod that never issued the nonce and fail
 * closed ("please try again"). Moving the store to Redis/Postgres is the fix if core scales out.
 */
@Component
public class OAuthStateBinding {

    /** Lifetime of a pending nonce; also the cookie Max-Age. */
    public static final Duration TTL = Duration.ofMinutes(10);

    /** Bound on concurrently pending connects across all tenants (memory guard). */
    static final int MAX_PENDING = 20_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Pending(String provider, String tenant, String subjectKey, String initiator, Instant expiresAt) {
    }

    /** Result of {@link #issue}: the nonce to embed in the encrypted state and the cookie to set. */
    public record Issued(String nonce, ResponseCookie cookie) {
    }

    /** What the consumed nonce was bound to. */
    public record Binding(String provider, String tenant, String subjectKey, String initiator) {
    }

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final boolean requireBrowserCookie;
    private final Clock clock;

    @Autowired
    public OAuthStateBinding(@Value("${app.oauth.state.require-browser-cookie:false}") boolean requireBrowserCookie) {
        this(requireBrowserCookie, Clock.systemUTC());
    }

    public OAuthStateBinding(boolean requireBrowserCookie, Clock clock) {
        this.requireBrowserCookie = requireBrowserCookie;
        this.clock = Objects.requireNonNull(clock);
    }

    public static String cookieName(String provider) {
        return "vd_oauth_" + provider;
    }

    public static String callbackPath(String provider) {
        return "/oauth/" + provider + "/callback";
    }

    /** A cookie that deletes the provider's binding cookie (set on the final callback redirect). */
    public static ResponseCookie clearCookie(String provider) {
        return ResponseCookie.from(cookieName(provider), "")
            .httpOnly(true).secure(true).sameSite("Lax").path(callbackPath(provider)).maxAge(0).build();
    }

    /**
     * Mint and store a single-use nonce for a connect flow.
     *
     * @param provider   short provider key ({@code google}, {@code x}, {@code atlassian}); also the callback path segment
     * @param tenant     tenant schema/id the resulting credential will be stored in
     * @param subjectKey the user the credential is attributed to ({@code null} for a tenant-wide connect)
     * @param initiator  who started it (JWT subject), for audit/logging only
     */
    public Issued issue(String provider, String tenant, String subjectKey, String initiator) {
        requireText(provider, "provider");
        requireText(tenant, "tenant");
        Instant now = clock.instant();
        if (pending.size() >= MAX_PENDING) {
            pending.values().removeIf(p -> !now.isBefore(p.expiresAt()));
            if (pending.size() >= MAX_PENDING) {
                throw new IllegalStateException("Too many OAuth connections in progress - please try again shortly.");
            }
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        pending.put(nonce, new Pending(provider, tenant, subjectKey, initiator, now.plus(TTL)));
        ResponseCookie cookie = ResponseCookie.from(cookieName(provider), hash(nonce))
            .httpOnly(true).secure(true).sameSite("Lax").path(callbackPath(provider)).maxAge(TTL).build();
        return new Issued(nonce, cookie);
    }

    /**
     * Verify and CONSUME (single use) the binding for a callback.
     *
     * @param cookieValue the binding cookie from the callback request, or {@code null} if absent
     * @throws OAuthStateBindingException on any mismatch - the caller must not complete the connect
     */
    public Binding consume(String provider, String nonce, String tenant, String subjectKey, String cookieValue) {
        if (nonce == null || nonce.isBlank()) {
            throw new OAuthStateBindingException("OAuth state is not bound to a connect session");
        }
        Pending p = pending.remove(nonce);
        if (p == null) {
            throw new OAuthStateBindingException("OAuth state is unknown or was already used");
        }
        if (!clock.instant().isBefore(p.expiresAt())) {
            throw new OAuthStateBindingException("OAuth state expired");
        }
        if (!p.provider().equals(provider) || !p.tenant().equals(tenant) || !Objects.equals(p.subjectKey(), subjectKey)) {
            throw new OAuthStateBindingException("OAuth state does not match the connect session that issued it");
        }
        if (cookieValue == null || cookieValue.isBlank()) {
            if (requireBrowserCookie) {
                throw new OAuthStateBindingException("OAuth callback did not come from the browser that started it");
            }
        } else if (!MessageDigest.isEqual(
            hash(nonce).getBytes(StandardCharsets.US_ASCII), cookieValue.getBytes(StandardCharsets.US_ASCII))) {
            throw new OAuthStateBindingException("OAuth callback did not come from the browser that started it");
        }
        return new Binding(p.provider(), p.tenant(), p.subjectKey(), p.initiator());
    }

    int pendingCount() {
        return pending.size();
    }

    static String hash(String nonce) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('|') >= 0) {
            throw new IllegalArgumentException("Invalid OAuth binding " + name);
        }
    }
}
