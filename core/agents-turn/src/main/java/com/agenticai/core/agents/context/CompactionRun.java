package com.agenticai.core.agents.context;

import com.agenticai.core.agents.context.ContextCompactionEngine.Params;
import com.agenticai.core.agents.context.ContextCompactionEngine.Result;
import com.agenticai.core.agents.context.ContextCompactionEngine.Summarizer;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.chat.ToolLoopHistoryTransform;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

/**
 * Everything one chat run needs to keep its prompt inside the model's context window: the settings,
 * the model window, the tool-schema overhead, a guarded summariser and the step-event emitter. One
 * instance per turn, created by {@link ChatContextCompaction#openRun} only while {@code
 * app.context.compaction.enabled} is on - so a turn with the flag off never sees any of this.
 *
 * <p>It acts at two points, both through {@link ContextCompactionEngine}:
 *
 * <ul>
 *   <li>{@link #prepareMessages} - once before the first model call, over the replayed history plus
 *       the memory block (the conversation across turns). Session history stores only
 *       user/assistant text, so across turns the only available action is summarising old turns;
 *       there are no stored tool results to clear.
 *   <li>{@link #transform} - after every tool round inside the Spring AI tool loop (the manager
 *       reads this object from the request's tool context), where the dominant growth is tool
 *       results.
 * </ul>
 *
 * <p>Fail-open throughout: any exception leaves the messages untouched. The summariser is wrapped
 * so that one failure or timeout stops further attempts in this run and a run can summarise at most
 * {@code maxSummaries} times.
 */
public final class CompactionRun implements ToolLoopHistoryTransform {

    private static final Logger log = LoggerFactory.getLogger(CompactionRun.class);

    private final ContextCompactionEngine engine;
    private final Params params;
    private final String scopeModel;
    private final int scopeWindowTokens;
    private final int defaultWindowTokens;
    private final int toolSchemaTokens;
    private final Summarizer summarizer;
    private final AgentStepEventEmitter stepEvents;
    private final MeterRegistry meterRegistry;

    private final int maxSummaries;
    private final AtomicInteger summariesUsed = new AtomicInteger();
    private final AtomicBoolean summarizerGaveUp = new AtomicBoolean();

    /**
     * @param params the settings; {@code windowTokens} is the window of {@code scopeModel}
     * @param scopeModel the model the window was resolved for, or null
     * @param defaultWindowTokens used for a different model seen mid-run (hosted-pool failover)
     * @param toolSchemaTokens estimated tokens of this turn's tool definitions
     * @param summarizer produces a summary; null disables compaction (clearing still works)
     * @param stepEvents null or disabled = no step events
     */
    public CompactionRun(
            ContextCompactionEngine engine,
            Params params,
            String scopeModel,
            int defaultWindowTokens,
            int toolSchemaTokens,
            Summarizer summarizer,
            AgentStepEventEmitter stepEvents,
            int maxSummaries) {
        this(
                engine,
                params,
                scopeModel,
                defaultWindowTokens,
                toolSchemaTokens,
                summarizer,
                stepEvents,
                maxSummaries,
                null);
    }

    /**
     * @param meterRegistry the Spring registry the compaction counters are recorded on; null = no
     *     counters
     */
    public CompactionRun(
            ContextCompactionEngine engine,
            Params params,
            String scopeModel,
            int defaultWindowTokens,
            int toolSchemaTokens,
            Summarizer summarizer,
            AgentStepEventEmitter stepEvents,
            int maxSummaries,
            MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.engine = engine;
        this.params = params;
        this.scopeModel = scopeModel;
        this.scopeWindowTokens = params.windowTokens();
        this.defaultWindowTokens = defaultWindowTokens;
        this.toolSchemaTokens = Math.max(toolSchemaTokens, 0);
        this.summarizer = summarizer == null ? null : guarded(summarizer);
        this.stepEvents = stepEvents;
        this.maxSummaries = Math.max(maxSummaries, 0);
    }

    /**
     * The conversation view for the first model call.
     *
     * @param messages the memory block and replayed history (the system prompt and the new user
     *     message are not in the list - see {@code pendingTokens})
     * @param pendingTokens estimated tokens of the system prompt and the user message that will be
     *     added around {@code messages}
     */
    public List<Message> prepareMessages(List<Message> messages, int pendingTokens) {
        return run(messages, toolSchemaTokens + Math.max(pendingTokens, 0), params, "pre-turn");
    }

