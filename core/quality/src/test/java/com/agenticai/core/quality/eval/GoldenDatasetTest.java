package com.agenticai.core.quality.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Schema/taxonomy validation of {@code golden-dataset.json}, run at ordinary
 * {@code mvn test} time with zero network calls. The point: a malformed or
 * off-taxonomy dataset edit should fail the fast, free per-push CI job - not
 * surface for the first time in the nightly billed eval run
 * ({@code .github/workflows/eval.yml}), where a parse failure would burn a
 * scheduled slot and read as a mysterious infra error instead of "your JSON
 * edit was bad."
 *
 * <p>Loads through {@link EvalRunner#loadDataset()} - the exact code path the
 * real run uses - rather than re-reading the file with its own mapper, so the
 * test cannot silently drift from what the runner actually accepts.
 *
 * <p>Honest limitation: this validates structure only. Whether a rubric is
 * actually well-calibrated (narrow, checkable, not noisy for the judge) still
 * requires human review plus watching a few nightly runs - no unit test can
 * grade rubric quality.
 */
class GoldenDatasetTest {

    /**
     * The closed category taxonomy - keep in lockstep with the table in
     * core/quality/eval-harness.md. Adding a new category is deliberately a two-file
     * change (eval-harness.md + here): the doc documents what the bucket MEANS,
     * and an undocumented bucket in the summary report is worse than a
     * failing test.
     */
    private static final Set<String> CATEGORY_TAXONOMY = Set.of(
        "persona-adherence",
        "safety-refusal",
        "tool-judgment",
        "indic-language",
        "business-terse",
        "grounding",
        "long-context",
        "memory-directive");

    /**
     * Per-case prompt-size cost guard (chars, not tokens - close enough for
     * a guard rail without dragging in a tokenizer dependency). The suite's
     * cost posture is "many small cases": ~35 cases x 2 calls each is cheap
     * only while individual prompts stay short. See eval-harness.md's cost section.
     */
    private static final int MAX_CASE_CHARS = 4000;

    private static List<EvalCase> cases;

    @BeforeAll
    static void loadOnce() throws IOException {
        cases = EvalRunner.loadDataset();
    }

    @Test
    void datasetIsNonEmptyAndBroad() {
        // Floor of 25 matches the "meaningfully broad" bar the suite was
        // expanded to - shrinking below it should be a conscious decision,
        // not a drive-by deletion.
        assertThat(cases).hasSizeGreaterThanOrEqualTo(25);
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
            assertThat(c.persona()).as("persona of %s", c.id()).isNotBlank();
            assertThat(c.systemPrompt()).as("systemPrompt of %s", c.id()).isNotBlank();
            assertThat(c.userMessage()).as("userMessage of %s", c.id()).isNotBlank();
            assertThat(c.rubric()).as("rubric of %s", c.id()).isNotBlank();
        }
    }

    @Test
    void everyCaseUsesADocumentedCategory() {
        for (EvalCase c : cases) {
            assertThat(CATEGORY_TAXONOMY)
                .as("category '%s' of case %s must be in the eval-harness.md taxonomy", c.category(), c.id())
                .contains(c.categoryOrDefault());
        }
    }

    @Test
    void everyDocumentedCategoryHasAtLeastOneCase() {
        // The taxonomy is a promise about coverage dimensions the platform
        // sells - an empty bucket means the promise quietly stopped being
        // checked.
        Set<String> used = new HashSet<>();
        for (EvalCase c : cases) {
            used.add(c.categoryOrDefault());
        }
        assertThat(used).containsAll(CATEGORY_TAXONOMY);
    }

    @Test
    void casesStayCheapToRun() {
        for (EvalCase c : cases) {
            int totalChars = c.systemPrompt().length() + c.userMessage().length() + c.rubric().length();
            assertThat(totalChars)
                .as("case %s prompt size (chars) - keep cases short, see eval-harness.md cost section", c.id())
                .isLessThanOrEqualTo(MAX_CASE_CHARS);
        }
    }
}
