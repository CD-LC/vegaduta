package com.agenticai.core.agents.job.output;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Takes the model's final text for a job and turns it into JSON that provably satisfies the job's
 * result schema - or fails the job.
 *
 * <h2>The one rule this class exists to enforce</h2>
 *
 * A job that asked for structured output NEVER succeeds with prose. If the model cannot produce
 * conforming JSON within its repair budget, {@link StructuredOutputViolationException} is thrown
 * and the job lands FAILED with {@code error_code='SCHEMA_VIOLATION'}. Returning the prose "so the
 * caller has something" is the exact failure a result schema is requested in order to prevent: the
 * caller is a program, and it will either crash on the prose or - far worse - pattern-match
 * something plausible out of it and proceed on a value nobody validated. The prose is still
 * preserved on the row as {@code result_text}, as EVIDENCE for whoever diagnoses it, never as the
 * answer.
 *
 * <h2>Why {@code repairTurn} is a {@link Function} rather than an injected service</h2>
 *
 * The repair is another agent turn, which means {@code AgentChatService}. Taking it as a lambda
 * supplied by the caller keeps this class free of that dependency entirely: it is a pure
 * text-in/JSON-out state machine, unit-testable with a stub function and zero Spring context, and
 * it stays out of the way of the fact that the caller - not this class - owns the deadline, the
 * turn accounting and the cancellation checkpoints that must surround a real LLM call.
 *
 * <h2>Budget exhaustion is not a schema failure</h2>
 *
 * If {@code repairTurn} throws {@code PerUserBudgetExhaustedException}, it propagates UNWRAPPED.
 * Nothing here catches it. Masking a spend-ceiling stop as "the agent's output did not match the
 * schema" would send an operator to debug a schema that was never the problem, and would hide the
 * one error the platform most needs to report honestly.
 */
@Component
public class StructuredResultCoercer {

    private static final Logger log = LoggerFactory.getLogger(StructuredResultCoercer.class);

    private final ObjectMapper objectMapper;
    private final JobResultSchemaValidator validator;
    private final SchemaPromptRenderer promptRenderer;
    private final int maxRepairAttempts;

    public StructuredResultCoercer(
            ObjectMapper objectMapper,
            JobResultSchemaValidator validator,
            SchemaPromptRenderer promptRenderer,
            AgentJobProperties properties) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.promptRenderer = promptRenderer;
        this.maxRepairAttempts = Math.max(0, properties.getOutputSchema().getMaxRepairAttempts());
    }

    /**
     * @param resultText the text to persist as {@code agent_job.result_text}. On success this is
     *     the CONFORMING attempt, not the model's first one - so the row's text and its JSON always
     *     describe the same answer.
     * @param resultJson the validated document, ready for {@code agent_job.result_json}.
     * @param attempts model turns spent producing it: 1 when the first answer conformed, 1 + n
     *     after n repairs. Persisted as {@code schema_attempts} because a schema that always needs
     *     two attempts is a schema (or a model) worth revisiting, and that is invisible without the
     *     count.
     */
    public record CoercedResult(String resultText, JsonNode resultJson, int attempts) {}

    /**
     * Convenience for the job runner: the goal text to send on the FIRST turn.
     *
     * <p>Here rather than at the call site so that "how a schema reaches the model" and "how its
     * answer is checked" stay in one class - if they drift apart (a renderer change that the
     * coercer's parser no longer anticipates), it is one file that has to be read to see it.
     */
    public String goalWithSchemaInstruction(String goal, String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return goal;
        }
        return goal + promptRenderer.firstTurnInstruction(schemaJson);
    }

    /**
     * @param repairTurn given a repair prompt, runs one more agent turn and returns its text.
     *     Called at most {@code app.agent-jobs.output-schema.max-repair-attempts} times. Any
     *     exception it throws propagates untouched - see the class javadoc on budget exhaustion.
     * @throws StructuredOutputViolationException when the repair budget is spent and the answer
     *     still does not conform.
     */
    public CoercedResult coerce(
            String schemaJson, String finalText, Function<String, String> repairTurn) {
        String candidate = finalText;
        List<String> violations = new ArrayList<>();

        // attempt 1 is the original answer; each additional pass is one repair.
        for (int attempt = 1; attempt <= maxRepairAttempts + 1; attempt++) {
            JsonNode parsed = parseOrNull(candidate);
            if (parsed == null) {
                violations =
                        List.of(
                                "the answer was not valid JSON at all - it must be a single JSON object");
            } else {
                violations = validator.violations(schemaJson, parsed);
                if (violations.isEmpty()) {
                    // The text persisted is the attempt that PASSED, so
                    // result_text and result_json can never describe different
                    // answers on the same row.
                    return new CoercedResult(candidate, parsed, attempt);
                }
            }

            if (attempt > maxRepairAttempts) {
                break;
            }
            log.debug(
                    "Agent job result failed schema validation on attempt {} - repairing. Violations: {}",
                    attempt,
                    violations);
            // Not wrapped in a try/catch on purpose. See the class javadoc: a
            // budget exhaustion, a cancellation or a provider outage during the
            // repair turn is that failure, and must be reported as itself.
            candidate =
                    repairTurn.apply(
                            promptRenderer.repairInstruction(schemaJson, candidate, violations));
        }

        throw new StructuredOutputViolationException(maxRepairAttempts + 1, violations);
    }

    // --------------------------------------------------------------- internals

    /**
     * Best-effort JSON recovery from model output, via the shared {@link StructuredOutput} locator,
     * in this order:
     *
     * <ol>
     *   <li><b>Fence strip.</b> Models emit ```` ```json ... ``` ```` constantly, instruction or no
     *       instruction. Stripping it is not "being lenient" - it is recognising the single most
     *       common wrapper and refusing to burn a repair turn on it.
     *   <li><b>The whole reply as one JSON document</b> - any value; a non-object is rejected by
     *       schema validation, not here.
     *   <li><b>First balanced {@code {...}} / {@code [...]} span that parses.</b> Catches the other
     *       common shape: a correct object with a sentence of prose in front of (or after) it
     *       ("Here's the summary you asked for:"). Deliberately the LAST resort - it is a
     *       heuristic, so anything it recovers still goes through full schema validation before it
     *       is allowed anywhere near a SUCCEEDED row.
     * </ol>
     *
     * <p>Returns null rather than throwing: "unparseable" is a normal intermediate state that feeds
     * the repair loop, not an exceptional one.
     */
    private JsonNode parseOrNull(String text) {
        return StructuredOutput.readTree(objectMapper, text).orElse(null);
    }
}
