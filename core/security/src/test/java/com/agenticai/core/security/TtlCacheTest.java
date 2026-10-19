package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TtlCacheTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private TtlCache<String> cache(String name, Duration ttl, int max) {
        return new TtlCache<>(name, ttl, max, now::get);
    }

    @Test
    void zeroTtlIsAPassThroughAndStoresNothing() {
        TtlCache<String> c = cache("t.zero", Duration.ZERO, 10);
        AtomicInteger loads = new AtomicInteger();
        c.get("k", () -> "v" + loads.incrementAndGet());
        c.get("k", () -> "v" + loads.incrementAndGet());
        assertThat(loads).hasValue(2);
        assertThat(c.size()).isZero();
        assertThat(c.isEnabled()).isFalse();
    }

    @Test
    void hitsWithinTtlThenReloadsAfterExpiry() {
        TtlCache<String> c = cache("t.ttl", Duration.ofSeconds(10), 10);
        AtomicInteger loads = new AtomicInteger();
        assertThat(c.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        now.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(c.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(c.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v2");
        assertThat(c.hits()).isEqualTo(1);
        assertThat(c.misses()).isEqualTo(2);
    }

    @Test
    void nullAndFailuresAreNeverCached() {
        TtlCache<String> c = cache("t.null", Duration.ofSeconds(10), 10);
        AtomicInteger loads = new AtomicInteger();
        assertThat(c.get("k", () -> { loads.incrementAndGet(); return null; })).isNull();
        assertThat(c.get("k", () -> { loads.incrementAndGet(); return null; })).isNull();
        assertThatThrownBy(() -> c.get("e", () -> { throw new IllegalStateException("boom"); }))
            .isInstanceOf(IllegalStateException.class);
        assertThat(loads).hasValue(2);
        assertThat(c.size()).isZero();
    }

    @Test
    void boundedByFlushingWhenFull() {
        TtlCache<String> c = cache("t.bound", Duration.ofSeconds(10), 3);
        for (int i = 0; i < 10; i++) {
            c.get("k" + i, () -> "v");
        }
        assertThat(c.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void tenantKeysNeverCollideAndPrefixEvictionIsPerTenant() {
        TtlCache<String> c = cache("t.tenant", Duration.ofSeconds(10), 100);
        c.get(TtlCache.key("acme", "ANTHROPIC"), () -> "acme-key");
        c.get(TtlCache.key("acme2", "ANTHROPIC"), () -> "acme2-key");
        c.get(TtlCache.key("acme", "OPENAI"), () -> "acme-openai");
        // a tenant id that is a string-prefix of another must not evict the other
        TtlCache.evictPrefix("t.tenant", "acme");
        assertThat(c.size()).isEqualTo(1);
        assertThat(c.get(TtlCache.key("acme2", "ANTHROPIC"), () -> "reloaded")).isEqualTo("acme2-key");
        assertThat(c.get(TtlCache.key("acme", "ANTHROPIC"), () -> "reloaded")).isEqualTo("reloaded");
    }

    @Test
    void evictPrefixOnProviderOnlyDropsThatProvider() {
        TtlCache<String> c = cache("t.provider", Duration.ofSeconds(10), 100);
        c.get(TtlCache.key("acme", "ANTHROPIC", "m1"), () -> "a");
        c.get(TtlCache.key("acme", "OPENAI", "m1"), () -> "b");
        TtlCache.evictPrefix("t.provider", "acme", "ANTHROPIC");
        assertThat(c.size()).isEqualTo(1);
    }

    @Test
    void hotpathEvictionReachesRegisteredCaches() {
        TtlCache<String> plans = new TtlCache<>(HotpathCaches.TENANT_PLAN, Duration.ofSeconds(10), 10);
        TtlCache<String> keys = new TtlCache<>(HotpathCaches.PROVIDER_KEY, Duration.ofSeconds(10), 10);
        plans.get(TtlCache.key("acme", "plan"), () -> "PREMIUM");
        plans.get(TtlCache.key("other", "plan"), () -> "FREE");
        keys.get(TtlCache.key("acme", "ANTHROPIC"), () -> "sk");
        HotpathCaches.evictTenantPlan("acme");
        assertThat(plans.size()).isEqualTo(1);
        assertThat(keys.size()).isEqualTo(1);
        HotpathCaches.evictProviderCredential("acme", "ANTHROPIC");
        assertThat(keys.size()).isZero();
        // unknown tenant on the write side flushes everything rather than guessing
        plans.get(TtlCache.key("acme", "plan"), () -> "PREMIUM");
        HotpathCaches.evictTenantPlan(null);
        assertThat(plans.size()).isZero();
    }
}
