package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HotpathLookupsTest {

    @AfterEach
    void reset() {
        HotpathLookups.configure(Duration.ZERO);
        TenantContext.clear();
    }

    private static void asTenant(String tenant) {
        TenantContext.set(TenantContext.of(tenant, "user", Set.of()));
    }

    @Test
    void beforeConfigureEveryCallRunsTheLoader() {
        asTenant("a");
        AtomicInteger loads = new AtomicInteger();
        HotpathLookups.tenant("x", () -> loads.incrementAndGet());
        HotpathLookups.tenant("x", () -> loads.incrementAndGet());
        assertThat(loads).hasValue(2);
    }

    @Test
    void repeatedLookupsWithinTheTtlRunTheLoaderOnce() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        asTenant("a");
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            assertThat(HotpathLookups.<Boolean>tenant("mcp.any", () -> loads.incrementAndGet() > 0))
                    .isTrue();
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void aFalseAnswerIsCachedToo() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        asTenant("a");
        AtomicInteger loads = new AtomicInteger();
        HotpathLookups.<Boolean>tenant(
                "x",
                () -> {
                    loads.incrementAndGet();
                    return false;
                });
        assertThat(HotpathLookups.<Boolean>tenant("x", () -> true)).isFalse();
        assertThat(loads).hasValue(1);
    }

    @Test
    void oneTenantsAnswerIsNeverServedToAnother() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        asTenant("a");
        assertThat(HotpathLookups.<String>tenant("k", () -> "from-a")).isEqualTo("from-a");
        asTenant("b");
        assertThat(HotpathLookups.<String>tenant("k", () -> "from-b")).isEqualTo("from-b");
        asTenant("a");
        assertThat(HotpathLookups.<String>tenant("k", () -> "reloaded")).isEqualTo("from-a");
    }

    @Test
    void theArgumentGivesEachSubjectItsOwnEntry() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        asTenant("a");
        assertThat(HotpathLookups.<String>tenant("mandate", "agent-1", () -> "one"))
                .isEqualTo("one");
        assertThat(HotpathLookups.<String>tenant("mandate", "agent-2", () -> "two"))
                .isEqualTo("two");
    }

    @Test
    void withNoTenantContextNothingIsCached() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        AtomicInteger loads = new AtomicInteger();
        HotpathLookups.tenant("x", () -> loads.incrementAndGet());
        HotpathLookups.tenant("x", () -> loads.incrementAndGet());
        assertThat(loads).hasValue(2);
    }

    @Test
    void evictingATenantDropsOnlyThatTenantsAnswers() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        asTenant("a");
        HotpathLookups.<String>tenant("k", () -> "a1");
        asTenant("b");
        HotpathLookups.<String>tenant("k", () -> "b1");

        HotpathCaches.evictTenantConfig("a");

        asTenant("a");
        assertThat(HotpathLookups.<String>tenant("k", () -> "a2")).isEqualTo("a2");
        asTenant("b");
        assertThat(HotpathLookups.<String>tenant("k", () -> "b2")).isEqualTo("b1");
    }

    @Test
    void aTenantIdThatPrefixesAnotherIsNotEvictedByMistake() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        asTenant("acme2");
        HotpathLookups.<String>tenant("k", () -> "kept");
        HotpathCaches.evictTenantConfig("acme");
        assertThat(HotpathLookups.<String>tenant("k", () -> "reloaded")).isEqualTo("kept");
    }

    @Test
    void platformAnswersIgnoreTheTenantAndAreEvictedTogether() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        asTenant("a");
        assertThat(HotpathLookups.<String>platform("cred", "brave", () -> "v1")).isEqualTo("v1");
        asTenant("b");
        assertThat(HotpathLookups.<String>platform("cred", "brave", () -> "v2")).isEqualTo("v1");

        HotpathCaches.evictPlatformConfig();

        assertThat(HotpathLookups.<String>platform("cred", "brave", () -> "v3")).isEqualTo("v3");
    }

    @Test
    void anExceptionFromTheLoaderPropagatesAndIsNotCached() {
        HotpathLookups.configure(Duration.ofSeconds(5));
        asTenant("a");
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            try {
                HotpathLookups.tenant(
                        "x",
                        () -> {
                            loads.incrementAndGet();
                            throw new IllegalStateException("db down");
                        });
            } catch (IllegalStateException expected) {
                // expected
            }
        }
        assertThat(loads).hasValue(2);
    }
}
