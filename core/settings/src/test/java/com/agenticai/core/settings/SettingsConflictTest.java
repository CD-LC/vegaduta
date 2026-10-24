package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Two writers of one key: the loser gets a 409 SETTING_CONFLICT and nothing of it is stored, never a 500. */
class SettingsConflictTest {

    SettingsFixture f;

    @BeforeEach
    void setUp() {
        f = new SettingsFixture();
        SettingsFixture.asAdmin();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SettingsService serviceOver(SettingsStore store) {
        DefaultSettingsResolver resolver = new DefaultSettingsResolver(f.registry, store, List.of(f.backend), f.properties, f.environment, () -> f.now);
        return new SettingsService(f.registry, store, List.of(f.backend), resolver, f.audit, org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class), f.properties);
    }

    /** Both writers read the same "latest version" before either one appends - the interleaving the real lock prevents. */
    static class RacingStore extends InMemorySettingsStore {
        final CyclicBarrier bothHaveReadTheVersion = new CyclicBarrier(2);

        @Override
        public int latestVersion(SettingScope scope, String scopeId, String key) {
            int v = super.latestVersion(scope, scopeId, key);
            try {
                bothHaveReadTheVersion.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return v;
        }
    }

    @Test
    void theSecondOfTwoRacingWritersGetsASettingConflictAndLeavesNoTrace() throws Exception {
        RacingStore store = new RacingStore();
        SettingsService service = serviceOver(store);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = pool.submit(() -> attempt(service, 3));
            Future<Object> b = pool.submit(() -> attempt(service, 4));
            List<Object> outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(o -> o instanceof SettingsService.Applied).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o instanceof SettingConflictException).hasSize(1);
            SettingConflictException lost = (SettingConflictException) outcomes.stream().filter(o -> o instanceof SettingConflictException).findFirst().orElseThrow();
            assertThat(lost.key()).isEqualTo("chat.retry.maxAttempts");
            assertThat(lost.getCause()).isInstanceOf(DuplicateKeyException.class);
        } finally {
            pool.shutdownNow();
        }
        TenantContext.set(TenantContext.of("tenant-a", "alice", java.util.Set.of("ROLE_tenant-admin")));
        assertThat(store.history(SettingScope.TENANT, "", "chat.retry.maxAttempts", 10)).hasSize(1);
    }

    private static Object attempt(SettingsService service, int value) {
        TenantContext.set(TenantContext.of("tenant-a", "alice", java.util.Set.of("ROLE_tenant-admin")));
        try {
            return service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", value, null);
        } catch (RuntimeException e) {
            return e;
        } finally {
            TenantContext.clear();
        }
    }

    /** A store whose history insert always loses the race. */
    static class AlwaysDuplicate extends InMemorySettingsStore {
        @Override
        public void appendHistory(SettingHistoryEntry entry) {
            throw new DuplicateKeyException("setting_override_history_version_uq");
        }
    }

    @Test
    void theHttpAnswerIs409WithTheConflictCodeForAPutAndForADelete() throws Exception {
        SettingsService service = serviceOver(new AlwaysDuplicate());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SettingsController(service)).setControllerAdvice(new SettingsExceptionHandler()).build();
        mvc.perform(put("/api/settings/tenant/chat.retry.maxAttempts").contentType(MediaType.APPLICATION_JSON).content("{\"value\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_CONFLICT"))
                .andExpect(jsonPath("$.key").value("chat.retry.maxAttempts"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        // a reset has something to reset only once a value is stored; seed the current row directly
        SettingsStore failing = new AlwaysDuplicate();
        failing.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "chat.retry.maxAttempts", 3, 1, "x", java.time.Instant.now()));
        SettingsService service2 = serviceOver(failing);
        MockMvc mvc2 = MockMvcBuilders.standaloneSetup(new SettingsController(service2)).setControllerAdvice(new SettingsExceptionHandler()).build();
        mvc2.perform(delete("/api/settings/tenant/chat.retry.maxAttempts"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_CONFLICT"));
    }

    @Test
    void theInMemoryStoreRefusesAnotherRowOfTheSameVersionLikeTheRealTable() {
        InMemorySettingsStore store = new InMemorySettingsStore();
        SettingHistoryEntry e = new SettingHistoryEntry(0, SettingScope.TENANT, "", "k", 1, "SET", 1, null, null, "a", java.time.Instant.now());
        store.appendHistory(e);
        assertThatThrownBy(() -> store.appendHistory(e)).isInstanceOf(DuplicateKeyException.class);
        store.appendHistory(new SettingHistoryEntry(0, SettingScope.AGENT, "x", "k", 1, "SET", 1, null, null, "a", java.time.Instant.now()));
    }

    @Test
    void theExceptionHandlerDocumentsBothConflictCodes() {
        assertThat(SettingsExceptionHandler.class.getDeclaredMethods())
                .extracting(m -> m.getParameterTypes().length == 1 ? m.getParameterTypes()[0] : null)
                .contains(SettingLockedException.class, SettingConflictException.class);
    }
}
