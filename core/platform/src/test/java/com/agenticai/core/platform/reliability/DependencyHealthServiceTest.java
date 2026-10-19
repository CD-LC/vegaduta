package com.agenticai.core.platform.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.platform.reliability.DependencyProbes.Broker;
import com.agenticai.core.platform.reliability.ProbeRunner.Outcome;
import com.agenticai.core.platform.reliability.ReliabilityApi.DependenciesResponse;
import com.agenticai.core.platform.reliability.ReliabilityApi.DependencyEntry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Roll-up, caching, the disabled switch and the small pure decisions of the dependency view. */
class DependencyHealthServiceTest {

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T10:00:00Z");

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static DependencyEntry entry(String name, DependencyStatus status) {
        return new DependencyEntry(name, "x", status, 5L, Instant.EPOCH, "d");
    }

    private DependencyHealthService service(
            boolean enabled,
            long ttl,
            MutableClock clock,
            List<Supplier<List<DependencyEntry>>> stages) {
        return new DependencyHealthService(enabled, ttl, clock, executor, stages);
    }

    // ---------------------------------------------------------------- roll-up

    @Test
    void postgresDownIsDownEverywhere() {
        assertThat(
                        DependencyHealthService.rollup(
                                List.of(
                                        entry("redis", DependencyStatus.UP),
                                        entry("postgres", DependencyStatus.DOWN))))
                .isEqualTo(DependencyStatus.DOWN);
    }

    @Test
    void anyOtherDownOrDegradedIsDegraded() {
        assertThat(
                        DependencyHealthService.rollup(
                                List.of(
                                        entry("postgres", DependencyStatus.UP),
                                        entry("redis", DependencyStatus.DOWN))))
                .isEqualTo(DependencyStatus.DEGRADED);
        assertThat(
                        DependencyHealthService.rollup(
                                List.of(
                                        entry("postgres", DependencyStatus.DEGRADED),
                                        entry("redis", DependencyStatus.UP))))
                .isEqualTo(DependencyStatus.DEGRADED);
    }

    @Test
    void unknownEntriesNeverDragTheVerdictDown() {
        assertThat(
                        DependencyHealthService.rollup(
                                List.of(
                                        entry("postgres", DependencyStatus.UP),
                                        entry("kafka", DependencyStatus.UNKNOWN))))
                .isEqualTo(DependencyStatus.UP);
        assertThat(
                        DependencyHealthService.rollup(
                                List.of(entry("kafka", DependencyStatus.UNKNOWN))))
                .isEqualTo(DependencyStatus.UNKNOWN);
        assertThat(DependencyHealthService.rollup(List.of())).isEqualTo(DependencyStatus.UNKNOWN);
    }

    // ---------------------------------------------------------------- pure decisions

    @Test
    void poolPressureIsDegradedBeforeItIsAnOutage() {
        assertThat(DependencyProbes.poolStatus(3, 15, 0)).isEqualTo(DependencyStatus.UP);
        assertThat(DependencyProbes.poolStatus(14, 15, 0))
                .isEqualTo(DependencyStatus.DEGRADED);
        assertThat(DependencyProbes.poolStatus(5, 15, 2))
                .isEqualTo(DependencyStatus.DEGRADED);
        assertThat(DependencyProbes.poolStatus(0, 0, 0)).isEqualTo(DependencyStatus.UP);
    }

    @Test
    void firstBrokerParsesCommonBootstrapForms() {
        assertThat(DependencyProbes.firstBroker("kafka:9092"))
                .isEqualTo(new Broker("kafka", 9092));
        assertThat(DependencyProbes.firstBroker("PLAINTEXT://k1:9093,k2:9093"))
                .isEqualTo(new Broker("k1", 9093));
        assertThat(DependencyProbes.firstBroker("broker"))
                .isEqualTo(new Broker("broker", 9092));
        assertThat(DependencyProbes.firstBroker("")).isNull();
        assertThat(DependencyProbes.firstBroker(null)).isNull();
        assertThat(DependencyProbes.firstBroker("host:notaport")).isNull();
        assertThat(DependencyProbes.firstBroker(":9092")).isNull();
    }

