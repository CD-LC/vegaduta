package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import reactor.core.publisher.Flux;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end through the real {@link ResilientToolCallingManagerFactory} manager (argument
 * validation on), with a scripted provider model: the 2026-10-15 incident reply and the shapes
 * around it, for both the streaming and the blocking path.
 */
class TextToolCallRecoveringChatModelTest {

    private static final String INCIDENT_REPLY =
            "{\"name\": \"generate_document\", \"parameters\": {\"f\":"
                + " \"mandi_prices_weather_report\", \"path\": \"templates/report.md\", \"skill\":"
                + " \"mandi-prices-weather-report\"}}";
    private static final String VALID_REPLY =
            "{\"name\": \"generate_document\", \"parameters\": {\"type\": \"PDF\", \"filename\":"
                + " \"report.pdf\"}}";

    private final ObjectMapper objectMapper = com.agenticai.core.json.JsonMappers.plain();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /**
     * What ResilientToolCallingManagerFactory builds in production, with a registry to assert on.
     */
    private final ToolCallingManager manager =
            new ArgumentSanitizingToolCallingManager(
                            ToolCallingManager.builder().build(),
                            objectMapper,
                            ArgumentSanitizingToolCallingManager.DEFAULT_MAX_TOOL_ROUNDS,
                            ArgumentSanitizingToolCallingManager.DEFAULT_MAX_UNKNOWN_TOOL_CALLS,
                            true,
                            meters)
                    .withArgumentValidation(true);

    // --- the incident, streaming -----------------------------------------------

