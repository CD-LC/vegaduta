package com.agenticai.core.quality.memeval;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.quality.memeval.InMemoryReferenceSubject.Quirk;
import com.agenticai.core.quality.memeval.MemoryEvalCli.Result;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Context;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Status;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End to end: dataset -> reference subject (B, M, ORACLE) -> scorers -> scorecard -> gates. No LLM, no DB. */
class MemoryPipelineTest {

    private static final long SEED = 20261104L;
    private static List<MemoryScenario> scenarios;

    @BeforeAll
    static void load() {
        scenarios = MemoryScenarioLoader.loadAll();
    }

    private static Result run(MemoryEvalSubject subject) {
        return MemoryEvalCli.run(subject, scenarios, 3, SEED, Context.ci());
    }

    private static Result withQuirk(Quirk q) {
        return run(InMemoryReferenceSubject.with("quirk-" + q, q));
    }

    @Test
    void goodOracleSubjectShips() {
        Result r = run(InMemoryReferenceSubject.good());
        r.report().gates().forEach(g -> System.out.println(g.id() + " " + g.status() + " " + g.detail()));
        assertThat(r.report().verdict()).isEqualTo(Verdict.SHIP);
        for (int n = 1; n <= 10; n++) {
            assertThat(r.report().status("G" + n)).as("G" + n).isEqualTo(Status.PASS);
        }
        assertThat(r.scorecard().gain().m1().meanDelta()).isGreaterThan(0.5);
        assertThat(r.scorecard().recall().m2().value()).isEqualTo(1.0);
        assertThat(r.scorecard().poisoning().influenced().hits()).isZero();
    }

    @Test
    void deliberatelyBadSubjectIsNoShipOnTheHardZeroGates() {
        Result r = run(MemoryEvalCli.badSubject());
        assertThat(r.report().verdict()).isEqualTo(Verdict.NO_SHIP);
        assertThat(r.report().status("G7")).isEqualTo(Status.FAIL);
        assertThat(r.report().status("G8")).isEqualTo(Status.FAIL);
        assertThat(r.scorecard().poisoning().influenced().hits()).isPositive();
        assertThat(r.scorecard().isolation().forget().hits()).isPositive();
        assertThat(r.scorecard().isolation().leaks().hits()).isPositive();
    }

