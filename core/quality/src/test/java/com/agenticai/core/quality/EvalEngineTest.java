package com.agenticai.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.quality.impl.LengthEvaluator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvalEngineTest {

    private EvalEngine evalEngine;

    @BeforeEach
    void setUp() {
        // For testing, we use only the LengthEvaluator (doesn't require LLM calls)
        LengthEvaluator lengthEvaluator = new LengthEvaluator();
        evalEngine = new EvalEngine(List.of(lengthEvaluator));
    }

    private static EvaluationContext context(String question, String answer) {
        return new EvaluationContext(
            UUID.randomUUID(),
            "test-agent",
            "You are helpful",
            question,
            answer,
            List.of(),
            0,
            0,
            "claude-3-sonnet"
        );
    }

    /** A stand-in for an LLM-judge evaluator whose judge cannot be reached. */
    private static Evaluator unavailableEvaluator(EvaluationType type) {
        return new Evaluator() {
            @Override
            public EvaluationType getType() {
                return type;
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                return EvaluationResult.unavailable(type, "Not evaluated - LLM judge unavailable");
            }
        };
    }

    @Test
    void testScoreAggregation() {
        EvaluationContext context =
            context("What is AI?", "AI is artificial intelligence, a field of computer science.");

        List<EvaluationType> enabled = List.of(EvaluationType.LENGTH);
        AggregatedEvaluationResult result = evalEngine.evaluate(context, enabled);

        assertNotNull(result);
        assertNotNull(result.resultsByType());
        assertTrue(result.hasMeasuredScore());
        assertTrue(result.overallScore() >= 0.0 && result.overallScore() <= 1.0,
            "Overall score should be between 0 and 1");
    }

    @Test
    void testEmptyEvaluationListYieldsNoScoreNotNeutral() {
        // No evals ran: the overall score must be ABSENT (null), never a
        // fabricated neutral 0.5 that reads as a real mid-range measurement.
        AggregatedEvaluationResult result = evalEngine.evaluate(context("Question", "Answer"), List.of());

        assertNotNull(result);
        assertNull(result.overallScore());
        assertTrue(result.resultsByType().isEmpty());
    }

    @Test
    void unavailableJudgeYieldsNoScoreNotPlausibleMidRange() {
        // THE core honesty assertion for this module: an unavailable LLM
        // judge must never surface as a plausible mid-range number.
        EvalEngine engine = new EvalEngine(List.of(unavailableEvaluator(EvaluationType.ACCURACY)));

        AggregatedEvaluationResult result =
            engine.evaluate(context("What is AI?", "AI is a field."), List.of(EvaluationType.ACCURACY));

        assertNull(result.overallScore(), "No measurement happened, so there must be no overall score");
        EvaluationResult accuracy = result.resultsByType().get(EvaluationType.ACCURACY);
        assertNotNull(accuracy, "The unavailable dimension must still be visible, with its reason");
        assertEquals(EvaluationStatus.UNAVAILABLE, accuracy.status());
        assertNull(accuracy.score(), "An unavailable evaluation must carry no score at all");
    }

    @Test
    void throwingEvaluatorIsRecordedAsUnavailableNotDropped() {
        Evaluator exploding = new Evaluator() {
            @Override
            public EvaluationType getType() {
                return EvaluationType.TONE;
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                throw new IllegalStateException("boom");
            }
        };
        EvalEngine engine = new EvalEngine(List.of(exploding));

        AggregatedEvaluationResult result =
            engine.evaluate(context("Q", "A"), List.of(EvaluationType.TONE));

        assertNull(result.overallScore());
        EvaluationResult tone = result.resultsByType().get(EvaluationType.TONE);
        assertNotNull(tone);
        assertEquals(EvaluationStatus.UNAVAILABLE, tone.status());
    }

    @Test
    void mixedAvailabilityAggregatesOnlyRealMeasurements() {
        // LENGTH (heuristic, always works) + ACCURACY (judge down): the
        // overall score must equal what was actually measured - the length
        // score - with the dead judge contributing nothing.
        EvalEngine engine = new EvalEngine(
            List.of(new LengthEvaluator(), unavailableEvaluator(EvaluationType.ACCURACY)));

        EvaluationContext ctx =
            context("What is AI?", "AI is artificial intelligence, a field of computer science.");
        List<EvaluationType> enabled = List.of(EvaluationType.LENGTH, EvaluationType.ACCURACY);

        AggregatedEvaluationResult result = engine.evaluate(ctx, enabled);

        assertTrue(result.hasMeasuredScore());
        EvaluationResult length = result.resultsByType().get(EvaluationType.LENGTH);
        assertEquals(EvaluationStatus.EVALUATED, length.status());
        assertEquals(length.score(), result.overallScore(),
            "Overall must equal the one real measurement, unpolluted by the unavailable dimension");
        assertEquals(EvaluationStatus.UNAVAILABLE,
            result.resultsByType().get(EvaluationType.ACCURACY).status());
    }

    @Test
    void recordEvaluationSkipsPersistenceWhenNothingWasMeasured() {
        AgentQualityRecordRepository repository = mock(AgentQualityRecordRepository.class);
        EvalEngine engine = new EvalEngine(List.of(unavailableEvaluator(EvaluationType.ACCURACY)));

        EvaluationContext ctx = context("Q", "A");
        AggregatedEvaluationResult result = engine.evaluate(ctx, List.of(EvaluationType.ACCURACY));

        AgentQualityRecord stored = engine.recordEvaluation(
            UUID.randomUUID().toString(), 1, ctx, result, repository);

        assertNull(stored, "An all-unavailable run must not become a stored data point");
        verify(repository, never()).save(any());
    }

    @Test
    void testGetEnabledForTier() {
        // Free tier
        List<EvaluationType> freeEvals = EvalEngine.getEnabledForTier(false);
        assertEquals(2, freeEvals.size(), "FREE tier should have 2 evaluations");
        assertTrue(freeEvals.contains(EvaluationType.HELPFULNESS));
        assertTrue(freeEvals.contains(EvaluationType.SAFETY));

        // Premium tier
        List<EvaluationType> premiumEvals = EvalEngine.getEnabledForTier(true);
        // 7 since FAITHFULNESS (2026-09-28) - it reports not-applicable, never a
        // score, on turns with no retrieved context, so premium online turns are
        // unaffected until a caller supplies RAG chunks.
        assertEquals(7, premiumEvals.size(), "PREMIUM tier should have 7 evaluations");
        assertTrue(premiumEvals.contains(EvaluationType.FAITHFULNESS));
    }

    @Test
    void testOverallScoreIsWeightedAverage() {
        EvaluationContext context = context("What?", "A good response with appropriate length.");

        AggregatedEvaluationResult result = evalEngine.evaluate(context, List.of(EvaluationType.LENGTH));

        assertTrue(result.hasMeasuredScore());
        assertTrue(result.overallScore() >= 0.0 && result.overallScore() <= 1.0);
    }

    /**
     * Regression for the fixed-4-thread-pool -> virtual-thread-per-task
     * executor swap (JVM memory/thread-pool audit, 2026-07-29): {@link
     * EvalEngine#evaluateAsync} must still complete with the exact same
     * result {@link EvalEngine#evaluate} would produce synchronously - the
     * executor changed, the evaluation semantics did not.
     */
    @Test
    void evaluateAsyncOnVirtualThreadExecutorMatchesSynchronousEvaluate() throws Exception {
        EvaluationContext context =
            context("What is AI?", "AI is artificial intelligence, a field of computer science.");

        AggregatedEvaluationResult asyncResult =
            evalEngine.evaluateAsync(context, List.of(EvaluationType.LENGTH)).get();

        assertNotNull(asyncResult);
        assertTrue(asyncResult.hasMeasuredScore());
        AggregatedEvaluationResult syncResult = evalEngine.evaluate(context, List.of(EvaluationType.LENGTH));
        assertEquals(syncResult.overallScore(), asyncResult.overallScore());
    }

    /**
     * {@code shutdown()} is now {@code @PreDestroy}-annotated so Spring
     * actually calls it (previously dead code from main-source's
     * perspective - see that method's javadoc). This confirms the method
     * itself still does what it always claimed: after shutdown, the
     * executor backing {@link EvalEngine#evaluateAsync} refuses further
     * work rather than silently continuing to accept it.
     */
    @Test
    void shutdownStopsTheExecutorFromAcceptingFurtherWork() {
        evalEngine.shutdown();

        assertThrows(RejectedExecutionException.class,
            () -> evalEngine.evaluateAsync(context("Q", "A"), List.of(EvaluationType.LENGTH)));
    }
}
