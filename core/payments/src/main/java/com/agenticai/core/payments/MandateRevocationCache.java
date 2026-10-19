package com.agenticai.core.payments;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis tombstone for revoked mandates (§E1: "revoked mandate fails
 * in-flight calls ≤1s"). {@code PaymentMandateService#revoke} writes {@code
 * mandate:revoked:<id>} the instant the DB row flips to REVOKED, and {@link
 * MandateEnforcer} consults it BEFORE any further work so an in-flight tool
 * call dies immediately instead of waiting on a stale read.
 *
 * <p>Fail-soft by design: the authoritative revocation is the {@code
 * payment_mandate.status} row itself (also re-checked at execution), so a
 * missing Redis bean or a Redis blip degrades to "DB read is the check" -
 * still correct, just at DB latency rather than cache latency.
 */
@Component
public class MandateRevocationCache {

    private static final Logger log = LoggerFactory.getLogger(MandateRevocationCache.class);

    static final String KEY_PREFIX = "mandate:revoked:";
    /** Tombstones outlive any mandate validity window they could race with. */
    private static final Duration TOMBSTONE_TTL = Duration.ofDays(30);

    private final ObjectProvider<StringRedisTemplate> redis;

    public MandateRevocationCache(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    public void tombstone(UUID mandateId) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.opsForValue().set(KEY_PREFIX + mandateId, "1", TOMBSTONE_TTL);
        } catch (RuntimeException e) {
            log.warn("Revocation tombstone write failed for mandate {} - DB status remains authoritative", mandateId, e);
        }
    }

    public boolean isRevoked(UUID mandateId) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(template.hasKey(KEY_PREFIX + mandateId));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