    @Test
    void canaryLeakAloneIsNoShip() {
        Result r = run(InMemoryReferenceSubject.with("leaky", Quirk.PERSISTS_ATTACKS, Quirk.OBEYS_INJECTED_RULES));
        assertThat(r.report().status("G7")).isEqualTo(Status.FAIL);
        assertThat(r.report().verdict()).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void persistingWithoutObeyingFailsNothingButIsVisibleAsPersistedStage() {
        Result r = withQuirk(Quirk.PERSISTS_ATTACKS);
        assertThat(r.scorecard().poisoning().persisted().hits()).isPositive();
        assertThat(r.scorecard().poisoning().influenced().hits()).isZero();
        assertThat(r.report().status("G7")).isEqualTo(Status.PASS);
    }

    @Test
    void ignoringForgetIsNoShip() {
        Result r = withQuirk(Quirk.IGNORES_FORGET);
        assertThat(r.report().status("G8")).isEqualTo(Status.FAIL);
        assertThat(r.report().verdict()).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void crossUserAndCrossTenantLeaksAreNoShip() {
        assertThat(withQuirk(Quirk.LEAKS_ACROSS_USERS).report().status("G8")).isEqualTo(Status.FAIL);
        assertThat(withQuirk(Quirk.LEAKS_ACROSS_TENANTS).report().status("G8")).isEqualTo(Status.FAIL);
    }

    @Test
    void keepingStaleFactsFailsStalenessAndContradiction() {
        Result r = withQuirk(Quirk.KEEPS_STALE_FACTS);
        assertThat(r.report().status("G6")).isEqualTo(Status.FAIL);
        assertThat(r.report().status("G5")).isEqualTo(Status.FAIL);
        assertThat(r.report().verdict()).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void inventingAnswersFailsContradictionGate() {
        assertThat(withQuirk(Quirk.INVENTS_ANSWERS).report().status("G5")).isEqualTo(Status.FAIL);
    }

    @Test
    void repeatingMistakesFailsG4() {
        Result r = withQuirk(Quirk.REPEATS_MISTAKES);
        assertThat(r.report().status("G4")).isEqualTo(Status.FAIL);
        assertThat(r.report().verdict()).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void overRecallLowersPrecisionAndAWarningBlocksShip() {
        Result r = withQuirk(Quirk.OVER_RECALLS);
        assertThat(r.scorecard().recall().m3().value()).isLessThan(0.5);
        assertThat(r.report().status("G3")).isIn(Status.WARN, Status.FAIL);
        assertThat(r.report().verdict()).isNotEqualTo(Verdict.SHIP);
    }

    @Test
    void aNetworkHopOnTheHotPathFailsLatency() {
        Result r = withQuirk(Quirk.SLOW_RECALL);
        assertThat(r.report().status("G10")).isEqualTo(Status.FAIL);
        assertThat(r.report().verdict()).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void duplicateWritesOnlyWarnOnGrowth() {
        Result r = withQuirk(Quirk.DUPLICATES_FACTS);
        assertThat(r.report().status("G11")).isEqualTo(Status.WARN);
        assertThat(r.report().verdict()).isEqualTo(Verdict.SHIP);
    }

    @Test
    void pipelineIsDeterministic() {
        String a = MemoryEvalCli.toJson(run(InMemoryReferenceSubject.good()), Instant.EPOCH);
        String b = MemoryEvalCli.toJson(run(InMemoryReferenceSubject.good()), Instant.EPOCH);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void baselineArmNeverInjectsAndOracleNeverLeaks() {
        MemoryScenario s1 = scenarios.stream().filter(s -> s.id().equals("s01-basic-recall")).findFirst().orElseThrow();
        MemoryRun b = MemoryEvalRunner.run(InMemoryReferenceSubject.good(), s1, MemoryArm.B, 0, 1);
        MemoryRun o = MemoryEvalRunner.run(InMemoryReferenceSubject.good(), s1, MemoryArm.ORACLE, 0, 1);
        MemoryRun m = MemoryEvalRunner.run(InMemoryReferenceSubject.good(), s1, MemoryArm.M, 0, 1);
        assertThat(b.outcomes().get(0).injected()).isEmpty();
        assertThat(b.finalStore()).isEmpty();
        assertThat(b.outcomes().get(0).answer()).contains("don't know");
        assertThat(o.outcomes().get(0).answer()).contains("peanut");
        assertThat(m.outcomes().get(0).injected()).extracting(MemoryRun.InjectedItem::factId).containsExactly("f-allergy");
    }

    @Test
    void jsonReportFollowsAddendumA(@TempDir Path dir) throws Exception {
        Result r = run(InMemoryReferenceSubject.good());
        Path out = dir.resolve("memory-eval/memory-scorecard.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, MemoryEvalCli.toJson(r, Instant.parse("2026-11-04T02:00:00Z")));
        JsonNode j = new ObjectMapper().readTree(out.toFile());
        assertThat(j.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(j.get("verdict").asText()).isEqualTo("SHIP");
        assertThat(j.get("generatedAt").asText()).isEqualTo("2026-11-04T02:00:00Z");
        assertThat(j.get("memoryDefaultOn").asBoolean()).isFalse();
        assertThat(j.get("gates")).hasSize(11);
        for (JsonNode g : j.get("gates")) {
            assertThat(g.get("id").asText()).matches("G([1-9]|1[01])");
            assertThat(g.get("status").asText()).isIn("PASS", "FAIL", "WARN", "UNPROVEN");
        }
        assertThat(j.get("scorecard").get("gain").get("m1").get("meanDelta").asDouble()).isPositive();
    }

    @Test
    void writesTheReportsTheGateScriptReads() throws Exception {
        Path dir = Path.of("target", "memory-eval");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("memory-scorecard.json"),
            MemoryEvalCli.toJson(run(InMemoryReferenceSubject.good()), Instant.now()));
        Files.writeString(dir.resolve("memory-scorecard-bad-subject.json"),
            MemoryEvalCli.toJson(run(MemoryEvalCli.badSubject()), Instant.now()));
        assertThat(dir.resolve("memory-scorecard.json")).exists();
    }

    @Test
    void exitCodesMatchTheGateScript() {
        assertThat(MemoryEvalCli.exitCode(run(InMemoryReferenceSubject.good()).report())).isZero();
        assertThat(MemoryEvalCli.exitCode(run(MemoryEvalCli.badSubject()).report())).isEqualTo(1);
    }
}
