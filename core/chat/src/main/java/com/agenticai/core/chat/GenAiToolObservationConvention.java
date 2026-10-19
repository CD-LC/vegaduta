package com.agenticai.core.chat;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.ai.tool.observation.DefaultToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/**
 * OpenTelemetry GenAI semantic-convention shape for tool executions.
 *
 * <p>Spring AI's {@code DefaultToolCallingManager} already wraps every tool
 * call in an observation ({@code spring.ai.tool}); the micrometer-tracing OTel
 * bridge turns it into a span that is a child of whatever is current, i.e. the
 * model call inside the {@code invoke_agent} span (see {@link
 * LlmMetricsAdvisor}). This convention only renames and re-tags it to the GenAI
 * spec: span name {@code execute_tool <tool>}, {@code gen_ai.operation.name =
 * execute_tool}, {@code gen_ai.tool.name}, {@code gen_ai.tool.type = function}.
 *
 * <p><b>Content stays off.</b> The tool definition's description and JSON input
 * schema (high-cardinality, large, and tenant-authored for custom tools) that
 * the default convention attaches are dropped. Tool call arguments and results
 * are only ever added by Spring AI's {@code ToolCallingContentObservationFilter},
 * which this application does not register, and nothing here adds them - same
 * policy as {@code spring.ai.chat.observations.log-prompt/log-completion=false}
 * in application.yml.
 */
public class GenAiToolObservationConvention extends DefaultToolCallingObservationConvention {

    static final String OPERATION_NAME = "execute_tool";

    @Override
    public String getName() {
        return OPERATION_NAME;
    }

    @Override
    public String getContextualName(ToolCallingObservationContext context) {
        return OPERATION_NAME + " " + toolName(context);
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ToolCallingObservationContext context) {
        return KeyValues.of(
            KeyValue.of("gen_ai.operation.name", OPERATION_NAME),
            KeyValue.of("gen_ai.tool.name", toolName(context)),
            KeyValue.of("gen_ai.tool.type", "function"));
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ToolCallingObservationContext context) {
        return KeyValues.empty();
    }

    private static String toolName(ToolCallingObservationContext context) {
        if (context == null || context.getToolDefinition() == null || context.getToolDefinition().name() == null) {
            return "unknown";
        }
        return context.getToolDefinition().name();
    }
}
