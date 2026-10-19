package com.agenticai.core.agents.job.output;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOutputContract;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The wiring that makes a result schema actually mean something.
 *
 * <h2>What was broken, precisely</h2>
 * Everything else in this package shipped in 52399a20 and none of it was ever
 * called. {@code agent.result_schema_json} was writable through the API,
 * {@code agent_job.result_schema_json} was snapshotted onto every row, {@link
 * JobResultSchemaValidator} compiled schemas and {@link StructuredResultCoercer}
 * implemented the whole validate-repair-or-fail loop - and the execution path
 * read none of it, so a job that declared a schema SUCCEEDED with prose and a
 * {@code null} {@code result_json}. That is not a missing feature; it is a
 * contract violation, because the caller of a structured-output job is by
 * construction a program. It will either crash on the prose or, far worse,
 * pattern-match something plausible out of it and carry on with a value nobody
 * validated. This class is the ~40 lines that were missing between the two
 * halves.
 *
 * <h2>Why it is a seam implementation rather than code inside the executor</h2>
 * {@code AgentJobExecution} is the one file four parallel features would
 * otherwise have edited simultaneously. It consults {@link
 * AgentJobOutputContract} through an {@code ObjectProvider} and knows nothing
 * about this package. The consequence worth stating: with this bean absent, jobs
 * behave exactly as they did before - the goal goes to the model unchanged and
 * the text is persisted unchanged. Registering it is what turns the schema on.
 *
 * <h2>Blast radius: a "one turn" job can make three model calls</h2>
 * {@code max_turns} is 1, but a schema violation buys up to {@code
 * app.agent-jobs.output-schema.max-repair-attempts} (default 2) additional
 * calls, so up to three in total. There is no job-level cost ledger - {@code
 * agent_job.cost_usd} is never written - and every {@code
 * AgentChatService.chat(...)} call independently consumes a FREE-tier message
 * unit and a {@code PerUserBudgetService} reservation keyed on the REQUESTER's
 * subject. So a repair turn charges the human again. That is correct (real
 * tokens are genuinely spent) but it is real money and it is bounded only by
 * that one property, which is why this paragraph exists instead of the fact
 * being left for someone to discover from an invoice.
 *
 * <h2>Repair turns do not consume {@code turns_used}</h2>
 * {@code turns_used} counts GOAL turns and is what the durability policy reads
 * to decide whether a job did chargeable work. Repairs are bounded separately
 * and recorded in {@code schema_attempts}, so the two questions - "how far
 * through its task did this job get" and "how hard was the schema to satisfy" -
 * stay answerable independently.
 */
@Component
public class AgentJobStructuredOutputContract implements AgentJobOutputContract {

    private static final Logger log = LoggerFactory.getLogger(AgentJobStructuredOutputContract.class);

    /**
     * F-PR6's reserved Micrometer namespace. One meter NAME with one tag KEY, on
     * purpose: registering the same name with differing tag sets is a Micrometer
     * startup failure, and three sibling counters would make "what fraction of
     * schema'd jobs needed a repair?" a client-side sum of three series.
     */
    private static final String OUTCOME_COUNTER = "agent.job.schema.result";

    private final StructuredResultCoercer coercer;
    private final MeterRegistry meterRegistry;

    public AgentJobStructuredOutputContract(StructuredResultCoercer coercer, MeterRegistry meterRegistry) {
        this.coercer = coercer;
        this.meterRegistry = meterRegistry;
    }

    /**
     * The goal with the schema instruction appended, or the goal untouched when
     * the job declared no schema.
     *
     * <p>Delegated to {@link StructuredResultCoercer#goalWithSchemaInstruction}
     * rather than reaching for {@link SchemaPromptRenderer} directly, so that
     * "how the schema reaches the model" and "how the answer is checked" stay in
     * one class. If those two ever drift - a re-worded instruction the parser no
     * longer anticipates - there is one file to read to see it.
     */
    @Override
    public String effectiveGoal(AgentJob job) {
        return coercer.goalWithSchemaInstruction(job.getGoal(), job.getResultSchemaJson());
    }

