package com.agenticai.core.quality.memeval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.quality.memeval.CaseEnvironment.CaseIdentity;
import com.agenticai.core.quality.memeval.MemoryRun.ProbeOutcome;
import com.agenticai.core.quality.memeval.MemoryRun.StoreItem;
import com.agenticai.core.quality.memeval.MemoryRun.TurnCost;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.Session;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Context;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Status;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Verdict;
import com.agenticai.core.quality.memeval.MemoryScorer.ScenarioScore;
import com.agenticai.core.quality.stats.NoiseFloor;
import com.agenticai.core.quality.stats.NoiseFloorEstimator;
import com.agenticai.core.quality.stats.PairedCaseOutcomes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The extension points a platform runner needs (fresh identity, clock, switches, recall trace carrier)
 * and the optional noise-floor input of G1. Scorecard doc Addendum D.
 */
class MemoryEvalSeamsTest {

    private static final long SEED = 20261104L;
    private static List<MemoryScenario> scenarios;

    @BeforeAll
    static void load() {
        scenarios = MemoryScenarioLoader.loadAll();
    }

    /** Wraps the reference subject and records what the runner hands to {@code start} and when. */
    private static final class Recording implements MemoryEvalSubject {
        final List<CaseEnvironment> envs = new ArrayList<>();
        final List<Long> daysAtSession = new ArrayList<>();
        final MemoryEvalSubject inner = InMemoryReferenceSubject.good();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public Execution start(MemoryScenario scenario, MemoryArm arm, int repeat, long seed) {
            throw new AssertionError("the runner must use the environment overload");
        }

        @Override
        public Execution start(MemoryScenario scenario, MemoryArm arm, int repeat, long seed, CaseEnvironment env) {
            envs.add(env);
            Execution real = inner.start(scenario, arm, repeat, seed);
            return new Execution() {
                @Override
                public List<TurnCost> playSession(Session session) {
                    daysAtSession.add(env.clock().elapsedDays());
                    return real.playSession(session);
                }

                @Override
                public ProbeOutcome ask(Probe probe) {
                    return real.ask(probe);
                }

                @Override
                public List<StoreItem> store() {
                    return real.store();
                }
            };
        }
    }

    @Test
    void runnerGivesEveryExecutionAFreshIdentityAClockAndTheArmsSwitches() {
        Recording subject = new Recording();
        MemoryScenario s3 = scenarios.stream().filter(s -> s.id().equals("s03-knowledge-update")).findFirst().orElseThrow();
        List<MemoryRun> runs = MemoryEvalRunner.runAll(
            subject, List.of(s3), List.of(MemoryArm.B, MemoryArm.M, MemoryArm.ORACLE), 2, SEED);
        assertThat(runs).hasSize(6);
        assertThat(subject.envs).hasSize(6);
        Set<String> keys = new HashSet<>();
        subject.envs.forEach(e -> keys.add(e.identity().runKey()));
        assertThat(keys).as("no two executions share a tenant or user").hasSize(6);
        assertThat(subject.envs.get(0).switches()).isEqualTo(new CaseEnvironment.MemorySwitches(false, false)); // B
        assertThat(subject.envs.get(1).switches()).isEqualTo(new CaseEnvironment.MemorySwitches(true, true)); // M
        assertThat(subject.envs.get(2).switches()).isEqualTo(new CaseEnvironment.MemorySwitches(false, false)); // ORACLE
        // Each session sees the clock already wound by its gap: 0, 7, 90 days cumulative = 0, 7, 97.
        assertThat(subject.daysAtSession.subList(0, 3)).containsExactly(0L, (long) s3.dayOf(1), (long) s3.dayOf(2));
    }

    @Test
    void identitiesMapLogicalIdsToPhysicalAndBack() {
        CaseIdentity id = CaseIdentity.of("s14-cross-user-isolation", MemoryArm.M, 0);
        assertThat(id).isNotEqualTo(CaseIdentity.of("s14-cross-user-isolation", MemoryArm.M, 1));
        assertThat(id).isNotEqualTo(CaseIdentity.of("s14-cross-user-isolation", MemoryArm.B, 0));
        String physical = id.userId("uA");
        assertThat(physical).startsWith(id.runKey()).isNotEqualTo("uA");
        assertThat(id.logicalUser(physical)).isEqualTo("uA");
        assertThat(id.logicalTenant(id.tenantId("t1"))).isEqualTo("t1");
        // A row of some other execution keeps its foreign id, so it still compares unequal to the asker.
        String foreign = CaseIdentity.of("other", MemoryArm.M, 0).userId("uA");
        assertThat(id.logicalUser(foreign)).isEqualTo(foreign).isNotEqualTo("uA");
    }

