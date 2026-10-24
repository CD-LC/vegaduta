package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.backoff.Sleeper;

/**
 * {@code chat.retry.maxAttempts}: a tenant's attempts per model call replace {@code app.llm.retry.max-attempts} for
 * the calls made in its name, on the real retry template and the real policy over the real resolver; another tenant, a
 * thread without a tenant and a broken catalog all keep the platform value.
 */
class LlmRetrySettingsTest {

    private static final String KEY = LlmRetryPolicy.MAX_ATTEMPTS_KEY;
    private static final Sleeper NO_SLEEP = millis -> {};

    private final InMemorySettingsStore store = new InMemorySettingsStore();
    private final LlmRetryProperties platform = platformProperties();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static LlmRetryProperties platformProperties() {
        LlmRetryProperties p = new LlmRetryProperties();
        p.setMaxAttempts(2);
        p.setJitter(0.0);
        return p;
    }

    private SettingsResolver catalog() {
        SettingDefinition def =
                SettingDefinition.builder(KEY, "chat", SettingType.INT)
                        .unit(SettingUnit.COUNT)
                        .range(1, 6)
                        .risk(SettingRisk.LOW)
                        .scopes(SettingScope.TENANT)
                        .property("app.llm.retry.max-attempts")
                        .platform(platform::getMaxAttempts)
                        .build();
        SettingsProperties noCache = new SettingsProperties();
        noCache.setCacheTtl(Duration.ZERO);
        return new DefaultSettingsResolver(SettingsRegistry.of(def), store, List.of(), noCache, null);
    }

    private void inTenant(String tenant, Runnable work) {
        TenantContext.set(TenantContext.of(tenant, "u", Set.of()));
        try {
            work.run();
        } finally {
            TenantContext.clear();
        }
    }

    private void tenantSets(String tenant, int attempts) {
        inTenant(
                tenant,
                () ->
                        store.saveCurrent(
                                new SettingOverride(SettingScope.TENANT, "", KEY, attempts, 1, "admin", Instant.now())));
    }

    private static int attemptsMadeBy(LlmRetry retry) {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                TransientAiException.class,
                () ->
                        retry.execute(
                                () -> {
                                    calls.incrementAndGet();
                                    throw new TransientAiException("503");
                                }));
        return calls.get();
    }

    private LlmRetry retryOverTheCatalog() {
        LlmRetryPolicy policy = new LlmRetryPolicy(platform, () -> 0.0).useSettings(this::catalog);
        return new SpringRetryLlmRetry(policy, NO_SLEEP);
    }

    @Test
    void withNoOverrideACallIsAttemptedAsManyTimesAsThePlatformSays() {
        LlmRetry retry = retryOverTheCatalog();

        inTenant("tenant-a", () -> assertEquals(platform.getMaxAttempts(), attemptsMadeBy(retry)));
        assertEquals(platform.getMaxAttempts(), new LlmRetryPolicy(platform).useSettings(this::catalog).maxAttempts(),
                "no tenant on the thread: the platform value");
    }

    @Test
    void aTenantsOwnAttemptsReplaceThePlatformsForItsCallsOnly() {
        tenantSets("tenant-a", 4);
        LlmRetry retry = retryOverTheCatalog();

        inTenant("tenant-a", () -> assertEquals(4, attemptsMadeBy(retry), "the tenant asked for four attempts"));
        inTenant("tenant-b", () -> assertEquals(2, attemptsMadeBy(retry), "another tenant keeps the platform value"));
    }

    @Test
    void aTenantCanAskForASingleAttempt() {
        tenantSets("tenant-a", 1);
        LlmRetry retry = retryOverTheCatalog();

        inTenant("tenant-a", () -> assertEquals(1, attemptsMadeBy(retry)));
    }

    @Test
    void theStreamingPathAsksWithTheTurnsTenantScopeOnAnyThread() throws Exception {
        tenantSets("tenant-a", 5);
        LlmRetryPolicy policy = new LlmRetryPolicy(platform, () -> 0.0).useSettings(this::catalog);
        TenantScope scope = TenantScope.of(TenantContext.of("tenant-a", "u", Set.of()));
        int[] seen = new int[2];
        boolean[] streams = new boolean[1];

        Thread other =
                new Thread(
                        () -> {
                            seen[0] = policy.maxAttempts();
                            seen[1] = policy.maxAttempts(scope);
                            streams[0] = policy.retriesStreamsBeforeFirstToken(scope);
                        });
        other.start();
        other.join();

        assertEquals(2, seen[0], "a scheduler thread holds no tenant: the platform value");
        assertEquals(5, seen[1], "the turn's scope carries the tenant to it");
        assertTrue(streams[0]);
    }

    @Test
    void aTenantThatAsksForOneAttemptTurnsTheStreamRetryOff() {
        tenantSets("tenant-a", 1);
        LlmRetryPolicy policy = new LlmRetryPolicy(platform, () -> 0.0).useSettings(this::catalog);
        TenantScope scope = TenantScope.of(TenantContext.of("tenant-a", "u", Set.of()));

        assertTrue(!policy.retriesStreamsBeforeFirstToken(scope));
        assertTrue(policy.retriesStreamsBeforeFirstToken(TenantScope.of(TenantContext.of("tenant-b", "u", Set.of()))));
    }

    @Test
    void aCatalogThatFailsOrIsAbsentLeavesThePlatformValue() {
        SettingsResolver broken =
                new SettingsResolver() {
                    @Override
                    public com.agenticai.core.settings.SettingValue get(String key, com.agenticai.core.settings.SettingContext ctx) {
                        throw new IllegalStateException("store is down");
                    }

                    @Override
                    public java.util.Map<String, com.agenticai.core.settings.SettingValue> resolveAll(
                            com.agenticai.core.settings.SettingContext ctx) {
                        throw new IllegalStateException("store is down");
                    }

                    @Override
                    public SettingsRegistry registry() {
                        return SettingsRegistry.of();
                    }
                };

        inTenant(
                "tenant-a",
                () -> {
                    assertEquals(2, new LlmRetryPolicy(platform).useSettings(() -> broken).maxAttempts());
                    assertEquals(2, new LlmRetryPolicy(platform).useSettings(() -> null).maxAttempts());
                    assertEquals(2, new LlmRetryPolicy(platform).maxAttempts());
                });
    }
}
