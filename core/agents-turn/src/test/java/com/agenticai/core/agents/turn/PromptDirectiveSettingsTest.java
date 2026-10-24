package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.PromptDirectiveRegistry.Keys;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PromptDirectiveSettingsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final PromptProperties props = new PromptProperties();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, props, clock::get);
    private final PromptDirectiveSettings settings = PromptDirectiveSettings.forTest(resolver);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static void tenant(String id) {
        TenantContext.set(TenantContext.of(id, "owner", Set.of()));
    }

    private void store(String key) {
        store.put(new PromptOverride(PromptScope.TENANT, "", key, "en", null, false, 1, "owner", Instant.now()));
    }

    @Test
    void noTenantMeansDefaultsAndNoDatabaseRead() {
        assertThat(settings.forToday(DAY).text(Keys.TOOL_SEARCH)).isEqualTo(ChatPromptDirectives.TOOL_SEARCH_DIRECTIVE);
        assertThat(store.tenantLoads).hasValue(0);
    }

    @Test
    void secondTurnIsServedFromTheCacheUntilTheTtlPasses() {
        tenant("a");
        store(Keys.TOOL_SEARCH);
        assertThat(settings.forToday(DAY).text(Keys.TOOL_SEARCH)).isNull();
        settings.forToday(DAY).text(Keys.TOOL_SEARCH);
        assertThat(store.tenantLoads).hasValue(1);

        clock.addAndGet(props.getCache().getTtl().toNanos() + 1);
        settings.forToday(DAY).text(Keys.TOOL_SEARCH);
        assertThat(store.tenantLoads).hasValue(2);
    }

    @Test
    void tenantsDoNotShareACacheEntry() {
        tenant("a");
        store(Keys.TOOL_SEARCH);
        assertThat(settings.forToday(DAY).text(Keys.TOOL_SEARCH)).isNull();
        tenant("b");
        assertThat(settings.forToday(DAY).text(Keys.TOOL_SEARCH)).isEqualTo(ChatPromptDirectives.TOOL_SEARCH_DIRECTIVE);
        tenant("a");
        assertThat(settings.forToday(DAY).text(Keys.TOOL_SEARCH)).isNull();
        assertThat(store.tenantLoads).hasValue(2);
    }

    @Test
    void aFailingStoreFallsBackToDefaultsAndRetriesSoon() {
        tenant("a");
        store(Keys.TOOL_SEARCH);
        store.failWith = new IllegalStateException("db down");
        assertThat(settings.forToday(DAY).text(Keys.TOOL_SEARCH)).isEqualTo(ChatPromptDirectives.TOOL_SEARCH_DIRECTIVE);

        store.failWith = null;
        clock.addAndGet(props.getCache().getFailureTtl().toNanos() + 1);
        assertThat(settings.forToday(DAY).text(Keys.TOOL_SEARCH)).isNull();
    }

    @Test
    void noResolverBeanMeansTheBuiltInTextWithTheDateFilledIn() {
        tenant("a");
        ResolvedDirectives pd = PromptDirectiveSettings.defaultsOnly().forToday(DAY);

        assertThat(pd.text(Keys.TOOL_SEARCH)).isEqualTo(ChatPromptDirectives.TOOL_SEARCH_DIRECTIVE);
        assertThat(pd.text(Keys.CURRENT_DATE)).isEqualTo(ChatPromptDirectives.currentDateDirective(DAY));
    }
}