    @Test
    void httpUriAcceptsOnlyHttpAndHttps() {
        assertThat(DependencyProbes.httpUri("https://api.example.com/v1"))
                .isEqualTo(URI.create("https://api.example.com/v1"));
        assertThat(DependencyProbes.httpUri(" http://localhost:8001 ")).isNotNull();
        assertThat(DependencyProbes.httpUri("file:///etc/passwd")).isNull();
        assertThat(DependencyProbes.httpUri("not a url")).isNull();
        assertThat(DependencyProbes.httpUri("ftp://x.example.com")).isNull();
        assertThat(DependencyProbes.httpUri("https:///nohost")).isNull();
    }

    @Test
    void metaCredentialsGroupAppIdAndSecretTogether() {
        assertThat(DependencyHealthService.metaOutcome(false, false, false).status())
                .isEqualTo(DependencyStatus.UNKNOWN);
        assertThat(DependencyHealthService.metaOutcome(true, false, false).status())
                .isEqualTo(DependencyStatus.UP);
        assertThat(DependencyHealthService.metaOutcome(false, true, true).status())
                .isEqualTo(DependencyStatus.UP);
        assertThat(DependencyHealthService.metaOutcome(true, true, true).detail())
                .contains("webhook verify token")
                .contains("embedded-signup app credentials")
                .contains("not live-checked");
        Outcome broken = DependencyHealthService.metaOutcome(true, true, false);
        assertThat(broken.status()).isEqualTo(DependencyStatus.DEGRADED);
        assertThat(DependencyHealthService.metaOutcome(true, false, true).status())
                .isEqualTo(DependencyStatus.DEGRADED);
    }

    // ---------------------------------------------------------------- service behaviour

