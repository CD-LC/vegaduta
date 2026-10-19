package com.agenticai.core.agents.job.output;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a result schema into the two pieces of prompt text the structured-output
 * path needs: the instruction appended to a job's goal on the FIRST turn, and
 * the repair instruction sent when the answer did not conform.
 *
 * <p><b>Its own class, rather than string literals inside {@link
 * StructuredResultCoercer}, because prompt wording is the part of this feature
 * most likely to be tuned.</b> The coercer's logic (parse, validate, repair,
 * give up) is a state machine that should not need touching when someone
 * discovers that a particular model responds better to a differently-phrased
 * instruction. Keeping the two apart also means the coercer's unit tests never
 * assert on prompt copy, so re-wording does not break them.
 *
 * <p><b>Deliberately not using the provider's own "JSON mode" / response-format
 * parameter.</b> That would be strictly better where it exists - but it exists
 * on some of this platform's providers and not others, with three different
 * shapes, and reaching it would mean editing {@code AgentChatService} and the
 * provider layer, both of which this workstream must not touch. Prompt-plus-
 * validate-plus-repair works identically across every provider the platform has,
 * and the VALIDATION is what actually guarantees the contract - the prompt only
 * improves the hit rate on the first try. If a native response-format is wired
 * up later it slots in ahead of this without changing the guarantee.
 */
@Component
public class SchemaPromptRenderer {

    /**
     * Appended to the job's goal on the first turn. Kept short: it competes for
     * attention with the actual task, and a long preamble about formatting is a
     * reliable way to get a well-formatted answer to the wrong question.
     *
     * <p>Explicitly forbidding the markdown fence is not redundant with {@link
     * StructuredResultCoercer} stripping one. The coercer strips it because
     * models emit it anyway; the instruction asks them not to because a bare
     * object is what actually gets parsed on the first attempt, and every
     * recovered fence is a heuristic that could in principle recover the wrong
     * thing.
     */
    public String firstTurnInstruction(String schemaJson) {
        // "Respond with ONLY a JSON object" on its own is read, by capable models too, as "answer right now": the
        // job's tools were never called and the schema was filled with example values (a research run finished in
        // 5s with example.com sources). So the instruction is about the FINAL answer, after the work and its tools.
        return """

            ---
            Do the task above first, using whichever of your tools it needs. Then give your FINAL \
            answer as ONLY a single JSON object that validates against this JSON Schema: no prose \
            before or after it, no markdown code fence, no explanation - the raw object and nothing \
            else. Every value must come from the work you actually did; never fill the schema with \
            example or placeholder values.

            Schema:
            """
            + schemaJson;
    }

    /**
     * The repair turn. Shows the model its OWN previous answer alongside the
     * specific violations, because "that was wrong, try again" reliably produces
     * a differently-wrong answer - the model has no memory of the attempt within
     * this new turn (each job turn is a fresh chat call), so the previous output
     * has to be quoted back or it is repairing something it cannot see.
     *
     * <p>The violations are quoted verbatim as {@link JobResultSchemaValidator}
     * formats them ("at /items/0/qty: integer found, string expected"), which is
     * already the most actionable phrasing available - paraphrasing them into
     * friendlier prose would lose the JSON pointer, which is the single most
     * useful token in the whole message.
     */
    public String repairInstruction(String schemaJson, String previousAnswer, List<String> violations) {
        StringBuilder prompt = new StringBuilder()
            .append("Your previous answer did not validate against the required JSON Schema.\n\n")
            .append("Your previous answer was:\n")
            .append(previousAnswer == null ? "(empty)" : previousAnswer)
            .append("\n\nThe problems were:\n");
        for (String violation : violations) {
            prompt.append("- ").append(violation).append('\n');
        }
        return prompt
            .append("\nSchema:\n")
            .append(schemaJson)
            .append("\n\nReturn ONLY the corrected JSON object. No prose, no markdown fence. ")
            .append("Do not redo the task - keep the same content and fix only the structure, ")
            .append("unless a value is missing entirely, in which case supply it.")
            .toString();
    }
}