    /**
     * Convenience for the chat path: estimates the pending system prompt and user message itself.
     */
    public List<Message> prepareMessages(
            List<Message> messages, String systemPrompt, String userMessage) {
        long pending =
                SessionMemoryService.estimateTokens(systemPrompt)
                        + SessionMemoryService.estimateTokens(userMessage);
        return prepareMessages(messages, (int) Math.min(pending, Integer.MAX_VALUE));
    }

    @Override
    public List<Message> transform(List<Message> history, String modelId) {
        Params effective = params;
        if (modelId != null && !modelId.equals(scopeModel)) {
            // Hosted-pool failover moved the run to another model: size it by name, not by the
            // first
            // model's window (no I/O on the tool-loop thread).
            int window =
                    Math.min(
                            scopeWindowTokens,
                            ModelContextWindows.heuristic(
                                    modelId, defaultWindowTokens, params.tuning().getWindowRules()));
            effective =
                    new Params(
                            window,
                            params.clearAt(),
                            params.compactAt(),
                            params.keepRecentToolResults(),
                            params.keepRecentMessages(),
                            params.summaryMaxTokens(),
                            params.restoreToolOffered(),
                            params.tuning(),
                            params.texts());
        }
        return run(history, toolSchemaTokens, effective, "tool-loop");
    }

    private List<Message> run(
            List<Message> messages, int overheadTokens, Params effective, String phase) {
        try {
            Result result = engine.apply(messages, overheadTokens, effective, summarizer);
            if (!result.changed()) {
                return messages;
            }
            report(result, phase);
            return result.messages();
        } catch (RuntimeException e) {
            log.warn(
                    "Context compaction ({}) failed - sending the messages unchanged: {}",
                    phase,
                    e.toString());
            return messages;
        }
    }

    private void report(Result result, String phase) {
        log.debug(
                "Context compaction ({}): {} -> {} tokens, cleared {} tool result(s), summarised {} message(s)",
                phase,
                result.tokensBefore(),
                result.tokensAfter(),
                result.clearedToolResults(),
                result.compactedMessages());
        try {
            if (meterRegistry != null && result.clearedToolResults() > 0) {
                meterRegistry
                        .counter("chat.context.compaction", "action", "clear")
                        .increment(result.clearedToolResults());
            }
            if (meterRegistry != null && result.compactedMessages() > 0) {
                meterRegistry.counter("chat.context.compaction", "action", "compact").increment();
            }
            if (stepEvents != null && stepEvents.isEnabled()) {
                stepEvents.contextCompacted(
                        result.tokensBefore(),
                        result.tokensAfter(),
                        result.clearedToolResults(),
                        result.compactedMessages());
            }
        } catch (RuntimeException e) {
            // Observability must never cost the turn.
            log.debug("Could not record context compaction telemetry", e);
        }
    }

    /**
     * Returns a client whose requests carry this run in their tool context, which is how the
     * tool-loop manager finds it. The original client is returned untouched if it cannot be
     * re-built.
     */
    public ChatClient attach(ChatClient client) {
        if (client == null) {
            return null;
        }
        try {
            return client.mutate()
                    .defaultToolContext(
                            Map.<String, Object>of(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY, this))
                    .build();
        } catch (RuntimeException e) {
            log.warn(
                    "Could not attach context compaction to the chat client - this turn runs without it: {}",
                    e.toString());
            return client;
        }
    }

    /**
     * Wraps the summariser: one failure ends attempts for this run, and attempts are capped per
     * run.
     */
    private Summarizer guarded(Summarizer delegate) {
        return (existing, turns) -> {
            if (summarizerGaveUp.get() || summariesUsed.get() >= maxSummaries) {
                return Optional.empty();
            }
            summariesUsed.incrementAndGet();
            Optional<String> produced = delegate.summarize(existing, turns);
            if (produced == null || produced.isEmpty()) {
                summarizerGaveUp.set(true);
                return Optional.empty();
            }
            return produced;
        };
    }

    /** Estimated tokens of a tool list's definitions (name, description, input schema). */
    public static int toolSchemaTokens(List<ToolCallback> tools) {
        if (tools == null) {
            return 0;
        }
        long total = 0;
        for (ToolCallback tool : tools) {
            try {
                var definition = tool.getToolDefinition();
                total +=
                        SessionMemoryService.estimateTokens(definition.name())
                                + SessionMemoryService.estimateTokens(definition.description())
                                + SessionMemoryService.estimateTokens(definition.inputSchema());
            } catch (RuntimeException e) {
                // A tool whose definition cannot be read is simply not counted.
            }
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }
}
