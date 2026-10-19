package com.agenticai.core.agents.job.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOutputContract;
import com.agenticai.core.agents.job.AgentJobProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The seam implementation on its own, without the execution machinery around
 * it - the cases that are about how this class behaves at its BOUNDARIES rather
 * than about what a job row ends up saying.
 */
class AgentJobStructuredOutputContractTest {

    private final AgentJobProperties properties = StructuredOutputJobFixtures.properties();
    private final AgentJobStructuredOutputContract contract = StructuredOutputJobFixtures.contract(properties);

    @Test
    void withoutASchemaTheGoalAndTheResultBothPassThroughUntouched() throws Exception {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(null);
        AtomicBoolean repaired = new AtomicBoolean();

        assertEquals(StructuredOutputJobFixtures.GOAL, contract.effectiveGoal(job));

        AgentJobOutputContract.FinalResult result = contract.finalizeResult(
            job, StructuredOutputJobFixtures.PROSE,
            prompt -> {
                repaired.set(true);
                return "never reached";
            });

        assertEquals(StructuredOutputJobFixtures.PROSE, result.resultText());
        assertNull(result.resultJson());
        assertEquals(0, result.schemaAttempts());
        assertFalse(repaired.get(), "a job with no schema must not cost a single extra model call");
    }

    @Test
    void withASchemaTheGoalCarriesItAndAConformingAnswerIsAcceptedFirstTime() throws Exception {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);

        assertTrue(contract.effectiveGoal(job).startsWith(StructuredOutputJobFixtures.GOAL));
        assertTrue(contract.effectiveGoal(job).contains("\"count\""));

        AgentJobOutputContract.FinalResult result = contract.finalizeResult(
            job, StructuredOutputJobFixtures.CONFORMING,
            prompt -> {
                throw new AssertionError("a conforming answer must not trigger a repair turn");
            });

        assertEquals(1, result.schemaAttempts());
        assertNotNull(result.resultJson());
    }

    /**
     * Models wrap JSON in a markdown fence constantly, instruction or no
     * instruction. Recognising the single most common wrapper is not leniency -
     * it is refusing to spend a paid repair turn on it. Whatever is recovered
     * still goes through full schema validation before it can reach a SUCCEEDED
     * row.
     */
    @Test
    void aFencedAnswerIsRecoveredRatherThanCostingARepairTurn() throws Exception {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);

        AgentJobOutputContract.FinalResult result = contract.finalizeResult(
            job, "```json\n" + StructuredOutputJobFixtures.CONFORMING + "\n```",
            prompt -> {
                throw new AssertionError("a fenced but otherwise valid answer must not trigger a repair turn");
            });

        assertEquals(1, result.schemaAttempts());
        assertNotNull(result.resultJson());
    }

    /**
     * Prose on BOTH sides of the object, with a brace group in the trailing
     * chatter: the old first-{ to last-} slice spanned the chatter and cost a
     * repair turn; the balanced-span locator recovers the object itself.
     */
    @Test
    void anAnswerWithBraceyChatterAfterItIsRecoveredWithoutARepairTurn() throws Exception {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);

        AgentJobOutputContract.FinalResult result = contract.finalizeResult(
            job, "Here is the result:\n" + StructuredOutputJobFixtures.CONFORMING
                + "\nLet me know if you want {more} detail.",
            prompt -> {
                throw new AssertionError("a recoverable answer must not trigger a repair turn");
            });

        assertEquals(1, result.schemaAttempts());
        assertNotNull(result.resultJson());
    }

    /**
     * A cancel that lands during a repair turn has to come out as an {@link
     * InterruptedException}, not as some wrapper type. {@code
     * StructuredResultCoercer}'s repair hook is a plain {@code Function} and
     * cannot declare a checked exception, so the interrupt is smuggled across
     * that boundary - and if the unwrapping were forgotten, {@code
     * AgentJobExecution}'s generic {@code RuntimeException} clause would record a
     * cancelled job as {@code EXECUTION_FAILED}.
     */
    @Test
    void anInterruptDuringARepairTurnSurfacesAsAnInterruptedException() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);
        InterruptedException raised = new InterruptedException("cancelled mid-repair");

        InterruptedException thrown = assertThrows(InterruptedException.class, () ->
            contract.finalizeResult(job, StructuredOutputJobFixtures.PROSE, prompt -> {
                throw raised;
            }));

        assertSame(raised, thrown, "the original interrupt must reach the caller, not a copy of it");
    }

    /**
     * Anything else thrown by a repair turn propagates UNWRAPPED, so it reaches
     * its own existing handler. A budget exhaustion reported as "your schema did
     * not match" sends an operator to debug the wrong thing entirely.
     */
    @Test
    void aRuntimeFailureInsideARepairTurnPropagatesUnwrapped() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);
        IllegalStateException providerOutage = new IllegalStateException("provider unavailable");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
            contract.finalizeResult(job, StructuredOutputJobFixtures.PROSE, prompt -> {
                throw providerOutage;
            }));

        assertSame(providerOutage, thrown);
    }

    @Test
    void anUnsatisfiableSchemaThrowsRatherThanReturningProse() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);

        StructuredOutputViolationException violation = assertThrows(
            StructuredOutputViolationException.class, () ->
                contract.finalizeResult(job, StructuredOutputJobFixtures.PROSE,
                    prompt -> StructuredOutputJobFixtures.PROSE));

        // The original answer plus max-repair-attempts (2).
        assertEquals(properties.getOutputSchema().getMaxRepairAttempts() + 1, violation.getAttempts());
        assertFalse(violation.getViolations().isEmpty());
    }
}
