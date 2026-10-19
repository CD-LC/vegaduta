package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Gates a delegation tool on the turn's shared {@link RunBudget}: once the cap is hit the tool is
 * not executed and the model receives a structured stop reason instead; a {@code guardrail} step
 * event makes the stop visible in the trace as well. When {@code recordUsage} is true the call is
 * also charged to the budget as a delegate call (tools that charge themselves, such as
 * delegate_parallel, pass false).
 *
 * <p>Wrap the RAW tool (inside the guard/log/step-event chain), so the step-event emitter is bound
 * to this thread while the stop is raised.
 */
final class RunBudgetToolCallback implements ToolCallback {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ToolCallback delegate;
    private final RunBudget budget;
    private final boolean recordUsage;

    RunBudgetToolCallback(ToolCallback delegate, RunBudget budget, boolean recordUsage) {
        this.delegate = delegate;
        this.budget = budget;
        this.recordUsage = recordUsage;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        if (budget.isExceeded()) {
            AgentStepEventEmitter.current()
                    .ifPresent(
                            e ->
                                    e.guardrail(
                                            delegate.getToolDefinition().name(),
                                            RunBudget.STOP_REASON));
            return stopJson(budget);
        }
        String out = delegate.call(toolInput);
        if (recordUsage) {
            budget.recordDelegate(toolInput, out);
        }
        return out;
    }

    static String stopJson(RunBudget budget) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", budget.stopMessage());
        body.put("stopReason", RunBudget.STOP_REASON);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"stopReason\":\"" + RunBudget.STOP_REASON + "\"}";
        }
    }
}
