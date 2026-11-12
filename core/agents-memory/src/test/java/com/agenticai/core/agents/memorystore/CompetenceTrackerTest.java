package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import com.agenticai.core.agents.memory.spi.VerifiedOutcome.Signal;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class CompetenceTrackerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);

    private final UUID agent = UUID.randomUUID();
    private final CompetenceProperties props = new CompetenceProperties();
    private final InMemoryCompetenceStorage storage = new InMemoryCompetenceStorage();
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private CompetenceTracker tracker;

    private CompetenceTracker build() {
        tracker =
                new CompetenceTracker(
                        props,
                        new CompetenceGate(props, beans.getBeanProvider(SettingsResolver.class)),
                        storage,
                        beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                        CLOCK);
        return tracker;
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void on() {
        props.setEnabled(true);
        build();
    }

    @Test
    void flagOffIsANoOpAndReadsNothing() {
        build(); // default: off
        tracker.record(new VerifiedOutcome(agent, "chat", Signal.TOOL_SUCCESS));
        assertThat(storage.rows(agent)).isEmpty();
        props.setEnabled(true);
        tracker.record(new VerifiedOutcome(agent, "chat", Signal.TOOL_SUCCESS));
        props.setEnabled(false);
        assertThat(tracker.rows(agent)).isEmpty();
    }

    @Test
    void eachSignalIncrementsItsOwnColumnAndUnknownIsNeverSuccess() {
        on();
        for (Signal s : Signal.values()) {
            tracker.record(new VerifiedOutcome(agent, "refund", s));
        }
        tracker.record(new VerifiedOutcome(agent, "refund", Signal.UNKNOWN));
        CompetenceRow r = storage.rows(agent).get(0);
        assertThat(r.attempts()).isEqualTo(8);
        assertThat(r.verifiedSuccess()).isEqualTo(2); // tool success + approval granted
        assertThat(r.verifiedFailure()).isEqualTo(1);
        assertThat(r.humanAccepted()).isEqualTo(1);
        assertThat(r.humanRejected()).isEqualTo(2); // approval denied + rejected
        assertThat(r.unknownOutcome()).isEqualTo(2);
        assertThat(r.successes() + r.failures() + r.unknownOutcome()).isEqualTo(r.attempts());
        assertThat(r.lastOutcomeAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void taskClassCardinalityIsCappedWithAnOverflowBucket() {
        props.setMaxTaskClassesPerAgent(3);
        on();
        for (int i = 0; i < 10; i++) {
            tracker.record(new VerifiedOutcome(agent, "class_" + i, Signal.TOOL_SUCCESS));
        }
        // a known class keeps counting after the cap is reached
        tracker.record(new VerifiedOutcome(agent, "class_0", Signal.TOOL_ERROR));
        List<CompetenceRow> rows = storage.rows(agent);
        assertThat(rows).hasSize(4);
        assertThat(rows)
                .extracting(CompetenceRow::taskClass)
                .contains("class_0", "class_1", "class_2", "_overflow");
        assertThat(
                        rows.stream()
                                .filter(r -> r.taskClass().equals("_overflow"))
                                .findFirst()
                                .orElseThrow()
                                .attempts())
                .isEqualTo(7);
        assertThat(
                        rows.stream()
                                .filter(r -> r.taskClass().equals("class_0"))
                                .findFirst()
                                .orElseThrow()
                                .attempts())
                .isEqualTo(2);
        // another agent has its own budget
        UUID other = UUID.randomUUID();
        tracker.record(new VerifiedOutcome(other, "class_9", Signal.TOOL_SUCCESS));
        assertThat(storage.rows(other))
                .extracting(CompetenceRow::taskClass)
                .containsExactly("class_9");
    }

    @Test
    void concurrentIncrementsAreNotLost() throws Exception {
        on();
        int threads = 16;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(
                    pool.submit(
                            () -> {
                                go.await();
                                for (int i = 0; i < perThread; i++) {
                                    tracker.record(
                                            new VerifiedOutcome(
                                                    agent,
                                                    "hot",
                                                    i % 2 == 0
                                                            ? Signal.TOOL_SUCCESS
                                                            : Signal.TOOL_ERROR));
                                }
                                return null;
                            }));
        }
        go.countDown();
        for (Future<?> f : done) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        CompetenceRow r = storage.rows(agent).get(0);
        assertThat(r.attempts()).isEqualTo(threads * perThread);
        assertThat(r.verifiedSuccess()).isEqualTo(threads * perThread / 2);
        assertThat(r.verifiedFailure()).isEqualTo(threads * perThread / 2);
    }

    @Test
    void aFailingStoreNeverThrowsIntoTheCaller() {
        on();
        storage.failing = true;
        tracker.record(new VerifiedOutcome(agent, "chat", Signal.TOOL_SUCCESS));
        assertThat(tracker.rows(agent)).isEmpty();
        tracker.record(null);
    }

    @Test
    void aTenantOrAgentSettingCanSwitchItOffButNeverOn() {
        CompetenceProperties platform = new CompetenceProperties();
        platform.setEnabled(true);
        InMemorySettingsStore store = new InMemorySettingsStore();
        SettingsProperties noCache = new SettingsProperties();
        noCache.setCacheTtl(Duration.ZERO);
        SettingsResolver resolver =
                new DefaultSettingsResolver(
                        new SettingsRegistry(List.of(new CompetenceSettings(platform))),
                        store,
                        List.of(),
                        noCache,
                        null);
        beans.addBean("resolver", resolver);
        TenantContext.set(TenantContext.of("tenant-a", "u", Set.of()));
        CompetenceGate gate =
                new CompetenceGate(platform, beans.getBeanProvider(SettingsResolver.class));
        assertThat(gate.enabled(agent)).isTrue();

        store.saveCurrent(
                new SettingOverride(
                        SettingScope.AGENT,
                        agent.toString(),
                        CompetenceSettings.ENABLED_KEY,
                        false,
                        1,
                        "admin",
                        Instant.now()));
        assertThat(gate.enabled(agent)).isFalse();
        assertThat(gate.enabled(UUID.randomUUID())).isTrue();

        store.saveCurrent(
                new SettingOverride(
                        SettingScope.TENANT,
                        "",
                        CompetenceSettings.ENABLED_KEY,
                        false,
                        1,
                        "admin",
                        Instant.now()));
        assertThat(gate.enabled(UUID.randomUUID())).isFalse();

        platform.setEnabled(false);
        assertThat(gate.enabled(agent)).isFalse();
        assertThat(new CompetenceSettings(platform).definitions().get(0).platformValue())
                .isEqualTo(false);
    }

    @Test
    void recordedOutcomesAreCountedInMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        beans.addBean("meters", meters);
        on();
        tracker.record(new VerifiedOutcome(agent, "chat", Signal.TOOL_SUCCESS));
        storage.failing = true;
        tracker.record(new VerifiedOutcome(agent, "chat", Signal.TOOL_SUCCESS));
        assertThat(
                        meters.find("memory.competence.outcome")
                                .tag("result", "recorded")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        meters.find("memory.competence.outcome")
                                .tag("result", "failed")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }
}
