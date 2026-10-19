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
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.UsageCalculator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * P0 characterisation fixture (ADR-0012): a {@link ChatModel} that replays a fixed script and, like
 * every provider {@code ChatModel} in Spring AI 1.1.x (OpenAiChatModel, AnthropicChatModel, ...),
 * runs the tool-calling loop INTERNALLY: when a scripted step carries tool calls and internal
 * execution is enabled it hands them to the {@link ToolCallingManager} and calls itself again with
 * the extended conversation.
 *
 * <p>It also reproduces the one metering-relevant detail of those models: across loop iterations the
 * usage is accumulated with {@code UsageCalculator.getCumulativeUsage(current, previousResponse)},
 * which returns a fresh {@code DefaultUsage(prompt, completion, total)} with NO native usage. The
 * scripted {@code Usage} of iteration N therefore reaches the advisors only as a cumulative
 * sum, and provider-specific cache counters (Anthropic cache read/write) survive only on a turn
 * that made a single model call.
 *
 * <p><b>P3 touch point.</b> In Spring AI 2 the loop moves out of the model into the ChatClient's
 * {@code ToolCallingAdvisor}, and the model just returns the tool calls. This class is then the
 * only thing to adapt: drop the internal loop and the cumulative-usage step (the advisor
 * accumulates), keep the script format. The tests use only the {@code text}/{@code toolCalls}
 * step builders and {@link ToolLoopHarness}, so they do not change.
 */
public final class ScriptedChatModel implements ChatModel {

    /** One model response: text chunks (streaming), optional tool calls, and this call's own usage. */
    public record Step(List<String> textChunks, List<AssistantMessage.ToolCall> toolCalls, Usage usage) {
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
    private final DefaultToolExecutionEligibilityPredicate eligibility = new DefaultToolExecutionEligibilityPredicate();

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

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    // ---------------------------------------------------------------- call

    @Override
    public ChatResponse call(Prompt prompt) {
        return internalCall(prompt, null);
    }

    private ChatResponse internalCall(Prompt prompt, ChatResponse previous) {
        Step step = nextStep(prompt);
        Usage cumulative = UsageCalculator.getCumulativeUsage(step.usage(), previous);
        ChatResponse response = response(step, step.text(), cumulative, true);
        if (eligibility.test(prompt.getOptions(), response)) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            if (result.returnDirect()) {
                return ChatResponse.builder()
                    .from(response)
                    .generations(ToolExecutionResult.buildGenerations(result))
                    .build();
            }
            return internalCall(new Prompt(result.conversationHistory(), prompt.getOptions()), response);
        }
        return response;
    }

    // ------------------------------------------------------------- stream

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> internalStream(prompt, null));
    }

    private Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previous) {
        // Lazy, like a real provider request: the "HTTP call" only happens on subscription, so a
        // cancelled subscription never reaches the next model call.
        return Flux.create(sink -> {
            if (sink.isCancelled()) {
                return;
            }
            Disposable running = Flux.defer(() -> streamStep(prompt, previous))
                .subscribe(sink::next, sink::error, sink::complete);
            sink.onDispose(running);
        });
    }

    private Flux<ChatResponse> streamStep(Prompt prompt, ChatResponse previous) {
        Step step = nextStep(prompt);
        List<Flux<ChatResponse>> parts = new ArrayList<>();
        for (String chunk : step.textChunks()) {
            // Provider streams carry usage only on the last chunk; earlier chunks report none.
            parts.add(Flux.just(response(step, chunk, new EmptyUsage(), false)));
        }
        Usage cumulative = UsageCalculator.getCumulativeUsage(step.usage(), previous);
        ChatResponse last = response(step, "", cumulative, true);
        if (eligibility.test(prompt.getOptions(), last)) {
            ChatResponse toolCallResponse = response(step, step.text(), cumulative, true);
            // The tool-call chunk itself is not emitted downstream; tools run off the event loop.
            parts.add(Flux.defer(() -> {
                ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, toolCallResponse);
                if (result.returnDirect()) {
                    return Flux.just(ChatResponse.builder()
                        .from(last)
                        .generations(ToolExecutionResult.buildGenerations(result))
                        .build());
                }
                return internalStream(new Prompt(result.conversationHistory(), prompt.getOptions()), toolCallResponse);
            }).subscribeOn(Schedulers.boundedElastic()));
        } else {
            parts.add(Flux.just(last));
        }
        return Flux.concat(parts);
    }

    // ------------------------------------------------------------ helpers

    private Step nextStep(Prompt prompt) {
        int index = modelCalls.getAndIncrement();
        prompts.add(prompt);
        if (index >= script.size()) {
            throw new IllegalStateException("ScriptedChatModel exhausted: call #" + (index + 1)
                + " but the script has " + script.size() + " steps (a runaway tool loop?)");
        }
        return script.get(index);
    }

    private static ChatResponse response(Step step, String text, Usage usage, boolean withToolCalls) {
        AssistantMessage.Builder message = AssistantMessage.builder().content(text);
        if (withToolCalls) {
            message.toolCalls(step.toolCalls());
        }
        return new ChatResponse(
            List.of(new Generation(message.build())),
            ChatResponseMetadata.builder().model("scripted-model").usage(usage).build());
    }
}
