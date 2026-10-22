package com.agenticai.core.characterisation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;

/**
 * P0 characterisation fixture (ADR-0012): a {@link ChatModel} that replays a fixed script and, like
 * every provider {@code ChatModel} in Spring AI 2 (the tool loop lives in the advisor, not the
 * model).
 *
 * <p>It also reproduces the one metering-relevant detail of those models: across loop iterations
 * the usage is accumulated with {@code UsageCalculator.getCumulativeUsage(current,
 * previousResponse)}, which returns a fresh {@code DefaultUsage(prompt, completion, total)} with NO
 * native usage. The scripted {@code Usage} of iteration N therefore reaches the advisors only as a
 * cumulative sum, and provider-specific cache counters (Anthropic cache read/write) survive only on
 * a turn that made a single model call.
 *
 * <p><b>P3 (done).</b> In Spring AI 2 the loop moved out of the model into the ChatClient's {@code
 * ToolCallingAdvisor}: this model now just returns one scripted response per call (tool calls
 * included) with that call's OWN usage, like the real OpenAI/Anthropic models; the advisor executes
 * the tools and accumulates usage. The script format and the step builders are unchanged.
 */
public final class ScriptedChatModel implements ChatModel {

    /**
     * One model response: text chunks (streaming), optional tool calls, and this call's own usage.
     */
    public record Step(
            List<String> textChunks, List<AssistantMessage.ToolCall> toolCalls, Usage usage) {
        public Step {
            textChunks = List.copyOf(textChunks);
            toolCalls = List.copyOf(toolCalls);
        }

        public String text() {
            return String.join("", textChunks);
        }
    }

    public static Step text(Usage usage, String... chunks) {
        return new Step(Arrays.asList(chunks), List.of(), usage);
    }

    public static Step toolCalls(Usage usage, AssistantMessage.ToolCall... calls) {
        return new Step(List.of(), Arrays.asList(calls), usage);
    }

    public static AssistantMessage.ToolCall call(String id, String toolName, String jsonArguments) {
        return new AssistantMessage.ToolCall(id, "function", toolName, jsonArguments);
    }

    private final List<Step> script;
    private final ToolCallingManager toolCallingManager;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    public ScriptedChatModel(List<Step> script, ToolCallingManager toolCallingManager) {
        this.script = List.copyOf(script);
        this.toolCallingManager = toolCallingManager;
    }

    /** How many times the (scripted) provider was called. */
    public int modelCalls() {
        return modelCalls.get();
    }

    /** Every prompt the model received, in order (the tool-loop conversation grows across them). */
    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    /**
     * The manager the ChatClient's ToolCallingAdvisor must be seated with (Spring AI 2: the model
     * no longer runs tools).
     */
    public ToolCallingManager toolCallingManager() {
        return toolCallingManager;
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    // ---------------------------------------------------------------- call

    @Override
    public ChatResponse call(Prompt prompt) {
        Step step = nextStep(prompt);
        // One provider call = its own usage. Summing across the loop is the ToolCallingAdvisor's
        // job.
        return response(step, step.text(), step.usage(), true);
    }

    // ------------------------------------------------------------- stream

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // Lazy, like a real provider request: the "HTTP call" only happens on subscription.
        return Flux.defer(
                () -> {
                    Step step = nextStep(prompt);
                    List<Flux<ChatResponse>> parts = new ArrayList<>();
                    for (String chunk : step.textChunks()) {
                        // Provider streams carry usage only on the last chunk; earlier chunks
                        // report none.
                        parts.add(Flux.just(response(step, chunk, new EmptyUsage(), false)));
                    }
                    parts.add(Flux.just(response(step, "", step.usage(), true)));
                    return Flux.concat(parts);
                });
    }

    // ------------------------------------------------------------ helpers

    private Step nextStep(Prompt prompt) {
        int index = modelCalls.getAndIncrement();
        prompts.add(prompt);
        if (index >= script.size()) {
            throw new IllegalStateException(
                    "ScriptedChatModel exhausted: call #"
                            + (index + 1)
                            + " but the script has "
                            + script.size()
                            + " steps (a runaway tool loop?)");
        }
        return script.get(index);
    }

    private static ChatResponse response(
            Step step, String text, Usage usage, boolean withToolCalls) {
        AssistantMessage.Builder message = AssistantMessage.builder().content(text);
        if (withToolCalls) {
            message.toolCalls(step.toolCalls());
        }
        return new ChatResponse(
                List.of(new Generation(message.build())),
                ChatResponseMetadata.builder().model("scripted-model").usage(usage).build());
    }
}
