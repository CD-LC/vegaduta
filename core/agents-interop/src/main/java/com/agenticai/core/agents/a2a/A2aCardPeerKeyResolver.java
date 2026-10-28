package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.net.URI;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves a caller's signing key from its Agent Card using {@link A2aCardVerifier} (SSRF-guarded,
 * no redirects, size-capped, card must itself be validly signed). The result is origin-bound proof,
 * like the card verification it builds on: it shows the request was signed by whoever controls the
 * key the caller's origin publishes, not that the origin is who it says. An operator who wants more
 * pins the origin with {@code app.a2a.server.trusted-card-hosts}.
 *
 * <p>Only an already authenticated caller reaches this (the bearer JWT is checked first), and the
 * lookups are bounded: results are cached for {@code key-cache-ttl}, failures for one minute, a
 * changed key id is re-fetched at most every 30 seconds per card, and the cache holds at most 256
 * cards.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Service
public class A2aCardPeerKeyResolver implements A2aPeerKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(A2aCardPeerKeyResolver.class);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(1);
    private static final Duration KID_REFETCH_MIN_AGE = Duration.ofSeconds(30);
    private static final int MAX_CARDS = 256;

    private record Entry(String kid, PublicKey key, long fetchedAtMillis, long expiresAtMillis) {}

    private final A2aCardVerifier verifier;
    private final A2aMessageSecurityProperties.Server config;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public A2aCardPeerKeyResolver(
            A2aCardVerifier verifier, A2aMessageSecurityProperties security) {
        this(verifier, security, Clock.systemUTC());
    }

    A2aCardPeerKeyResolver(
            A2aCardVerifier verifier, A2aMessageSecurityProperties security, Clock clock) {
        this.verifier = verifier;
        this.config = security.getServer();
        this.clock = clock;
    }

    @Override
    public Optional<PublicKey> resolve(String cardUrl, String keyId) {
        if (cardUrl == null || cardUrl.isBlank() || keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        if (!hostTrusted(cardUrl)) {
            return Optional.empty();
        }
        long now = clock.millis();
        Entry cached = cache.get(cardUrl);
        if (cached != null && cached.expiresAtMillis() > now) {
            if (cached.key() == null) {
                return Optional.empty(); // a recent failure
            }
            if (cached.kid().equals(keyId)) {
                return Optional.of(cached.key());
            }
            if (now - cached.fetchedAtMillis() < KID_REFETCH_MIN_AGE.toMillis()) {
                return Optional.empty(); // do not let a caller force a fetch per request
            }
        }
        A2aCardVerifier.KeyResolution resolution = verifier.resolveSigningKey(cardUrl);
        if (!resolution.result().verified() || resolution.key() == null) {
            log.debug(
                    "A2A peer key lookup failed for {}: {}", cardUrl, resolution.result().detail());
            store(cardUrl, new Entry("", null, now, now + FAILURE_TTL.toMillis()));
            return Optional.empty();
        }
        String verifiedKid = resolution.result().kid();
        store(
                cardUrl,
                new Entry(
                        verifiedKid == null ? "" : verifiedKid,
                        resolution.key(),
                        now,
                        now + config.getKeyCacheTtl().toMillis()));
        return verifiedKid != null && verifiedKid.equals(keyId)
                ? Optional.of(resolution.key())
                : Optional.empty();
    }

    private void store(String cardUrl, Entry entry) {
        if (cache.size() >= MAX_CARDS && !cache.containsKey(cardUrl)) {
            long now = clock.millis();
            cache.values().removeIf(e -> e.expiresAtMillis() <= now);
            if (cache.size() >= MAX_CARDS) {
                cache.clear();
            }
        }
        cache.put(cardUrl, entry);
    }

    private boolean hostTrusted(String cardUrl) {
        if (config.getTrustedCardHosts().isEmpty()) {
            return true;
        }
        String host;
        try {
            host = URI.create(cardUrl.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String entry : config.getTrustedCardHosts()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String e = entry.strip().toLowerCase(Locale.ROOT);
            if (e.startsWith("*.") ? h.endsWith(e.substring(1)) : h.equals(e)) {
                return true;
            }
        }
        return false;
    }
}
