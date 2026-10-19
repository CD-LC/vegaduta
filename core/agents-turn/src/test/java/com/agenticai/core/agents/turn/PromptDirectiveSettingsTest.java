package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.ResolvedDirectives.Setting;
import com.agenticai.core.security.TenantContext;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PromptDirectiveSettingsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicInteger loads = new AtomicInteger();
    private Map<String, Setting> stored = Map.of("tool_search", new Setting(false, null));
    private boolean failing;

    private final PromptDirectiveSettings settings =
            PromptDirectiveSettings.forTest(
                    () -> {
                        loads.incrementAndGet();
                        if (failing) {
                            throw new IllegalStateException("db down");
                        }
                        return stored;
                    },
                    clock::get);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static void tenant(String id) {
        TenantContext.set(TenantContext.of(id, "owner", Set.of()));
    }

    @Test
    void noTenantMeansDefaultsAndNoDatabaseRead() {
        assertThat(settings.forToday(DAY)).isSameAs(ResolvedDirectives.DEFAULTS);
        assertThat(loads).hasValue(0);
    }

    @Test
    void secondTurnIsServedFromTheCacheUntilTheTtlPasses() {
        tenant("a");
        assertThat(settings.forToday(DAY).resolve("tool_search", "x")).isNull();
        settings.forToday(DAY);
        assertThat(loads).hasValue(1);

        clock.addAndGet(PromptDirectiveSettings.TTL_SECONDS * 1_000_000_000L + 1);
        settings.forToday(DAY);
        assertThat(loads).hasValue(2);
    }

    @Test
    void tenantsDoNotShareACacheEntry() {
        tenant("a");
        settings.forToday(DAY);
        stored = Map.of();
        tenant("b");
        assertThat(settings.forToday(DAY).resolve("tool_search", "x")).isEqualTo("x");
        tenant("a");
        assertThat(settings.forToday(DAY).resolve("tool_search", "x")).isNull();
        assertThat(loads).hasValue(2);
    }

    @Test
    void evictMakesTheNextTurnReload() {
        tenant("a");
        settings.forToday(DAY);
        stored = Map.of();
        settings.evictCurrentTenant();

        assertThat(settings.forToday(DAY).resolve("tool_search", "x")).isEqualTo("x");
        assertThat(loads).hasValue(2);
    }

    @Test
    void aFailingStoreFallsBackToDefaultsAndRetriesSoon() {
        tenant("a");
        failing = true;
        assertThat(settings.forToday(DAY)).isSameAs(ResolvedDirectives.DEFAULTS);
        settings.forToday(DAY);
        assertThat(loads).hasValue(1);

        failing = false;
        clock.addAndGet(6_000_000_000L);
        assertThat(settings.forToday(DAY).resolve("tool_search", "x")).isNull();
    }

    @Test
    void noStoreBeanMeansDefaults() {
        tenant("a");
        assertThat(PromptDirectiveSettings.defaultsOnly().forToday(DAY))
                .isSameAs(ResolvedDirectives.DEFAULTS);
    }
}