    @Test
    void theIncidentReplyIsRecoveredValidatedAndTheModelAnswersAgainInstead() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(chunks(INCIDENT_REPLY, 5), chunks("Here is your report.", 2)));
        ChatModel model = recovering(provider, 3);

        List<ChatResponse> out = model.stream(prompt(tool)).collectList().block();

        assertEquals("Here is your report.", text(out), "the raw JSON never reaches the client");
        assertEquals(
                0,
                tool.calls.get(),
                "arguments missing type/filename are rejected before the tool runs");
        assertEquals(2, provider.prompts.size());
        List<Message> second = provider.prompts.get(1).getInstructions();
        AssistantMessage recovered =
                assertInstanceOf(AssistantMessage.class, second.get(second.size() - 2));
        assertTrue(recovered.hasToolCalls());
        assertEquals("generate_document", recovered.getToolCalls().get(0).name());
        assertEquals(
                Boolean.TRUE,
                recovered.getMetadata().get(TextToolCallRecoveringChatModel.RECOVERED_PROPERTY));
        ToolResponseMessage feedback =
                assertInstanceOf(ToolResponseMessage.class, second.get(second.size() - 1));
        String data = feedback.getResponses().get(0).responseData();
        assertTrue(data.contains("invalid_arguments"), data);
        assertTrue(data.contains("missing required property 'type'"), data);
        assertTrue(data.contains("\"f\""), data);
        assertEquals(
                1.0,
                meters.counter(
                                TextToolCallRecoveringChatModel.RECOVERY_METRIC,
                                "outcome",
                                "recovered")
                        .count());
        assertEquals(
                1.0,
                meters.counter(
                                ArgumentSanitizingToolCallingManager.ARGUMENT_REJECTED_METRIC,
                                "tool",
                                "generate_document")
                        .count());
    }

    @Test
    void aValidTextCallRunsTheToolAndFeedsTheResultBack() throws Exception {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(
                                chunks("```json\n" + VALID_REPLY + "\n```", 3),
                                chunks("Done - report.pdf is ready.", 1)));

        List<ChatResponse> out = recovering(provider, 3).stream(prompt(tool)).collectList().block();

        assertEquals("Done - report.pdf is ready.", text(out));
        assertEquals(1, tool.calls.get());
        JsonNode args = objectMapper.readTree(tool.lastInput.get());
        assertEquals("PDF", args.get("type").asString());
        ToolResponseMessage result = lastToolResponse(provider.prompts.get(1));
        assertEquals("generated:report.pdf", result.getResponses().get(0).responseData());
    }

    // --- no-op guarantees ---------------------------------------------------------

    @Test
    void aProseReplyStreamsThroughChunkForChunkWithOneModelCall() {
        CountingTool tool = new CountingTool();
        List<ChatResponse> script =
                chunks("Hello! I can prepare the mandi price report for you.", 6);
        ScriptedModel provider = new ScriptedModel(List.of(script));

        List<ChatResponse> out = recovering(provider, 3).stream(prompt(tool)).collectList().block();

        assertEquals(script.size(), out.size());
        for (int i = 0; i < script.size(); i++) {
            assertSame(
                    script.get(i), out.get(i), "chunk " + i + " must be the provider's own object");
        }
        assertEquals(1, provider.prompts.size());
        assertEquals(0, tool.calls.get());
    }

    @Test
    void aProseReplyIsReleasedMidStreamOnceItCannotBeACall() {
        CountingTool tool = new CountingTool();
        // 4 chunks of ~16 chars: the first alone (16 chars) is still an ambiguous lead-in, the
        // second takes the text past the 24-char lead-in budget and must release both at once.
        List<ChatResponse> script =
                chunks("Sure thing, here is a long answer that keeps going on and on.", 4);
        ScriptedModel provider = new ScriptedModel(List.of(script));
        List<Integer> seenAfterEachUpstreamChunk = new ArrayList<>();
        AtomicInteger seen = new AtomicInteger();

        new TextToolCallRecoveringChatModel(
                        new ScriptedModel(List.of()) {
                            @Override
                            public Flux<ChatResponse> stream(Prompt p) {
                                return provider.stream(p)
                                        .doOnNext(c -> seenAfterEachUpstreamChunk.add(seen.get()));
                            }
                        },
                        manager,
                        objectMapper,
                        3,
                        TextToolCallRecoveringChatModel.DEFAULT_HOLD_BACK_CHARS,
                        meters)
                .stream(prompt(tool)).doOnNext(c -> seen.incrementAndGet()).blockLast();

        assertEquals(script.size(), seen.get());
        assertEquals(
                0,
                seenAfterEachUpstreamChunk.get(1),
                "nothing released while the first chunk is ambiguous");
        assertEquals(
                2,
                seenAfterEachUpstreamChunk.get(2),
                "both held chunks released as soon as prose is certain");
        assertEquals(
                3,
                seenAfterEachUpstreamChunk.get(3),
                "from then on every chunk passes straight through");
    }

    @Test
    void withoutOfferedToolsEvenAToolShapedReplyPassesThrough() {
        List<ChatResponse> script = chunks(INCIDENT_REPLY, 3);
        ScriptedModel provider = new ScriptedModel(List.of(script));
        Prompt noTools =
                new Prompt(
                        List.of(new UserMessage("yes generate")),
                        ToolCallingChatOptions.builder().build());

        List<ChatResponse> out = recovering(provider, 3).stream(noTools).collectList().block();

        assertEquals(INCIDENT_REPLY, text(out));
        assertEquals(1, provider.prompts.size());
    }

    @Test
    void aCallerThatRunsItsOwnToolLoopIsLeftAlone() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider = new ScriptedModel(List.of(chunks(VALID_REPLY, 2)));
        ToolCallingChatOptions options =
                ToolCallingChatOptions.builder()
                        .toolCallbacks(tool)
                        .toolContext(CallerRunsToolLoop.CONTEXT_KEY, true)
                        .build();

        List<ChatResponse> out =
                recovering(provider, 3).stream(new Prompt(List.of(new UserMessage("go")), options))
                        .collectList()
                        .block();

        assertEquals(VALID_REPLY, text(out));
        assertEquals(0, tool.calls.get());
    }

    @Test
    void aStructuredToolCallChunkIsPassedThroughUntouched() {
        CountingTool tool = new CountingTool();
        AssistantMessage structured =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "1", "function", "generate_document", "{}")))
                        .build();
        ChatResponse chunk = new ChatResponse(List.of(new Generation(structured)));
        ScriptedModel provider = new ScriptedModel(List.of(List.of(chunk)));

        List<ChatResponse> out = recovering(provider, 3).stream(prompt(tool)).collectList().block();

        assertEquals(1, out.size());
        assertSame(chunk, out.get(0));
        assertEquals(0, tool.calls.get(), "the provider model's own loop owns structured calls");
    }

    @Test
    void aHeldBackReplyThatTurnsOutNotToBeACallIsReleasedUnchanged() {
        CountingTool tool = new CountingTool();
        List<ChatResponse> script = chunks("{\"summary\": \"prices rose 4%\", \"rows\": []}", 4);
        ScriptedModel provider = new ScriptedModel(List.of(script));

        List<ChatResponse> out = recovering(provider, 3).stream(prompt(tool)).collectList().block();

        assertEquals(script.size(), out.size());
        assertEquals(1, provider.prompts.size());
        assertEquals(0, tool.calls.get());
    }

    @Test
    void aProviderErrorWhileHoldingReleasesThePartialTextBeforeTheError() {
        CountingTool tool = new CountingTool();
        List<ChatResponse> partial = chunks("{\"name\": \"generate_doc", 2);
        ChatModel failing =
                new ScriptedModel(List.of()) {
                    @Override
                    public Flux<ChatResponse> stream(Prompt p) {
                        return Flux.fromIterable(partial)
                                .concatWith(
                                        Flux.error(
                                                new IllegalStateException("provider went away")));
                    }
                };
        List<ChatResponse> seen = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        recovering(failing, 3).stream(prompt(tool))
                .doOnNext(seen::add)
                .onErrorResume(
                        e -> {
                            error.set(e);
                            return Flux.empty();
                        })
                .blockLast();

        assertEquals(
                partial.size(), seen.size(), "the held chunks reach the caller before the error");
        assertInstanceOf(
                IllegalStateException.class, error.get(), "and the error still propagates");
        assertEquals(0, tool.calls.get());
    }

    @Test
    void theHoldBackCapReleasesALongJsonLookingReply() {
        CountingTool tool = new CountingTool();
        String longJson = "{\"text\": \"" + "x".repeat(500) + "\"}";
        ScriptedModel provider = new ScriptedModel(List.of(chunks(longJson, 10)));
        ChatModel model =
                new TextToolCallRecoveringChatModel(
                        provider, manager, objectMapper, 3, 100, meters);

        List<ChatResponse> out = model.stream(prompt(tool)).collectList().block();

        assertEquals(longJson, text(out));
        assertEquals(1, provider.prompts.size());
    }

    // --- bounds ---------------------------------------------------------------------

    @Test
    void afterTheRecoveryLimitTheTextIsReleasedAsIs() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(
                                chunks(VALID_REPLY, 2),
                                chunks(VALID_REPLY, 2),
                                chunks(VALID_REPLY, 2),
                                chunks(VALID_REPLY, 2)));

        List<ChatResponse> out = recovering(provider, 2).stream(prompt(tool)).collectList().block();

        assertEquals(VALID_REPLY, text(out), "the third text call is released, not recovered");
        assertEquals(2, tool.calls.get());
        assertEquals(3, provider.prompts.size());
        assertEquals(
                1.0,
                meters.counter(TextToolCallRecoveringChatModel.RECOVERY_METRIC, "outcome", "limit")
                        .count());
    }

    @Test
    void aReturnDirectToolEndsTheLoopWithItsResult() {
        CountingTool tool = new CountingTool(true);
        ScriptedModel provider = new ScriptedModel(List.of(chunks(VALID_REPLY, 2)));

        List<ChatResponse> out = recovering(provider, 3).stream(prompt(tool)).collectList().block();

        assertEquals(1, provider.prompts.size(), "the model is not called again");
        assertEquals("generated:report.pdf", text(out));
    }

    // --- blocking path --------------------------------------------------------------

    @Test
    void theBlockingPathRecoversTheSameWay() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(List.of(chunks(VALID_REPLY, 1), chunks("Done.", 1)));

        ChatResponse out = recovering(provider, 3).call(prompt(tool));

        assertEquals("Done.", out.getResult().getOutput().getText());
        assertEquals(1, tool.calls.get());
        assertEquals(2, provider.prompts.size());
    }

    @Test
    void theBlockingPathLeavesAProseReplyUntouched() {
        CountingTool tool = new CountingTool();
        ChatResponse reply =
                new ChatResponse(List.of(new Generation(new AssistantMessage("Hello."))));
        ScriptedModel provider = new ScriptedModel(List.of(List.of(reply)));

        assertSame(reply, recovering(provider, 3).call(prompt(tool)));
    }

    // --- usage ----------------------------------------------------------------------

    @Test
    void usageOfTheFollowUpCallIsCumulativeWithTheRecoveredCall() {
        CountingTool tool = new CountingTool();
        ChatResponse first =
                withUsage(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage(VALID_REPLY)))),
                        100,
                        20);
        ChatResponse second =
                withUsage(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("Done.")))),
                        150,
                        10);
        ScriptedModel provider = new ScriptedModel(List.of(List.of(first), List.of(second)));

        List<ChatResponse> out = recovering(provider, 3).stream(prompt(tool)).collectList().block();

        assertEquals(1, out.size());
        assertEquals(250, out.get(0).getMetadata().getUsage().getPromptTokens());
        assertEquals(30, out.get(0).getMetadata().getUsage().getCompletionTokens());
        assertEquals("Done.", text(out));
    }

    @Test
    void wrappingIsIdempotentAndCanBeSwitchedOff() {
        ScriptedModel provider = new ScriptedModel(List.of());
        ToolCallRecovery on =
                new ToolCallRecovery(
                        new ResilientToolCallingManagerFactory(objectMapper),
                        objectMapper,
                        null,
                        true,
                        3,
                        100);
        ToolCallRecovery off =
                new ToolCallRecovery(
                        new ResilientToolCallingManagerFactory(objectMapper),
                        objectMapper,
                        null,
                        false,
                        3,
                        100);

        ChatModel wrapped = on.wrap(provider);
        assertInstanceOf(TextToolCallRecoveringChatModel.class, wrapped);
        assertSame(wrapped, on.wrap(wrapped));
        assertSame(provider, off.wrap(provider));
        assertSame(provider, ((TextToolCallRecoveringChatModel) wrapped).delegate());
    }

    // --- fixtures ---------------------------------------------------------------------

    private ChatModel recovering(ChatModel provider, int maxRecoveries) {
        return new TextToolCallRecoveringChatModel(
                provider,
                manager,
                objectMapper,
                maxRecoveries,
                TextToolCallRecoveringChatModel.DEFAULT_HOLD_BACK_CHARS,
                meters);
    }

    private static Prompt prompt(ToolCallback tool) {
        return new Prompt(
                List.of(new UserMessage("yes generate")),
                ToolCallingChatOptions.builder().toolCallbacks(tool).build());
    }

    private static List<ChatResponse> chunks(String text, int parts) {
        List<ChatResponse> out = new ArrayList<>();
        int size = Math.max(1, (int) Math.ceil(text.length() / (double) parts));
        for (int i = 0; i < text.length(); i += size) {
            String piece = text.substring(i, Math.min(text.length(), i + size));
            out.add(new ChatResponse(List.of(new Generation(new AssistantMessage(piece)))));
        }
        return out;
    }

    private static ChatResponse withUsage(ChatResponse response, int prompt, int completion) {
        return ChatResponse.builder()
                .from(response)
                .metadata(
                        ChatResponseMetadata.builder()
                                .usage(new DefaultUsage(prompt, completion))
                                .build())
                .build();
    }

    private static String text(List<ChatResponse> responses) {
        StringBuilder text = new StringBuilder();
        for (ChatResponse response : responses) {
            for (Generation generation : response.getResults()) {
                if (generation.getOutput().getText() != null) {
                    text.append(generation.getOutput().getText());
                }
            }
        }
        return text.toString();
    }

    private static ToolResponseMessage lastToolResponse(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        return assertInstanceOf(ToolResponseMessage.class, messages.get(messages.size() - 1));
    }

    /** A provider model that answers each call from a script and records what it was asked. */
    static class ScriptedModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        private final List<List<ChatResponse>> script;

        ScriptedModel(List<List<ChatResponse>> script) {
            this.script = script;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<ChatResponse> responses = next(prompt);
            assertEquals(1, responses.size(), "blocking scripts hold one response");
            return responses.get(0);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.fromIterable(next(prompt)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private List<ChatResponse> next(Prompt prompt) {
            prompts.add(prompt);
            assertTrue(prompts.size() <= script.size(), "model called more often than scripted");
            return script.get(prompts.size() - 1);
        }
    }

    /** generate_document look-alike: type and filename required, counts executions. */
    static final class CountingTool implements ToolCallback {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastInput = new AtomicReference<>();
        private final boolean returnDirect;

        CountingTool() {
            this(false);
        }

        CountingTool(boolean returnDirect) {
            this.returnDirect = returnDirect;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("generate_document")
                    .description("Generate a document")
                    .inputSchema(ToolArgumentValidatorTest.GENERATE_DOCUMENT_SCHEMA)
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(returnDirect).build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            lastInput.set(toolInput);
            try {
                JsonNode args = com.agenticai.core.json.JsonMappers.plain().readTree(toolInput);
                assertNotNull(
                        args.get("filename"),
                        "validation must have let only complete calls through");
                return "generated:" + args.get("filename").asString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
