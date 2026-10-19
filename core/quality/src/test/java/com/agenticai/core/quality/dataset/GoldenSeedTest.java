package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.dataset.EvalDatasetService.GoldenCase;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins this module's {@code golden-dataset.json}
 * ({@code src/main/resources/golden-dataset.json}, shared with the {@code
 * com.agenticai.core.quality.eval} CLI harness since the {@code core/eval}
 * module merged in) through the exact loader the seed endpoint uses, so a
 * stale or malformed dataset fails the fast free CI job rather than the
 * first admin click on "Seed golden set". The shape assertions mirror the
 * harness's own {@code GoldenDatasetTest} (floor of 25, unique ids, every
 * grading field present); see {@code
 * EvalDatasetService#GOLDEN_DATASET_RESOURCES}.
 *
 * <p>Also covers the two pure Langfuse-link derivations, which need no
 * Spring context.
 */
class GoldenSeedTest {

    @Test
    void inModuleGoldenCopyLoadsThroughTheSeedLoader() throws IOException {
        List<GoldenCase> cases = EvalDatasetService.loadGoldenCases();

        assertTrue(cases.size() >= 25, "golden dataset shrank below the documented floor: " + cases.size());
        assertTrue(cases.size() <= EvalDatasetService.MAX_CASES_PER_DATASET,
            "golden copy exceeds the per-dataset cap - the seed would refuse to run");
        Set<String> ids = new HashSet<>();
        for (GoldenCase c : cases) {
            assertNotNull(c.id());
            assertFalse(c.id().isBlank(), "blank case id");
            assertTrue(ids.add(c.id()), "duplicate case id: " + c.id());
            assertFalse(c.userMessage() == null || c.userMessage().isBlank(), "userMessage of " + c.id());
            assertFalse(c.rubric() == null || c.rubric().isBlank(), "rubric of " + c.id());
            assertFalse(c.persona() == null || c.persona().isBlank(), "persona of " + c.id());
        }
    }

    @Test
    void referenceTextCarriesProvenanceAndRubric() {
        GoldenCase c = new GoldenCase("exam-coach-one-question", "exam-prep-coach", "persona-adherence",
            "sys", "Quiz me", "Must ask exactly ONE question.");

        String text = EvalDatasetService.goldenReferenceText(c);

        assertTrue(text.contains("'exam-coach-one-question'"));
        assertTrue(text.contains("persona: exam-prep-coach"));
        assertTrue(text.contains("category: persona-adherence"));
        assertTrue(text.endsWith("Must ask exactly ONE question."));
        // A pre-category entry degrades to the same bucket the eval harness's report uses.
        assertTrue(EvalDatasetService.goldenReferenceText(
            new GoldenCase("x", "p", null, "s", "u", "r")).contains("category: uncategorized"));
    }

    @Test
    void browserBaseIsNullWithoutLangfuseAuthEvenWhenHostIsSet() {
        // A trace id logged with tracing on but no Langfuse deployed points
        // at nothing - no link beats a dead link.
        assertNull(EvalTraceLinks.resolveLangfuseBrowserBase(
            "http://localhost:3002", "http://langfuse-web:3000/api/public/otel/v1/traces", ""));
        assertNull(EvalTraceLinks.resolveLangfuseBrowserBase("http://localhost:3002", null, null));
    }

    @Test
    void browserBasePrefersLangfuseHostThenOtlpDerivedOrigin() {
        String auth = "Basic cGs6c2s=";
        assertEquals("http://localhost:3002", EvalTraceLinks.resolveLangfuseBrowserBase(
            "http://localhost:3002/", "http://langfuse-web:3000/api/public/otel/v1/traces", auth));
        // Same stripping as LangfuseScoreClient#deriveScoresUrl.
        assertEquals("http://langfuse-web:3000", EvalTraceLinks.resolveLangfuseBrowserBase(
            " ", "http://langfuse-web:3000/api/public/otel/v1/traces", auth));
        assertEquals("https://lf.example.com", EvalTraceLinks.resolveLangfuseBrowserBase(
            null, "https://lf.example.com/", auth));
    }

    @Test
    void traceUrlUsesProjectScopedShapeWhenProjectKnownElseRedirectRoute() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        assertEquals("http://localhost:3002/project/proj-1/traces/" + traceId,
            EvalTraceLinks.langfuseTraceUrl("http://localhost:3002", "proj-1", traceId));
        assertEquals("http://localhost:3002/trace/" + traceId,
            EvalTraceLinks.langfuseTraceUrl("http://localhost:3002", null, " " + traceId + " "));
        assertNull(EvalTraceLinks.langfuseTraceUrl(null, "proj-1", traceId));
        assertNull(EvalTraceLinks.langfuseTraceUrl("http://localhost:3002", "proj-1", null));
        assertNull(EvalTraceLinks.langfuseTraceUrl("http://localhost:3002", "proj-1", "  "));
    }
}
