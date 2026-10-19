package com.agenticai.core.agents.job.output;

import java.util.List;

/**
 * The agent could not produce output matching the job's schema, even after its
 * repair turns were spent.
 *
 * <p><b>This existing at all is the point of the feature.</b> The tempting
 * alternative - hand back whatever prose the model produced and let the caller
 * sort it out - is precisely the failure mode a result schema is asked for in
 * order to prevent. A caller that requested structured output is, by
 * construction, a program: it will either crash on the prose or, far worse,
 * regex something plausible out of it and carry on with a value nobody
 * validated. So the job FAILS, explicitly, with {@code
 * error_code='SCHEMA_VIOLATION'} and the actual violations in the message.
 * {@code result_text} still holds the model's last attempt, so a human
 * diagnosing it can see exactly what came back - the text is preserved as
 * EVIDENCE, never returned as if it were the answer.
 *
 * <p>Deliberately NOT a subclass of anything {@code GlobalExceptionHandler}
 * maps. This is never thrown on an HTTP thread: it surfaces inside a worker,
 * where the job runner turns it into a terminal row. There is no request to
 * answer with a status code.
 */
public class StructuredOutputViolationException extends RuntimeException {

    /** Cap on how many violations go into the message - see {@link #describe}. */
    private static final int MAX_REPORTED = 5;

    private final int attempts;
    private final transient List<String> violations;

    public StructuredOutputViolationException(int attempts, List<String> violations) {
        super(describe(attempts, violations));
        this.attempts = attempts;
        this.violations = List.copyOf(violations);
    }

    /** How many model turns were spent trying (the first attempt plus each repair). */
    public int getAttempts() {
        return attempts;
    }

    /** Human-readable violations, one per line as {@code JobResultSchemaValidator} formats them. */
    public List<String> getViolations() {
        return violations;
    }

    /**
     * Truncated to the first few violations on purpose. This string lands in
     * {@code agent_job.error_message} and in front of an operator; a schema with
     * forty required properties and a model that returned {@code {}} would
     * otherwise produce a wall of text in which the ONE informative line (the
     * first) is buried. The count is still reported so nobody mistakes the
     * truncated list for the whole story.
     */
    private static String describe(int attempts, List<String> violations) {
        StringBuilder message = new StringBuilder("The agent's output did not match the required result schema after ")
            .append(attempts)
            .append(attempts == 1 ? " attempt" : " attempts")
            .append('.');
        if (violations.isEmpty()) {
            return message.toString();
        }
        message.append(" Violations: ");
        message.append(String.join("; ", violations.subList(0, Math.min(MAX_REPORTED, violations.size()))));
        if (violations.size() > MAX_REPORTED) {
            message.append(" (and ").append(violations.size() - MAX_REPORTED).append(" more)");
        }
        return message.toString();
    }
}
