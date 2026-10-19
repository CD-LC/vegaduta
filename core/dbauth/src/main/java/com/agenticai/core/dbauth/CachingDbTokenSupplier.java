package com.agenticai.core.dbauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches one token and fetches a new one shortly before it expires, so a pool opening many
 * connections signs once per refresh window rather than once per connection, and a connection
 * opened at the edge of the window never carries a token about to lapse.
 *
 * <p>Refresh happens {@code refreshSkew} before expiry (capped at half the token's lifetime so a
 * short-lived token is not refetched on every call). If a refresh fails while the cached token is
 * still valid, that token is served and the failure logged, and the source is not asked again for
 * {@link #RETRY_BACKOFF} (or until the token expires, if sooner): a connection storm during an
 * identity-provider outage must not serialise on slow failing calls under the lock. Once the token
 * has expired the failure propagates as {@link DbAuthException} and every call retries.
 */
public final class CachingDbTokenSupplier implements DbTokenSupplier {

    /** How long a failed refresh is not retried while the old token is still valid. */
    static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(CachingDbTokenSupplier.class);

    private final DbTokenSource source;
    private final Duration refreshSkew;
    private final Clock clock;
    private final Object lock = new Object();
    private volatile Cached cached;

    public CachingDbTokenSupplier(DbTokenSource source, Duration refreshSkew, Clock clock) {
        this.source = source;
        this.refreshSkew = refreshSkew;
        this.clock = clock;
    }

    @Override
    public String password() {
        Cached c = cached;
        if (c != null && clock.instant().isBefore(c.refreshAt)) {
            return c.token.value();
        }
        synchronized (lock) {
            c = cached;
            Instant now = clock.instant();
            if (c != null && now.isBefore(c.refreshAt)) {
                return c.token.value();
            }
            try {
                DbToken fresh = source.fetch();
                cached = Cached.of(fresh, now, refreshSkew);
                return fresh.value();
            } catch (RuntimeException e) {
                if (c != null && now.isBefore(c.token.expiresAt())) {
                    log.warn(
                            "Database token refresh failed ({}); reusing the current token until"
                                    + " {}",
                            e.getClass().getSimpleName(),
                            c.token.expiresAt());
                    Instant retryAt = now.plus(RETRY_BACKOFF);
                    cached =
                            new Cached(
                                    c.token,
                                    retryAt.isBefore(c.token.expiresAt())
                                            ? retryAt
                                            : c.token.expiresAt());
                    return c.token.value();
                }
                if (e instanceof DbAuthException dae) {
                    throw dae;
                }
                throw new DbAuthException(
                        "Could not obtain a database login token: " + e.getMessage(), e);
            }
        }
    }

    private record Cached(DbToken token, Instant refreshAt) {
        static Cached of(DbToken token, Instant fetchedAt, Duration skew) {
            Duration lifetime = Duration.between(fetchedAt, token.expiresAt());
            Duration half = lifetime.dividedBy(2);
            Duration effective = skew.compareTo(half) > 0 ? half : skew;
            return new Cached(token, token.expiresAt().minus(effective));
        }
    }
}