    /**
     * Validates, repairs within budget, or throws.
     *
     * <p>Three exits, and the third is the whole point of the feature:
     * <ol>
     *   <li>no schema on the job - the text through unchanged, {@code
     *       result_json} null, {@code schema_attempts} 0. Byte-for-byte the
     *       pre-existing behaviour of the common path.</li>
     *   <li>conforming JSON within budget - {@code result_text} is the attempt
     *       that PASSED (never the first one, when they differ) so the row's
     *       text and its JSON can never describe different answers.</li>
     *   <li>budget spent and still non-conforming - {@link
     *       StructuredOutputViolationException}, which {@code AgentJobExecution}
     *       records as {@code error_code = SCHEMA_VIOLATION}. It does NOT fall
     *       back to prose.</li>
     * </ol>
     *
     * <p>Nothing here catches {@link RuntimeException}. A {@code
     * PerUserBudgetExhaustedException}, a cancellation or a provider outage
     * raised inside a repair turn must reach its own existing handler
     * unwrapped - reporting a spend-ceiling stop as "your schema did not match"
     * sends an operator to debug the wrong thing entirely.
     */
    @Override
    public FinalResult finalizeResult(AgentJob job, String rawText, RepairTurn repairTurn)
        throws InterruptedException {

        String schemaJson = job.getResultSchemaJson();
        if (schemaJson == null || schemaJson.isBlank()) {
            return new FinalResult(rawText, null, 0);
        }

        try {
            StructuredResultCoercer.CoercedResult coerced =
                coercer.coerce(schemaJson, rawText, prompt -> {
                    try {
                        return repairTurn.run(prompt);
                    } catch (InterruptedException interrupted) {
                        // The coercer's repair hook is a plain Function and
                        // cannot declare a checked exception, so the interrupt
                        // is smuggled across it and unwrapped below rather than
                        // being swallowed. Swallowing it would leave a cancelled
                        // job grinding through its remaining repair budget.
                        throw new RepairInterruptedException(interrupted);
                    }
                });

            meterRegistry.counter(
                OUTCOME_COUNTER, "outcome", coerced.attempts() == 1 ? "conformed" : "repaired").increment();
            if (coerced.attempts() > 1) {
                // INFO, not DEBUG: each of these attempts was a paid model call,
                // and a schema that routinely needs a second one is a schema (or
                // a model) worth revisiting.
                log.info(
                    "Agent job {} satisfied its result schema on attempt {} - {} repair turn(s) were paid for",
                    job.getId(), coerced.attempts(), coerced.attempts() - 1);
            }
            // toString() rather than a re-serialization through the injected
            // ObjectMapper: this node was parsed from the model's own text, the
            // column is TEXT, and Jackson's compact rendering of a JsonNode is
            // already exactly what AgentJobResponse re-parses on the way out.
            return new FinalResult(coerced.resultText(), coerced.resultJson().toString(), coerced.attempts());

        } catch (RepairInterruptedException smuggled) {
            throw smuggled.unwrap();

        } catch (StructuredOutputViolationException violation) {
            // Counted here rather than at the catch site in AgentJobExecution:
            // that file must stay closed to this feature, and "how often does a
            // schema prove unsatisfiable" is a question about schemas, not about
            // job execution. Re-thrown untouched - the job MUST fail.
            meterRegistry.counter(OUTCOME_COUNTER, "outcome", "violation").increment();
            log.warn(
                "Agent job {} could not satisfy its result schema after {} attempts - failing it rather than "
                    + "returning prose. Violations: {}",
                job.getId(), violation.getAttempts(), violation.getViolations());
            throw violation;
        }
    }

    /**
     * Carries an {@link InterruptedException} across {@link
     * StructuredResultCoercer#coerce}'s {@code Function} boundary.
     *
     * <p>Private and unwrapped in the same method that creates it, so it can
     * never escape into {@code AgentJobExecution}'s generic {@code
     * RuntimeException} clause - where a cancelled job would be relabelled
     * {@code EXECUTION_FAILED} instead of {@code CANCELLED}.
     */
    private static final class RepairInterruptedException extends RuntimeException {

        private RepairInterruptedException(InterruptedException cause) {
            super(cause);
        }

        private InterruptedException unwrap() {
            return (InterruptedException) getCause();
        }
    }
}
