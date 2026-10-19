package com.agenticai.core.agents.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Emits one {@code tool_call} before, and one {@code tool_result}/{@code error} after, every tool
 * invocation of a turn that asked for step events - the producer side of {@link
 * AgentStepEventEmitter}'s side channel.
 *
 * <h2>Why this wraps at {@code guardAndLog}, and nowhere else</h2>
 *
 * {@code AgentToolProvider#guardAndLog} is the single choke point every one of the twenty {@code
 * resolveXxxTools} sources routes through - MCP (static and dynamic), knowledge, code-intel, code
 * workspace, memory, graph memory, Google, Telegram, forward-attachment, device, Sarvam, E2B,
 * image/video/ document generation, web fetch, audio, delegation, scripts and custom code. Emitting
 * from {@link LoggingToolCallback} instead would have looked equivalent and been wrong in a way
 * that is hard to notice: it would still have covered every tool, but it sits INSIDE the chain,
 * below the layer that turns an exception into a friendly result - so the trace would have shown
 * raw exception text for failures the model never actually saw as failures. Wrapping OUTERMOST is
 * what makes the trace show what the MODEL saw.
 *
 * <p>Concretely, wrap order after this class is added, outermost to innermost: {@code
 * StepEventToolCallback -> TenantContextPropagatingToolCallback -> LoggingToolCallback ->
 * GuardedToolCallback -> raw}. Everything below has therefore already run by the time {@link #call}
 * observes a result, which is exactly what the safety requirement needs:
 *
 * <ul>
 *   <li>MCP results have been through {@code SanitizedMcpToolCallback} / {@link
 *       McpToolOutputSanitizer} (ANSI + invisible-character stripping, 64KB cap) - that wrapper is
 *       applied inside the discovery cache loader, i.e. below {@code guardAndLog}.
 *   <li>EVERY result, MCP or built-in, has been through {@code GuardedToolCallback}'s {@code
 *       GuardrailService#sanitizeToolOutput} (secret-shaped substrings already replaced with {@code
 *       [REDACTED]}).
 *   <li>A guardrail BLOCK and a structural-validation failure have already been converted into
 *       ordinary tool results, so they show up in the trace as results rather than as errors -
 *       matching what the model itself was handed.
 * </ul>
 *
 * {@link AgentStepEventRedactor} then applies the one pass none of the above provides -
 * argument-side credential redaction - plus a short preview cap. See that class for why arguments
 * needed their own pass.
 *
 * <h2>Known, deliberate coverage gap</h2>
 *
 * {@code AgentChatService#chat} appends {@code extraTools} AFTER tool resolution, un-wrapped by
 * {@code guardAndLog}. Its only producer is {@code QuickTaskService#buildAdditionalAgentTools},
 * which builds raw {@code DelegateToAgentToolCallback}s - and Quick Task is a {@code
 * collect(joining()).block()} caller, not an SSE consumer, so it can never ask for step events in
 * the first place. Those two tools therefore produce no events, which costs nothing; adding a
 * second wrap point just for them would add a second thing to keep in sync.
 *
 * <h2>Transparency</h2>
 *
 * This class never alters the value flowing through it, never swallows an exception, and never adds
 * a failure mode: {@link #call} returns {@code delegate.call}'s result verbatim and rethrows
 * verbatim. Emission itself is fail-open inside {@code AgentStepEventEmitter#emit}. A turn WITH
 * step events must behave identically to one without, apart from the extra chunks.
 */
final class StepEventToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AgentStepEventEmitter emitter;

    /**
     * Nullable: MCP Apps hook (app.mcp.apps.enabled). Null (flag off) leaves the event
     * byte-identical.
     */
    private final McpAppStepHook appHook;

    StepEventToolCallback(ToolCallback delegate, AgentStepEventEmitter emitter) {
        this(delegate, emitter, null);
    }

    StepEventToolCallback(
            ToolCallback delegate, AgentStepEventEmitter emitter, McpAppStepHook appHook) {
        this.delegate = delegate;
        this.emitter = emitter;
        this.appHook = appHook;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return emittingCall(toolInput, null);
    }

    /** Forwards Spring AI's {@link ToolContext} to the wrapped tool instead of dropping it. */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return emittingCall(toolInput, toolContext);
    }

    private String emittingCall(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        int stepId = emitter.toolCall(toolName, toolInput);
        long startedAt = System.nanoTime();
        try {
            String output =
                    toolContext != null
                            ? delegate.call(toolInput, toolContext)
                            : delegate.call(toolInput);
            emitter.toolResult(
                    stepId,
                    toolName,
                    output,
                    elapsedMillis(startedAt),
                    appPointer(toolName, toolInput, output));
            return output;
        } catch (RuntimeException e) {
            // Rethrown untouched - see the class javadoc's transparency note.
            // The message alone (never the stack) goes into the trace, and it
            // goes through the same redaction/cap a normal result does.
            String detail =
                    e.getMessage() != null && !e.getMessage().isBlank()
                            ? e.getMessage()
                            : e.getClass().getSimpleName();
            emitter.toolError(stepId, toolName, detail, elapsedMillis(startedAt));
            throw e;
        }
    }

    /**
     * Additive {@code app} pointer for the tool_result event; null unless MCP Apps is on and this
     * tool declares an app. Never throws.
     */
    private java.util.Map<String, Object> appPointer(
            String toolName, String toolInput, String output) {
        if (appHook == null) {
            return null;
        }
        try {
            return appHook.onToolResult(toolName, toolInput, output);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
