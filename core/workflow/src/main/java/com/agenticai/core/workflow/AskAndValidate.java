package com.agenticai.core.workflow;

import com.agenticai.core.chat.gateway.LlmGatewayLimitExceededException;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template method for an AI node's "ask the model, validate the reply" step. The fixed skeleton is: one
 * temperature-0 model call (quota / budget exceptions propagate so the run stops visibly; any other provider
 * failure is reported, never retried), then {@link #validate} on the raw reply, then - only when the subclass
 * supplies a {@link #repairPrompt} for the issues it found - exactly ONE repair call, keeping the better of the two
 * results. Subclasses fill in the hooks: the system prompt, the validation (and with it type coercion), what counts
 * as an issue, and the repair wording.
 *
 * <p>The classifier uses only {@link #ask} (one call, no repair); the parameter extractor uses {@link #run}.
 *
 * @param <R> the validated result: a classification, an extraction, ...
 */
abstract class AskAndValidate<R> {

    private static final Logger log = LoggerFactory.getLogger(AskAndValidate.class);

    /** One model call's outcome: the validated result (null when the reply was unusable) or the provider failure. */
    record Attempt<R>(R result, RuntimeException failure) {
        boolean providerFailed() {
            return failure != null;
        }
    }

    /** The outcome of {@link #run}: the best validated result (maybe null) and whether the first call failed. */
    record Result<R>(R best, boolean providerFailed) {}

    /** The system prompt of every call. */
    abstract String system();

    /** Short node name for log lines, e.g. "Question classifier". */
    abstract String label();

    /** Validates (and coerces) a raw model reply; null when it holds nothing usable. Must not throw. */
    abstract R validate(String reply);

    /** What is wrong with {@code result} (null = no usable reply at all); empty means accept it as is. */
    List<String> issues(R result) {
        return List.of();
    }

    /** The user message for the single repair call, or null for "never repair". */
    String repairPrompt(String user, List<String> issues) {
        return null;
    }

    /** Whether the repair result replaces the first one. */
    boolean replaces(R repaired, R current) {
        return repaired != null;
    }

    /** Called once per failed provider call, after it was logged; e.g. to add a line to the node log. */
    void onProviderFailure(RuntimeException failure, List<String> logLines) {}

    /** One model call plus validation. */
    final Attempt<R> ask(
            WorkflowAiModel model,
            WorkflowNodeDto node,
            String user,
            int maxTokens,
            String provider,
            String modelId,
            List<String> logLines) {
        String reply;
        try {
            reply =
                    model.complete(
                            new WorkflowAiModel.Request(
                                    system(), user, maxTokens, 0.0, provider, modelId));
        } catch (TenantLimitExceededException | LlmGatewayLimitExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("{} model call failed for node {}: {}", label(), node.id(), e.toString());
            onProviderFailure(e, logLines);
            return new Attempt<>(null, e);
        }
        return new Attempt<>(validate(reply), null);
    }

    /** {@link #ask}, then at most one repair call when {@link #repairPrompt} wants one. */
    final Result<R> run(
            WorkflowAiModel model,
            WorkflowNodeDto node,
            String user,
            int maxTokens,
            String provider,
            String modelId,
            List<String> logLines) {
        Attempt<R> first = ask(model, node, user, maxTokens, provider, modelId, logLines);
        R best = first.result();
        if (first.providerFailed()) {
            return new Result<>(best, true);
        }
        List<String> issues = issues(best);
        String repair = issues.isEmpty() ? null : repairPrompt(user, issues);
        if (repair != null) {
            logLines.add(
                    "Reply was not usable (" + String.join("; ", issues) + ") - asking once more.");
            Attempt<R> second = ask(model, node, repair, maxTokens, provider, modelId, logLines);
            if (second.result() != null && (best == null || replaces(second.result(), best))) {
                best = second.result();
            }
        }
        return new Result<>(best, false);
    }
}
