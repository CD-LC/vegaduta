package com.agenticai.core.chat;

import com.agenticai.core.security.concurrent.TenantAwareSchedulers;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.UsageCalculator;
import org.springframework.ai.tool.ToolCallback;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps any {@link ChatModel} so that a tool call the model wrote as <em>text</em> (see {@link
 * TextToolCallParser}) is executed through the same {@link ToolCallingManager} loop a structured
 * one goes through, and the model is called again with the tool result - instead of the raw JSON
 * reaching the user as the answer.
 *
 * <p>This is the provider-independent half of "agents work on free models". It sits between the
 * {@code ChatClient} and the provider model in {@link LlmModelSupport#chatClientBuilder}, so every
 * factory in this package, and any provider added later, gets it without wiring; the provider's
 * own structured tool loop runs underneath, untouched.
 *
 * <p><b>It is a no-op for every reply that is not a text-form tool call.</b> Non-streaming: the
 * response is returned as-is unless its text parses as calls to offered tools. Streaming: chunks
 * are held back only while the text seen so far could still be such a call ({@link
 * TextToolCallParser#couldBeToolCall}: first non-blank character {@code {}, {@code [}, a code
 * fence or a known tag, up to {@code holdBackChars}); a reply that starts with a letter is passed
 * through on its first token, and a held-back reply that turns out not to be a call is released
 * unchanged at completion. A request that offers no tools, or that is marked {@link
 * CallerRunsToolLoop}, is passed straight through.
 *
 * <p>The recursion is bounded by {@code maxRecoveriesPerInvocation}: a model that keeps answering
 * in text after that many recovered rounds has its reply released as-is, as before. Usage on the
 * follow-up call is made cumulative with the recovered call's usage, the way Spring AI's own
 * internal tool loop does, so the metrics advisor above counts both.
 *
 * <p>Tool execution after a recovery hops to {@code boundedElastic}, exactly as Spring AI's chat
 * models do before running tools (synchronous tool code must not block a reactor thread). Every
 * platform tool callback carries its tenant itself ({@code TenantContextPropagatingToolCallback}
 * in {@code core.agents}), which is also what makes the provider model's own hop safe; see {@code
 * archrules/async-hop-allowlist.txt}.
 */
public final class TextToolCallRecoveringChatModel implements ChatModel {

    private static final Logger log =
            LoggerFactory.getLogger(TextToolCallRecoveringChatModel.class);

    static final String RECOVERY_METRIC = "chat.tool.text_call_recovery";

    /** Marker on the converted assistant message, for step-event consumers and tests. */
    static final String RECOVERED_PROPERTY = "recoveredFromText";

    public static final int DEFAULT_MAX_RECOVERIES = 3;
    public static final int DEFAULT_HOLD_BACK_CHARS = 16_384;

    private final ChatModel delegate;
    private final ToolCallingManager toolCallingManager;
    private final ObjectMapper objectMapper;
    private final int maxRecoveries;
    private final int holdBackChars;
    private final MeterRegistry meterRegistry;

    public TextToolCallRecoveringChatModel(
            ChatModel delegate,
            ToolCallingManager toolCallingManager,
            ObjectMapper objectMapper,
            int maxRecoveries,
            int holdBackChars,
            MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.toolCallingManager = toolCallingManager;
        this.objectMapper = objectMapper;
        this.maxRecoveries = maxRecoveries > 0 ? maxRecoveries : DEFAULT_MAX_RECOVERIES;
        this.holdBackChars = holdBackChars > 0 ? holdBackChars : DEFAULT_HOLD_BACK_CHARS;
        this.meterRegistry = meterRegistry;
    }

    /** The wrapped provider model (tests and wire-parity checks). */
    public ChatModel delegate() {
        return delegate;
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    // --- non-streaming ---------------------------------------------------------

    @Override
    public ChatResponse call(Prompt prompt) {
        return callWithRecovery(prompt, 0, null);
    }

    private ChatResponse callWithRecovery(Prompt prompt, int round, ChatResponse previous) {
        ChatResponse response = delegate.call(prompt);
        if (previous != null) {
            response = withCumulativeUsage(response, previous);
        }
        Set<String> known = knownToolNames(prompt);
        if (known.isEmpty() || response == null || hasToolCalls(response)) {
            return response;
        }
        List<AssistantMessage.ToolCall> calls =
                TextToolCallParser.parse(textOf(response), known, objectMapper);
        if (calls.isEmpty()) {
            return response;
        }
        if (round >= maxRecoveries) {
            giveUp(round, calls);
            return response;
        }
        ChatResponse converted = convert(response, calls, round);
        ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, converted);
        if (result.returnDirect()) {
            return direct(converted, result);
        }
        return callWithRecovery(
                new Prompt(result.conversationHistory(), prompt.getOptions()),
                round + 1,
                converted);
    }

    // --- streaming --------------------------------------------------------------

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Set<String> known = knownToolNames(prompt);
        if (known.isEmpty()) {
            return delegate.stream(prompt);
        }
        return streamWithRecovery(prompt, known, 0, null);
    }

    private Flux<ChatResponse> streamWithRecovery(
            Prompt prompt, Set<String> known, int round, ChatResponse previous) {
        return Flux.defer(
                () -> {
                    HoldBack hold = new HoldBack();
                    Flux<ChatResponse> upstream = delegate.stream(prompt);
                    if (previous != null) {
                        upstream = upstream.map(chunk -> withCumulativeUsage(chunk, previous));
                    }
                    return upstream.concatMap(hold::onNext)
                            // A provider failure mid-reply: whatever was held is released first, so
                            // the
                            // partial text reaches the caller exactly as it did before this wrapper
                            // existed.
                            .onErrorResume(
                                    error -> hold.release(null).concatWith(Flux.error(error)))
                            .concatWith(Flux.defer(() -> hold.onComplete(prompt, known, round)));
                });
    }

    /**
     * Per-subscription state: chunks are buffered while the text so far could still be a text-form
     * tool call, released the moment it cannot (or the buffer cap is hit), and inspected once at
     * completion. {@code concatMap} serialises {@link #onNext}, so no synchronisation is needed.
     */
    private final class HoldBack {
        private final List<ChatResponse> buffered = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private ChatResponse last;
        private boolean passthrough;

        Flux<ChatResponse> onNext(ChatResponse chunk) {
            if (passthrough) {
                return Flux.just(chunk);
            }
            if (hasToolCalls(chunk)) {
                passthrough = true;
                return release(chunk);
            }
            buffered.add(chunk);
            last = chunk;
            text.append(textOf(chunk));
            if (text.length() > holdBackChars || !TextToolCallParser.couldBeToolCall(text)) {
                passthrough = true;
                return release(null);
            }
            return Flux.empty();
        }

        Flux<ChatResponse> onComplete(Prompt prompt, Set<String> known, int round) {
            if (passthrough) {
                return Flux.empty();
            }
            List<AssistantMessage.ToolCall> calls =
                    TextToolCallParser.parse(text.toString(), known, objectMapper);
            if (calls.isEmpty()) {
                return release(null);
            }
            if (round >= maxRecoveries) {
                giveUp(round, calls);
                return release(null);
            }
            ChatResponse converted = convert(last, calls, round);
            return Mono.fromCallable(() -> toolCallingManager.executeToolCalls(prompt, converted))
                    .subscribeOn(TenantAwareSchedulers.boundedElastic())
                    .flatMapMany(
                            result ->
                                    result.returnDirect()
                                            ? Flux.just(direct(converted, result))
                                            : streamWithRecovery(
                                                    new Prompt(
                                                            result.conversationHistory(),
                                                            prompt.getOptions()),
                                                    known,
                                                    round + 1,
                                                    converted));
        }

        Flux<ChatResponse> release(ChatResponse andThen) {
            passthrough = true;
            List<ChatResponse> out = new ArrayList<>(buffered);
            buffered.clear();
            if (andThen != null) {
                out.add(andThen);
            }
            return Flux.fromIterable(out);
        }
    }

    // --- shared ------------------------------------------------------------------

    /** The text reply re-expressed as the structured tool-call response the loop expects. */
    private ChatResponse convert(
            ChatResponse source, List<AssistantMessage.ToolCall> calls, int round) {
        List<String> names = calls.stream().map(AssistantMessage.ToolCall::name).toList();
        log.info(
                "Model answered with a text-form tool call instead of a structured one - executing"
                    + " it (round={} tools={})",
                round + 1,
                names);
        increment("outcome", "recovered");
        AssistantMessage assistant =
                AssistantMessage.builder()
                        .content("")
                        .properties(Map.of(RECOVERED_PROPERTY, Boolean.TRUE))
                        .toolCalls(calls)
                        .build();
        Generation generation =
                new Generation(
                        assistant,
                        ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build());
        ChatResponse.Builder builder = ChatResponse.builder();
        if (source != null) {
            builder.from(source);
        }
        return builder.generations(List.of(generation)).build();
    }

    private static ChatResponse direct(ChatResponse converted, ToolExecutionResult result) {
        return ChatResponse.builder()
                .from(converted)
                .generations(ToolExecutionResult.buildGenerations(result))
                .build();
    }

    private void giveUp(int round, List<AssistantMessage.ToolCall> calls) {
        log.warn(
                "Model still answers with text-form tool calls after {} recovered round(s) -"
                    + " releasing the reply as text (tools={})",
                round,
                calls.stream().map(AssistantMessage.ToolCall::name).toList());
        increment("outcome", "limit");
    }

    /**
     * {@code chunk} with its usage made cumulative over {@code previous} (the recovered call), the
     * way the provider models accumulate across their own internal tool rounds. Other metadata is
     * carried over; the chunk's own generations are untouched.
     */
    static ChatResponse withCumulativeUsage(ChatResponse chunk, ChatResponse previous) {
        if (chunk == null || previous == null || previous.getMetadata() == null) {
            return chunk;
        }
        ChatResponseMetadata metadata = chunk.getMetadata();
        Usage current =
                metadata == null || metadata.getUsage() == null
                        ? new EmptyUsage()
                        : metadata.getUsage();
        Usage cumulative = UsageCalculator.getCumulativeUsage(current, previous);
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder().usage(cumulative);
        if (metadata != null) {
            builder.id(metadata.getId())
                    .model(metadata.getModel())
                    .rateLimit(metadata.getRateLimit())
                    .promptMetadata(metadata.getPromptMetadata());
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                builder.keyValue(entry.getKey(), entry.getValue());
            }
        }
        return ChatResponse.builder().from(chunk).metadata(builder.build()).build();
    }

    /** Offered tool names, or empty when this request cannot have a tool call to recover. */
    static Set<String> knownToolNames(Prompt prompt) {
        Set<String> known = new LinkedHashSet<>();
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return known;
        }
        if (CallerRunsToolLoop.isSet(options)) {
            // The caller runs the tool loop itself; a converted call would never be executed.
            return known;
        }
        if (options.getToolCallbacks() != null) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                known.add(callback.getToolDefinition().name());
            }
        }
        return known;
    }

    private static boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return false;
        }
        for (Generation generation : response.getResults()) {
            if (generation != null
                    && generation.getOutput() != null
                    && generation.getOutput().hasToolCalls()) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Generation generation : response.getResults()) {
            if (generation != null
                    && generation.getOutput() != null
                    && generation.getOutput().getText() != null) {
                text.append(generation.getOutput().getText());
            }
        }
        return text.toString();
    }

    private void increment(String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(RECOVERY_METRIC, tags).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record metric {}", RECOVERY_METRIC, e);
        }
    }
}