    @Test
    void theResultIsCachedForTheTtlSoCallersCannotHammerDependencies() {
        MutableClock clock = new MutableClock();
        AtomicInteger probes = new AtomicInteger();
        DependencyHealthService s =
                service(
                        true,
                        30,
                        clock,
                        List.of(
                                () -> {
                                    probes.incrementAndGet();
                                    return List.of(entry("postgres", DependencyStatus.UP));
                                }));

        DependenciesResponse first = s.dependencies();
        for (int i = 0; i < 20; i++) {
            assertThat(s.dependencies()).isSameAs(first);
        }
        assertThat(probes.get()).isEqualTo(1);

        clock.advanceSeconds(29);
        assertThat(s.dependencies()).isSameAs(first);
        assertThat(probes.get()).isEqualTo(1);

        clock.advanceSeconds(2); // 31 s after the first probe
        DependenciesResponse second = s.dependencies();
        assertThat(second).isNotSameAs(first);
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    void aResponseCarriesTheRollupTheEntriesAndHonestNotes() {
        MutableClock clock = new MutableClock();
        DependencyHealthService s =
                service(
                        true,
                        30,
                        clock,
                        List.of(
                                () ->
                                        List.of(
                                                entry("postgres", DependencyStatus.UP),
                                                entry("redis", DependencyStatus.DOWN)),
                                () -> List.of(entry("kafka", DependencyStatus.UNKNOWN))));

        DependenciesResponse r = s.dependencies();

        assertThat(r.overall()).isEqualTo(DependencyStatus.DEGRADED);
        assertThat(r.dependencies())
                .extracting(DependencyEntry::name)
                .containsExactly("postgres", "redis", "kafka");
        assertThat(r.cacheTtlSeconds()).isEqualTo(30);
        assertThat(r.generatedAt()).isEqualTo(clock.instant());
        assertThat(r.notes())
                .anyMatch(
                        n ->
                                n.contains("no completion is requested")
                                        || n.contains("No credential is sent"));
        assertThat(r.notes()).anyMatch(n -> n.contains("UNKNOWN means not configured"));
    }

    @Test
    void disabledMeansNotFound() {
        DependencyHealthService s = service(false, 30, new MutableClock(), List.of());
        assertThatThrownBy(s::dependencies)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void aFailingStageReadsUnknownKeepsTheOtherStagesAndIsCachedBrieflyOnly() {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        DependencyHealthService s =
                service(
                        true,
                        30,
                        clock,
                        List.of(
                                () -> {
                                    if (calls.incrementAndGet() == 1) {
                                        throw new IllegalStateException("stage blew up");
                                    }
                                    return List.of(entry("redis", DependencyStatus.UP));
                                },
                                () -> List.of(entry("postgres", DependencyStatus.UP))));

        DependenciesResponse failed = s.dependencies();
        assertThat(failed.dependencies())
                .extracting(DependencyEntry::name)
                .containsExactly("probe-stage-1", "postgres");
        assertThat(failed.dependencies().get(0).status()).isEqualTo(DependencyStatus.UNKNOWN);
        assertThat(failed.dependencies().get(1).status()).isEqualTo(DependencyStatus.UP);
        assertThat(failed.overall()).isEqualTo(DependencyStatus.UP);
        assertThat(failed.notes()).anyMatch(n -> n.contains("could not be run"));

        // a persistent fault is not re-probed by every caller: the failing round is cached for a
        // few seconds
        clock.advanceSeconds(4);
        assertThat(s.dependencies()).isSameAs(failed);
        assertThat(calls.get()).isEqualTo(1);

        clock.advanceSeconds(
                2); // 6 s after the failed round, beyond the short failure TTL, far inside the 30 s
        // TTL
        DependenciesResponse recovered = s.dependencies();
        assertThat(recovered).isNotSameAs(failed);
        assertThat(recovered.dependencies())
                .extracting(DependencyEntry::name)
                .containsExactly("redis", "postgres");
        assertThat(calls.get()).isEqualTo(2);

        clock.advanceSeconds(20); // a healthy round still gets the full TTL
        assertThat(s.dependencies()).isSameAs(recovered);
    }

    @Test
    void anExhaustedPoolReadsDegradedWithoutBorrowingAConnection() {
        Outcome o = DependencyProbes.exhaustedPoolOutcome(15, 15, 3);
        assertThat(o).isNotNull();
        assertThat(o.status()).isEqualTo(DependencyStatus.DEGRADED);
        assertThat(o.detail()).contains("15/15").contains("3 waiting");
        // room left, counters unavailable, or threads waiting while the pool has room: fall through
        // to SELECT 1
        assertThat(DependencyProbes.exhaustedPoolOutcome(14, 15, 0)).isNull();
        assertThat(DependencyProbes.exhaustedPoolOutcome(5, 15, 2)).isNull();
        assertThat(DependencyProbes.exhaustedPoolOutcome(-1, -1, -1)).isNull();
        assertThat(DependencyProbes.exhaustedPoolOutcome(0, 0, 0)).isNull();
    }

    // ---------------------------------------------------------------- ops-console probes
    // (2026-10-09)

    @Test
    void healthOutcomeMapsStatusCodes() {
        assertThat(DependencyProbes.healthOutcome(200).status())
                .isEqualTo(DependencyStatus.UP);
        assertThat(DependencyProbes.healthOutcome(404).status())
                .isEqualTo(DependencyStatus.DEGRADED);
        assertThat(DependencyProbes.healthOutcome(503).status())
                .isEqualTo(DependencyStatus.DOWN);
    }

    @Test
    void storageOutcomeTreatsAnsweringNonMinioStoresAsReachable() {
        assertThat(DependencyProbes.storageOutcome(200).status())
                .isEqualTo(DependencyStatus.UP);
        Outcome r2 = DependencyProbes.storageOutcome(403);
        assertThat(r2.status()).isEqualTo(DependencyStatus.UP);
        assertThat(r2.detail()).contains("credentials not exercised");
        assertThat(DependencyProbes.storageOutcome(500).status())
                .isEqualTo(DependencyStatus.DOWN);
    }

    @Test
    void hostPortUsesTheGivenDefaultPort() {
        assertThat(DependencyProbes.hostPort("temporal", 7233))
                .isEqualTo(new Broker("temporal", 7233));
        assertThat(DependencyProbes.hostPort("10.0.0.4:7233", 7233))
                .isEqualTo(new Broker("10.0.0.4", 7233));
        assertThat(DependencyProbes.hostPort("", 7233)).isNull();
        assertThat(DependencyProbes.firstBroker("kafka"))
                .isEqualTo(new Broker("kafka", 9092));
    }
}
