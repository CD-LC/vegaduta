package com.agenticai.core.quality.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Schema validation of {@code edge-faq-dataset.json} - the FAQ-traffic suite
 * for the self-hosted/on-device path ({@code EVAL_BACKEND=openai
 * EVAL_BASE_URL=http://localhost:11434 EVAL_DATASET=edge-faq-dataset.json}).
 * Same posture as {@link GoldenDatasetTest}: network-free, loads through
 * {@link EvalRunner#loadDataset(String)} - the exact code path a real run
 * uses - so a malformed edit fails at {@code mvn test} time instead of
 * mid-run against a live Ollama.
 *
 * <p>Unlike the golden taxonomy, every case here IS {@code faq-traffic} -
 * the dataset exists to answer one question ("is a small model good enough
 * for grounded FAQ replies"), so category variety would dilute the signal
 * rather than add coverage.
 */
class EdgeFaqDatasetTest {

    private static List<EvalCase> cases;

    @BeforeAll
    static void loadOnce() throws IOException {
        cases = EvalRunner.loadDataset("edge-faq-dataset.json");
    }

    @Test
    void datasetIsMeaningfullyBroad() {
        // 12+ cases: enough to say something about pass rate and p50 latency
        // on a small model without turning a local run into a chore.
        assertThat(cases).hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    void idsAreUniqueAndStable() {
        Set<String> seen = new HashSet<>();
        for (EvalCase c : cases) {
            assertThat(c.id()).as("case id").isNotBlank();
            assertThat(seen.add(c.id())).as("duplicate case id: %s", c.id()).isTrue();
        }
    }

    @Test
    void everyCaseHasAllGradingFields() {
        for (EvalCase c : cases) {
            assertThat(c.systemPrompt()).as("systemPrompt of %s", c.id()).isNotBlank();
            assertThat(c.userMessage()).as("userMessage of %s", c.id()).isNotBlank();
            assertThat(c.rubric()).as("rubric of %s", c.id()).isNotBlank();
            assertThat(c.categoryOrDefault()).as("category of %s", c.id()).isEqualTo("faq-traffic");
        }
    }

    @Test
    void systemPromptsFitAnEdgeContext() {
        // The whole point is small-context grounding: a 4B-class model on a
        // phone/laptop cannot carry a 20KB prompt and still answer fast. The
        // FAQ block is deliberately compact - a case that needs more is a
        // hosted-tier case and belongs in golden-dataset.json.
        for (EvalCase c : cases) {
            assertThat(c.systemPrompt().length())
                .as("systemPrompt length of %s - edge prompts must stay compact", c.id())
                .isLessThanOrEqualTo(2000);
        }
    }

    @Test
    void datasetIncludesGroundingBoundaryCases() {
        // A FAQ agent's failure mode is answering from general knowledge when
        // the FAQ doesn't cover the question. At least two cases must pin the
        // "say you don't know" boundary (they carry a non-FEASIBLE grading).
        long boundary = cases.stream()
            .filter(c -> c.feasibilityOrDefault().abstentionIsCorrect())
            .count();
        assertThat(boundary).as("out-of-scope grounding cases").isGreaterThanOrEqualTo(2);
    }
}
