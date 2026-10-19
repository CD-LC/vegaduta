package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Runs "LongMemEval-lite (own fixtures)" ({@link MemoryEval}) against the in-memory temporal graph,
 * always (no database needed). The same eval runs on real Apache AGE in {@code GraphMemoryRealAgeTest}
 * when {@code MEMEVAL_PG_URL} is set. Not the public LongMemEval benchmark.
 */
class MemoryEvalHarnessTest {

    @Test
    void fixtureCoversEveryAbilityCategoryWithAtLeastFortyCases() {
        MemoryEval.Fixture f = MemoryEval.loadFixture();
        assertThat(f.name()).isEqualTo("LongMemEval-lite (own fixtures)");
        assertThat(f.cases().size()).isGreaterThanOrEqualTo(40);
        Map<String, Long> perCategory = f.cases().stream()
            .collect(Collectors.groupingBy(MemoryEval.EvalCase::category, Collectors.counting()));
        assertThat(perCategory.keySet()).containsExactlyInAnyOrder(
            "single_session_recall", "multi_session_aggregation", "knowledge_update", "temporal_reasoning", "abstention");
        assertThat(perCategory.values()).allSatisfy(n -> assertThat(n).isGreaterThanOrEqualTo(8L));
        Set<String> ids = new HashSet<>();
        for (MemoryEval.EvalCase c : f.cases()) {
            assertThat(ids.add(c.id())).as("unique id " + c.id()).isTrue();
            MemoryEval.Question q = c.question();
            if (!q.abstain()) {
                assertThat(q.expected()).as(c.id() + " expected").isNotEmpty();
            }
            if (q.asOfSession() != null) {
                assertThat(c.sessions()).extracting(MemoryEval.Session::id).contains(q.asOfSession());
            }
        }
    }

    @Test
    void inMemoryEvalOffVersusOn() throws Exception {
        MemoryEval.Fixture f = MemoryEval.loadFixture();
        Map<MemoryEval.Mode, List<MemoryEval.CaseResult>> all =
            MemoryEval.runAll(f, new InMemoryTemporalGraphMemory(), new MemoryEval.ExtractiveAnswerer(), "inmem");
        String report = MemoryEval.report("in-memory temporal graph", all);
        System.out.println(report);
        Path out = Path.of("target", "memeval-report-inmemory.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);

        List<MemoryEval.CaseResult> off = all.get(MemoryEval.Mode.OFF);
        List<MemoryEval.CaseResult> ideal = all.get(MemoryEval.Mode.ON_IDEAL);
        List<MemoryEval.CaseResult> naive = all.get(MemoryEval.Mode.ON_NAIVE);

        // The point of arbitration: stale values stop answering "latest value wins" questions.
        assertThat(MemoryEval.accuracy(ideal, "knowledge_update")).isEqualTo(1.0);
        assertThat(MemoryEval.accuracy(off, "knowledge_update")).isLessThan(MemoryEval.accuracy(ideal, "knowledge_update"));
        assertThat(MemoryEval.staleLeakRate(ideal)).isZero();
        assertThat(MemoryEval.staleLeakRate(off)).isGreaterThan(0.0);
        assertThat(MemoryEval.accuracy(ideal, null)).isGreaterThan(MemoryEval.accuracy(off, null));
        // ...and the hazard: an arbitrator that overwrites multi-valued facts loses aggregation answers.
        assertThat(MemoryEval.accuracy(naive, "multi_session_aggregation"))
            .isLessThan(MemoryEval.accuracy(ideal, "multi_session_aggregation"));
        // Abstention is not hurt by either path.
        for (List<MemoryEval.CaseResult> mode : List.of(off, ideal, naive)) {
            assertThat(MemoryEval.hallucinationRate(mode)).isZero();
        }
    }

    /** Regression: the DISCARD guard normalised with the Java text-block escape {@code \s} (a plain space), not the regex class. */
    @Test
    void discardTreatsTabsAndNewlinesAsWhitespaceWhenVerifyingARedundantFact() {
        InMemoryTemporalGraphMemory store = new InMemoryTemporalGraphMemory();
        store.remember(MemoryEval.AGENT, "k", List.of(new GraphTriple("User", "LIVES_IN", "Pune\tIndia")));
        MemoryWriteArbitrationService arbitration = new MemoryWriteArbitrationService();
        arbitration.setEnabled(true);
        String discard = "[{\"subject\":\"User\",\"relation\":\"LIVES_IN\",\"object\":\"Pune India\",\"decision\":\"DISCARD\"}]";

        arbitration.arbitrateAndWrite(ScriptedChats.fixed(discard), store, MemoryEval.AGENT, "k",
            List.of(new GraphTriple("User", "LIVES_IN", "Pune India")));

        assertThat(store.dump("k")).hasSize(1);
    }

    @Test
    void theAnswererIsPluggable() {
        MemoryEval.Answerer neverAnswers = (q, facts) -> new MemoryEval.Answer(List.of(), true);
        List<MemoryEval.CaseResult> results = MemoryEval.run(
            MemoryEval.loadFixture(), MemoryEval.Mode.OFF, new InMemoryTemporalGraphMemory(), neverAnswers, "pluggable");
        assertThat(MemoryEval.accuracy(results, "abstention")).isEqualTo(1.0);
        assertThat(MemoryEval.accuracy(results, "knowledge_update")).isZero();
    }
}
