package com.agenticai.core.agents.job;

/**
 * The seam through which structured output (schema instruction on the way in,
 * validation plus bounded repair turns on the way out) plugs into {@link
 * AgentJobExecution} WITHOUT that class ever importing {@code job/output/}.
 *
 * <p><b>Why a seam at all.</b> {@code AgentJobExecution} is the one file every
 * feature in this workstream would otherwise need to edit at the same time. It
 * is opened ONCE, by the foundation, and consults this interface instead - so
 * the structured-output feature ships entirely inside its own package.
 *
 * <p><b>Absent implementation == today's behaviour, exactly.</b> Both methods
 * have defaults that are pure pass-through: the goal goes to the model
 * unchanged, and the model's text is persisted unchanged with a {@code null}
 * {@code result_json} and {@code schema_attempts = 0}. {@link
 * AgentJobExecution} resolves this via {@code
 * ObjectProvider#getIfAvailable(() -> PASS_THROUGH)}, so a deployment with no
 * implementing bean behaves byte-for-byte as it did before this interface
 * existed.
 *
 * <p><b>Intended implementer: F-PR6</b>, as a {@code @Component} in {@code
 * com.agenticai.core.agents.job.output}. Exactly one implementation may exist -
 * two beans would make {@code ObjectProvider#getIfAvailable} throw at the first
 * job, not at startup.
 */
public interface AgentJobOutputContract {

    /**
     * The message text for the FIRST turn of the job.
     *
     * <p>Default: the goal, unchanged. An implementation returns the goal with
     * the schema instruction appended (prompt-based structured output - see
     * {@code SchemaPromptRenderer}), which is why this is a separate hook rather
     * than the executor concatenating anything itself.
     */
    default String effectiveGoal(AgentJob job) {
        return job.getGoal();
    }

    /**
     * Turns the model's final text into what actually gets persisted, given a
     * way to run one more model turn.
     *
     * <p>Default: the text through unchanged, no JSON, zero attempts.
     *
     * <p>An implementation validates against the job's schema and, on a
     * violation, calls {@code repairTurn} to re-PROMPT the model - bounded by
     * {@code app.agent-jobs.output-schema.max-repair-attempts}. When the budget
     * is spent it must throw {@code StructuredOutputViolationException}, which
     * {@link AgentJobExecution} catches and records as {@code
     * error_code = SCHEMA_VIOLATION}. It must NEVER quietly return prose for a
     * job that declared a schema - that silent success is the shipped contract
     * violation this seam exists to close.
     *
     * @param repairTurn runs one more agent turn with the supplied prompt and
     *     returns its text. Supplied by the caller so this interface owns
     *     neither the deadline, the turn accounting nor the tool ceiling - all
     *     three are already enforced inside the lambda {@link AgentJobExecution}
     *     passes. Anything it throws ({@code PerUserBudgetExhaustedException},
     *     a cancellation, a provider outage) must be allowed to propagate
     *     untouched so it reaches its own existing handler rather than being
     *     relabelled as a schema failure.
     * @throws InterruptedException if the repair turn is interrupted by a cancel.
     */
    default FinalResult finalizeResult(AgentJob job, String rawText, RepairTurn repairTurn)
        throws InterruptedException {
        return new FinalResult(rawText, null, 0);
    }

    /**
     * What gets written by {@code AgentJob#succeed}.
     *
     * @param resultJson the structured result as a STRING, not a {@code
     *     JsonNode}: {@code agent_job.result_json} is a TEXT column, {@code
     *     AgentJob#succeed} takes a String, and {@code AgentJobResponse}
     *     re-parses it for the client. Keeping it a String here means the value
     *     is never serialized twice. {@code null} when the job declared no
     *     schema.
     * @param schemaAttempts 1 when the first answer conformed, 2 after one
     *     repair, and so on. 0 when no schema applied.
     */
    record FinalResult(String resultText, String resultJson, int schemaAttempts) {
    }

    /** One more agent turn, on the caller's terms. See {@link #finalizeResult}. */
    @FunctionalInterface
    interface RepairTurn {
        String run(String prompt) throws InterruptedException;
    }

    /** The no-op default used when no implementation is registered. */
    AgentJobOutputContract PASS_THROUGH = new AgentJobOutputContract() {
    };
}