    @Test
    void evalClockOnlyMovesForwardAndIsUtc() {
        EvalClock clock = new EvalClock();
        assertThat(clock.instant()).isEqualTo(EvalClock.START);
        clock.advanceDays(14);
        assertThat(clock.elapsedDays()).isEqualTo(14);
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
        assertThatThrownBy(() -> clock.advanceDays(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformSubjectSkeletonRunsThroughTheRunnerAndRefusesTheBareEntryPoint() {
        List<String> calls = new ArrayList<>();
        PlatformMemoryEvalSubject stub = new PlatformMemoryEvalSubject() {
            @Override
            public String name() {
                return "stub-platform";
            }

            @Override
            protected void provision(MemoryScenario scenario, MemoryArm arm, CaseEnvironment env) {
                calls.add("provision " + arm);
            }

            @Override
            protected List<TurnCost> replaySession(CaseEnvironment env, Session session) {
                calls.add("session " + session.id());
                return List.of();
            }

            @Override
            protected ProbeOutcome askInFreshThread(CaseEnvironment env, Probe probe) {
                calls.add("ask " + probe.id());
                return new ProbeOutcome(probe.id(), "I don't know", List.of(), List.of(), List.of(), null);
            }

            @Override
            protected List<StoreItem> readStore(CaseEnvironment env) {
                return List.of();
            }
        };
        MemoryScenario s1 = scenarios.stream().filter(s -> s.id().equals("s01-basic-recall")).findFirst().orElseThrow();
        MemoryEvalRunner.run(stub, s1, MemoryArm.M, 0, 1);
        assertThat(calls).containsExactly("provision M", "session s1", "session s2", "ask p1");
        assertThatThrownBy(() -> stub.start(s1, MemoryArm.M, 0, 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- optional noise floor input ----

    private static List<ScenarioScore> score(MemoryEvalSubject subject, List<MemoryArm> arms, long seed) {
        List<ScenarioScore> out = new ArrayList<>();
        for (MemoryRun r : MemoryEvalRunner.runAll(subject, scenarios, arms, 3, seed)) {
            MemoryScenario s = scenarios.stream().filter(x -> x.id().equals(r.scenarioId())).findFirst().orElseThrow();
            out.add(MemoryScorer.score(s, r));
        }
        return out;
    }

    @Test
    void noiseFloorFromTwoIdenticalBaselineRunsOfADeterministicSubjectIsZeroAndNotSuspect() {
        MemoryEvalSubject good = InMemoryReferenceSubject.good();
        NoiseFloor floor = MemoryScorecards.noiseFloor(
            score(good, List.of(MemoryArm.B), SEED), score(good, List.of(MemoryArm.B), SEED + 1), SEED);
        assertThat(floor).isNotNull();
        assertThat(floor.floor()).isZero();
        assertThat(floor.configurationSuspect()).isFalse();
        assertThat(floor.pairedCases()).isGreaterThan(50);
    }

    @Test
    void noiseFloorIsUnknownNotZeroWhenThereAreNoPairedCases() {
        assertThat(MemoryScorecards.noiseFloor(List.of(), List.of(), SEED)).isNull();
    }

    @Test
    void cliCanMeasureTheFloorAndRecordsItInTheReport() throws Exception {
        MemoryEvalCli.Result r = MemoryEvalCli.run(
            InMemoryReferenceSubject.good(), scenarios, 3, SEED, Context.ci(), true);
        assertThat(r.noiseFloor()).isNotNull();
        assertThat(r.report().verdict()).isEqualTo(Verdict.SHIP);
        JsonNode j = new ObjectMapper().readTree(MemoryEvalCli.toJson(r, Instant.EPOCH));
        assertThat(j.get("noiseFloor").get("floor").asDouble()).isZero();
        // and the default path stays floor-less
        assertThat(MemoryEvalCli.run(InMemoryReferenceSubject.good(), scenarios, 3, SEED, Context.ci()).noiseFloor()).isNull();
    }

    @Test
    void aNoiseFloorLargerThanTheGainStopsG1FromPassing() {
        List<ScenarioScore> scores = score(InMemoryReferenceSubject.good(),
            List.of(MemoryArm.B, MemoryArm.M, MemoryArm.ORACLE), SEED);
        assertThat(MemoryScorecardGate.evaluate(MemoryScorecards.build("good", scores, SEED), Context.ci())
            .status("G1")).isEqualTo(Status.PASS);

        // A rerun that disagrees with itself on every case: the metric cannot resolve anything smaller than ~1.0.
        List<PairedCaseOutcomes> noisy = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            noisy.add(new PairedCaseOutcomes("c" + i, List.of(i % 2 == 0 ? 1.0 : 0.0), List.of(i % 2 == 0 ? 0.0 : 1.0)));
        }
        NoiseFloor loud = NoiseFloorEstimator.estimate(noisy, SEED);
        assertThat(loud.floor()).isGreaterThan(0.9);
        var report = MemoryScorecardGate.evaluate(
            MemoryScorecards.build("good", scores, MemoryScorecards.TokenPrices.DEFAULT, loud, SEED), Context.ci());
        assertThat(report.status("G1")).isNotEqualTo(Status.PASS);
        assertThat(report.gate("G1").detail()).contains("noise floor");
        assertThat(report.verdict()).isNotEqualTo(Verdict.SHIP);

        // A floor well below the +1.0 gain does not block it: the floor only stops gains it cannot resolve.
        NoiseFloor quiet = new NoiseFloor(40, 40, 40, 0.1, 0.3, null, 0.3, 0.0, null, false);
        assertThat(MemoryScorecardGate.evaluate(
            MemoryScorecards.build("good", scores, MemoryScorecards.TokenPrices.DEFAULT, quiet, SEED), Context.ci())
            .status("G1")).isEqualTo(Status.PASS);
    }
}
