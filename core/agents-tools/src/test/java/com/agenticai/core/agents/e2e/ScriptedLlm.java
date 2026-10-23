package com.agenticai.core.agents.e2e;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * The ONLY fake in the approve-then-complete end-to-end harness: a {@link ChatModel} standing in
 * for the LLM provider. Everything above it (the ChatClient, Spring AI's tool loop, the platform's
 * guard chain, memory, approval) is production code.
 *
 * <p>Like every provider {@code ChatModel} in Spring AI 1.1 it runs the tool-calling loop
 * INTERNALLY: when a reply carries tool calls it hands them to the {@link ToolCallingManager} and
 * calls itself again with the extended conversation, so {@link #requests()} records one entry per
 * provider round trip, with the exact messages that round trip was sent.
 *
 * <p>Replies come from either a fixed sequence ({@link #sequence}) or a {@link Responder} that
 * reads the request. The responder form is what lets a test model "what a sensible LLM does with
 * what it was shown" (call the tool when it has no result, answer when it has one) instead of
 * hard-coding the outcome, so a defect that hides information from the model shows up as the model
 * behaving badly, which is what happens in production.
 *
 * <p>A reply can carry text, tool calls and a provider finish reason ({@code stop}, {@code length}
 * for truncation, {@code content_filter} for a safety stop, {@code tool_calls}); the finish reason
 * reaches {@link ChatResponse#getResult()} metadata exactly as a provider sets it.
 */
public final class ScriptedLlm implements ChatModel {

    /** What the model decided to say or do for one provider round trip. */
    public record Reply(
            String text, List<AssistantMessage.ToolCall> toolCalls, String finishReason) {
        public Reply {
            text = text == null ? "" : text;
            toolCalls = List.copyOf(toolCalls);
        }
    }

    public static Reply text(String text) {
        return new Reply(text, List.of(), "stop");
    }

    /** A reply the provider cut off at the token limit ({@code finish_reason=length}). */
    public static Reply truncated(String partialText) {
        return new Reply(partialText, List.of(), "length");
    }

    /** A reply the provider's safety system stopped ({@code finish_reason=content_filter}). */
    public static Reply safetyStopped(String partialText) {
        return new Reply(partialText, List.of(), "content_filter");
    }

    public static Reply callTool(String callId, String toolName, String jsonArguments) {
        return new Reply(
                "",
                List.of(new AssistantMessage.ToolCall(callId, "function", toolName, jsonArguments)),
                "tool_calls");
    }

    /** One provider round trip as the model saw it. */
    public record Request(int number, List<Message> messages, List<String> offeredTools) {

        public Request {
            messages = List.copyOf(messages);
            offeredTools = List.copyOf(offeredTools);
        }

        /** Every message's text, tool responses included, one per line with its role. */
        public String transcript() {
            StringBuilder out = new StringBuilder();
            for (Message m : messages) {
                out.append('[')
                        .append(m.getMessageType())
                        .append("] ")
                        .append(textOf(m))
                        .append('\n');
            }
            return out.toString();
        }

        public boolean mentions(String fragment) {
            return transcript().contains(fragment);
        }

        /**
         * The conversation without the system messages (which are long and constant), for failure
         * messages: user, assistant and tool messages in order.
         */
        public String conversation() {
            StringBuilder out = new StringBuilder();
            for (Message m : messages) {
                if (m.getMessageType() != MessageType.SYSTEM) {
                    out.append('[')
                            .append(m.getMessageType())
                            .append("] ")
                            .append(textOf(m))
                            .append('\n');
                }
            }
            return out.toString();
        }

        /**
         * The texts of the tool-result messages in this request (what tools returned to the model).
         */
        public List<String> toolResults() {
            List<String> results = new ArrayList<>();
            for (Message m : messages) {
                if (m instanceof ToolResponseMessage tr) {
                    tr.getResponses().forEach(r -> results.add(r.responseData()));
                }
            }
            return results;
        }

        public List<String> userTexts() {
            return messages.stream()
                    .filter(m -> m instanceof UserMessage)
                    .map(ScriptedLlm::textOf)
                    .toList();
        }

        /** True when the model has already been shown a tool result in this conversation. */
        public boolean hasToolResult() {
            return !toolResults().isEmpty();
        }
    }

    /** Decides the model's reply from the request it was sent. */
    @FunctionalInterface
    public interface Responder extends Function<Request, Reply> {}

    private static String textOf(Message m) {
        if (m instanceof ToolResponseMessage tr) {
            StringBuilder text = new StringBuilder();
            tr.getResponses()
                    .forEach(r -> text.append(r.name()).append(" -> ").append(r.responseData()));
            return text.toString();
        }
        if (m instanceof AssistantMessage am && am.hasToolCalls()) {
            StringBuilder text = new StringBuilder(am.getText() == null ? "" : am.getText());
            am.getToolCalls()
                    .forEach(
                            c ->
                                    text.append(" <call ")
                                            .append(c.name())
                                            .append(' ')
                                            .append(c.arguments())
                                            .append('>'));
            return text.toString();
        }
        return m.getText() == null ? "" : m.getText();
    }

    private final Responder responder;
    private final ToolCallingManager toolCallingManager;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final DefaultToolExecutionEligibilityPredicate eligibility =
            new DefaultToolExecutionEligibilityPredicate();

    public ScriptedLlm(Responder responder, ToolCallingManager toolCallingManager) {
        this.responder = responder;
        this.toolCallingManager = toolCallingManager;
    }

    /** Replays {@code replies} in order; a call past the end fails loudly (a runaway loop). */
    public static ScriptedLlm sequence(ToolCallingManager manager, Reply... replies) {
        List<Reply> script = Arrays.asList(replies);
        return new ScriptedLlm(
                request -> {
                    if (request.number() > script.size()) {
                        throw new IllegalStateException(
                                "ScriptedLlm exhausted: call #"
                                        + request.number()
                                        + " but the script has "
                                        + script.size()
                                        + " replies (a runaway tool loop?)");
                    }
                    return script.get(request.number() - 1);
                },
                manager);
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    /** Every provider round trip so far, in order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request lastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    // ----------------------------------------------------------------- blocking

    @Override
    public ChatResponse call(Prompt prompt) {
        return internalCall(prompt);
    }

    private ChatResponse internalCall(Prompt prompt) {
        Reply reply = nextReply(prompt);
        ChatResponse response = response(reply, reply.text(), true);
        if (eligibility.test(optionsOf(prompt), response)) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            if (result.returnDirect()) {
                return ChatResponse.builder()
                        .from(response)
                        .generations(ToolExecutionResult.buildGenerations(result))
                        .build();
            }
            return internalCall(new Prompt(result.conversationHistory(), optionsOf(prompt)));
        }
        return response;
    }

    // ---------------------------------------------------------------- streaming

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> internalStream(prompt));
    }

    private Flux<ChatResponse> internalStream(Prompt prompt) {
        // Lazy like a real provider request: the "HTTP call" happens on subscription, so a
        // cancelled subscription never reaches the next round trip.
        return Flux.create(
                sink -> {
                    if (sink.isCancelled()) {
                        return;
                    }
                    Disposable running =
                            Flux.defer(() -> streamStep(prompt))
                                    .subscribe(sink::next, sink::error, sink::complete);
                    sink.onDispose(running);
                });
    }

    private Flux<ChatResponse> streamStep(Prompt prompt) {
        Reply reply = nextReply(prompt);
        List<Flux<ChatResponse>> parts = new ArrayList<>();
        if (!reply.text().isEmpty()) {
            // Two chunks so a streaming consumer sees more than one token event.
            int mid = reply.text().length() / 2;
            parts.add(Flux.just(streamChunk(reply.text().substring(0, mid))));
            parts.add(Flux.just(streamChunk(reply.text().substring(mid))));
        }
        ChatResponse last = response(reply, "", true);
        if (eligibility.test(optionsOf(prompt), last)) {
            ChatResponse toolCallResponse = response(reply, reply.text(), true);
            // The tool-call chunk is not emitted downstream; tools run off the event loop.
            parts.add(
                    Flux.defer(
                                    () -> {
                                        ToolExecutionResult result =
                                                toolCallingManager.executeToolCalls(
                                                        prompt, toolCallResponse);
                                        if (result.returnDirect()) {
                                            return Flux.just(
                                                    ChatResponse.builder()
                                                            .from(last)
                                                            .generations(
                                                                    ToolExecutionResult
                                                                            .buildGenerations(
                                                                                    result))
                                                            .build());
                                        }
                                        return internalStream(
                                                new Prompt(
                                                        result.conversationHistory(),
                                                        optionsOf(prompt)));
                                    })
                            .subscribeOn(Schedulers.boundedElastic()));
        } else {
            parts.add(Flux.just(last));
        }
        return Flux.concat(parts);
    }

    // ------------------------------------------------------------------ helpers

    private ChatOptions optionsOf(Prompt prompt) {
        return prompt.getOptions() != null ? prompt.getOptions() : getDefaultOptions();
    }

    private Reply nextReply(Prompt prompt) {
        int number = modelCalls.incrementAndGet();
        List<String> offered = new ArrayList<>();
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            options.getToolCallbacks().forEach(t -> offered.add(t.getToolDefinition().name()));
        }
        Request request = new Request(number, prompt.getInstructions(), offered);
        requests.add(request);
        return responder.apply(request);
    }

    private static ChatResponse streamChunk(String text) {
        return new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content(text).build())),
                ChatResponseMetadata.builder()
                        .model("scripted-llm")
                        .usage(new EmptyUsage())
                        .build());
    }

    private static ChatResponse response(Reply reply, String text, boolean withToolCalls) {
        AssistantMessage.Builder message = AssistantMessage.builder().content(text);
        if (withToolCalls) {
            message.toolCalls(reply.toolCalls());
        }
        ChatGenerationMetadata generation =
                ChatGenerationMetadata.builder().finishReason(reply.finishReason()).build();
        return new ChatResponse(
                List.of(new Generation(message.build(), generation)),
                ChatResponseMetadata.builder()
                        .model("scripted-llm")
                        .usage(new EmptyUsage())
                        .build());
    }
}
