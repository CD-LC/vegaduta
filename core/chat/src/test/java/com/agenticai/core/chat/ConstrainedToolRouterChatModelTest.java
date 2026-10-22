package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.ToolRouterMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * Through the real {@link ArgumentSanitizingToolCallingManager}, against a scripted provider model,
 * so the request shaping (tools stripped, schema set, catalog message inserted) and the loop (tool
 * executed, result fed back, answer released) are both checked.
 */
class ConstrainedToolRouterChatModelTest {

    private final ObjectMapper objectMapper = com.agenticai.core.json.JsonMappers.plain();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ToolCallingManager manager =
            new ArgumentSanitizingToolCallingManager(
                            ToolCallingManager.builder().build(),
                            objectMapper,
                            ArgumentSanitizingToolCallingManager.DEFAULT_MAX_TOOL_ROUNDS,
                            ArgumentSanitizingToolCallingManager.DEFAULT_MAX_UNKNOWN_TOOL_CALLS,
                            true,
                            meters)
                    .withArgumentValidation(true);

    // --- FORMAT mode -----------------------------------------------------------------

    @Test
    void formatModeStripsToolsSetsTheSchemaAndListsToolsInASystemMessage() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(List.of(reply("{\"action\":\"answer\",\"answer\":\"Hi.\"}")));

        ChatResponse out = router(provider, ToolRouterMode.FORMAT, 6).call(prompt(tool));

        assertEquals("Hi.", out.getResult().getOutput().getText());
        Prompt sent = provider.prompts.get(0);
        OpenAiChatOptions options = (OpenAiChatOptions) sent.getOptions();
        assertTrue(options.getToolCallbacks().isEmpty(), "tools are not sent in FORMAT mode");
        assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
        assertEquals(ResponseFormat.Type.JSON_SCHEMA, options.getResponseFormat().getType());
        assertTrue(
                String.valueOf(options.getResponseFormat().getJsonSchema().getSchema())
                        .contains("generate_document"),
                "the tool enum names the offered tool");
        assertEquals(
                MessageType.SYSTEM,
                sent.getInstructions().get(0).getMessageType(),
                "agent system prompt first");
        assertEquals(
                MessageType.SYSTEM,
                sent.getInstructions().get(1).getMessageType(),
                "router catalog right after");
        assertTrue(sent.getInstructions().get(1).getText().contains("generate_document"));
        assertEquals(MessageType.USER, sent.getInstructions().get(2).getMessageType());
        assertEquals(0, tool.calls.get());
        assertEquals(
                1.0,
                meters.counter(ConstrainedToolRouterChatModel.ROUTER_METRIC, "outcome", "answer")
                        .count());
    }

    @Test
    void formatModeRunsTheChosenToolAndCallsAgainWithTheResult() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(
                                reply(
                                        "{\"action\":\"call\",\"tool\":\"generate_document\",\"arguments\":{\"type\":\"PDF\",\"filename\":\"r.pdf\"}}"),
                                reply("{\"action\":\"answer\",\"answer\":\"Done: r.pdf\"}")));

        ChatResponse out = router(provider, ToolRouterMode.FORMAT, 6).call(prompt(tool));

        assertEquals("Done: r.pdf", out.getResult().getOutput().getText());
        assertEquals(1, tool.calls.get());
        assertEquals(2, provider.prompts.size());
        List<Message> second = provider.prompts.get(1).getInstructions();
        AssistantMessage routed =
                assertInstanceOf(AssistantMessage.class, second.get(second.size() - 2));
        assertEquals(
                Boolean.TRUE,
                routed.getMetadata().get(ConstrainedToolRouterChatModel.ROUTED_PROPERTY));
        assertEquals("generate_document", routed.getToolCalls().get(0).name());
        assertInstanceOf(ToolResponseMessage.class, second.get(second.size() - 1));
        // The catalog message is inserted once per round, never accumulated in the history.
        assertEquals(
                1,
                second.stream()
                        .filter(
                                m ->
                                        m.getMessageType() == MessageType.SYSTEM
                                                && m.getText()
                                                        .startsWith("You can use the tools below"))
                        .count());
        assertEquals(
                1.0,
                meters.counter(ConstrainedToolRouterChatModel.ROUTER_METRIC, "outcome", "call")
                        .count());
    }

    @Test
    void formatModeSendsSchemaProblemsBackThroughTheSharedManager() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(
                                reply(
                                        "{\"action\":\"call\",\"tool\":\"generate_document\",\"arguments\":{\"prompt\":\"x\"}}"),
                                reply(
                                        "{\"action\":\"call\",\"tool\":\"generate_document\",\"arguments\":{\"type\":\"PDF\",\"filename\":\"r.pdf\"}}"),
                                reply("{\"action\":\"answer\",\"answer\":\"Done.\"}")));

        ChatResponse out = router(provider, ToolRouterMode.FORMAT, 6).call(prompt(tool));

        assertEquals("Done.", out.getResult().getOutput().getText());
        assertEquals(1, tool.calls.get(), "the invalid call never ran");
        ToolResponseMessage feedback =
                assertInstanceOf(
                        ToolResponseMessage.class,
                        provider.prompts
                                .get(1)
                                .getInstructions()
                                .get(provider.prompts.get(1).getInstructions().size() - 1));
        assertTrue(feedback.getResponses().get(0).responseData().contains("filename"));
    }

    @Test
    void formatModeAcceptsABareToolCallShapeAndACodeFence() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(
                                reply(
                                        "```json\n{\"name\":\"generate_document\",\"arguments\":{\"type\":\"PDF\",\"filename\":\"r.pdf\"}}\n```"),
                                reply("Plain prose, no JSON.")));

        ChatResponse out = router(provider, ToolRouterMode.FORMAT, 6).call(prompt(tool));

        assertEquals("Plain prose, no JSON.", out.getResult().getOutput().getText());
        assertEquals(1, tool.calls.get());
    }

    @Test
    void theRoundLimitEndsTheLoopWithAnExplanation() {
        CountingTool tool = new CountingTool();
        List<List<ChatResponse>> script = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            script.add(
                    reply(
                            "{\"action\":\"call\",\"tool\":\"generate_document\",\"arguments\":{\"type\":\"PDF\",\"filename\":\"r.pdf\"}}"));
        }
        ScriptedModel provider = new ScriptedModel(script);

        ChatResponse out = router(provider, ToolRouterMode.FORMAT, 2).call(prompt(tool));

        assertEquals(2, tool.calls.get());
        assertTrue(out.getResult().getOutput().getText().contains("could not finish"));
        assertEquals(
                1.0,
                meters.counter(ConstrainedToolRouterChatModel.ROUTER_METRIC, "outcome", "limit")
                        .count());
    }

    @Test
    void streamingAnswersAsOneChunkAfterTheLoop() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(
                                reply(
                                        "{\"action\":\"call\",\"tool\":\"generate_document\",\"arguments\":{\"type\":\"PDF\",\"filename\":\"r.pdf\"}}"),
                                reply("{\"action\":\"answer\",\"answer\":\"Done.\"}")));

        List<ChatResponse> out =
                router(provider, ToolRouterMode.FORMAT, 6).stream(prompt(tool))
                        .collectList()
                        .block();

        assertEquals(1, out.size());
        assertEquals("Done.", out.get(0).getResult().getOutput().getText());
        assertEquals(1, tool.calls.get());
    }

    @Test
    void aServerThatRefusesTheSchemaGetsThePlainRequestAndIsNotAskedAgain() {
        CountingTool tool = new CountingTool();
        ConstrainedToolRouterChatModel.FORMAT_UNAVAILABLE.remove("refuses-format");
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(reply("unused"), reply("Plain answer."), reply("Second turn.")));
        provider.failFirstCallWith =
                new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        "{\"error\":{\"message\":\"structured output is unavailable\"}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        null);
        Prompt prompt =
                new Prompt(
                        List.of(new UserMessage("make a pdf")),
                        OpenAiChatOptions.builder()
                                .model("refuses-format")
                                .toolCallbacks(tool)
                                .build());

        ChatResponse first = router(provider, ToolRouterMode.FORMAT, 6).call(prompt);
        ChatResponse second = router(provider, ToolRouterMode.FORMAT, 6).call(prompt);

        assertEquals("Plain answer.", first.getResult().getOutput().getText());
        assertEquals("Second turn.", second.getResult().getOutput().getText());
        // Call 1 was routed and refused, call 2 plain, call 3 plain straight away (memoised).
        assertEquals(3, provider.prompts.size());
        assertEquals(
                1,
                ((OpenAiChatOptions) provider.prompts.get(1).getOptions())
                        .getToolCallbacks()
                        .size(),
                "tools back on the plain request");
        assertNull(((OpenAiChatOptions) provider.prompts.get(2).getOptions()).getResponseFormat());
        assertEquals(
                1.0,
                meters.counter(
                                ConstrainedToolRouterChatModel.ROUTER_METRIC,
                                "outcome",
                                "format_unavailable")
                        .count());
        ConstrainedToolRouterChatModel.FORMAT_UNAVAILABLE.remove("refuses-format");
    }

    // --- REQUIRED mode ------------------------------------------------------------------

    @Test
    void requiredModeKeepsToolsAddsFinalAnswerAndRequiresAChoice() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider =
                new ScriptedModel(
                        List.of(
                                toolCallReply(
                                        "generate_document",
                                        "{\"type\":\"PDF\",\"filename\":\"r.pdf\"}"),
                                toolCallReply(
                                        ConstrainedToolRouterChatModel.FINAL_ANSWER_TOOL,
                                        "{\"answer\":\"All done.\"}")));

        ChatResponse out = router(provider, ToolRouterMode.REQUIRED, 6).call(prompt(tool));

        assertEquals("All done.", out.getResult().getOutput().getText());
        assertEquals(1, tool.calls.get());
        OpenAiChatOptions options = (OpenAiChatOptions) provider.prompts.get(0).getOptions();
        assertEquals("required", options.getToolChoice());
        assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
        assertEquals(
                List.of("generate_document", ConstrainedToolRouterChatModel.FINAL_ANSWER_TOOL),
                options.getToolCallbacks().stream()
                        .map(c -> c.getToolDefinition().name())
                        .toList());
        assertNull(options.getResponseFormat(), "no schema in REQUIRED mode");
    }

    @Test
    void requiredModeReleasesAProseReplyWhenTheServerIgnoredTheConstraint() {
        CountingTool tool = new CountingTool();
        ScriptedModel provider = new ScriptedModel(List.of(reply("Just text.")));
        ChatResponse out = router(provider, ToolRouterMode.REQUIRED, 6).call(prompt(tool));
        assertEquals("Just text.", out.getResult().getOutput().getText());
    }

    // --- pass-through ---------------------------------------------------------------------

    @Test
    void withoutToolsOrWithACallerOwnedLoopTheRequestIsUntouched() {
        ScriptedModel provider = new ScriptedModel(List.of(reply("Hello."), reply("Hello.")));
        ChatModel routed = router(provider, ToolRouterMode.FORMAT, 6);

        Prompt noTools =
                new Prompt(
                        List.of(new UserMessage("hi")),
                        OpenAiChatOptions.builder().model("m").build());
        assertSame(noTools, routed.call(noTools) == null ? null : provider.prompts.get(0));

        Prompt ownLoop =
                new Prompt(
                        List.of(new UserMessage("hi")),
                        OpenAiChatOptions.builder()
                                .model("m")
                                .toolCallbacks(new CountingTool())
                                .internalToolExecutionEnabled(false)
                                .build());
        routed.call(ownLoop);
        assertSame(ownLoop, provider.prompts.get(1));
    }

    @Test
    void nonOpenAiOptionsArePassedThrough() {
        ScriptedModel provider = new ScriptedModel(List.of(reply("Hello.")));
        Prompt generic =
                new Prompt(
                        List.of(new UserMessage("hi")),
                        ToolCallingChatOptions.builder().toolCallbacks(new CountingTool()).build());
        router(provider, ToolRouterMode.FORMAT, 6).call(generic);
        assertSame(generic, provider.prompts.get(0));
    }

    @Test
    void wiringIsPerModeIdempotentAndSwitchable() {
        ScriptedModel provider = new ScriptedModel(List.of());
        ConstrainedToolRouting on =
                new ConstrainedToolRouting(
                        new ResilientToolCallingManagerFactory(objectMapper),
                        objectMapper,
                        null,
                        true,
                        6);
        ConstrainedToolRouting off =
                new ConstrainedToolRouting(
                        new ResilientToolCallingManagerFactory(objectMapper),
                        objectMapper,
                        null,
                        false,
                        6);

        assertSame(provider, on.wrap(provider, null), "no mode = no router");
        ChatModel wrapped = on.wrap(provider, ToolRouterMode.REQUIRED);
        assertInstanceOf(ConstrainedToolRouterChatModel.class, wrapped);
        assertEquals(ToolRouterMode.REQUIRED, ((ConstrainedToolRouterChatModel) wrapped).mode());
        assertSame(wrapped, on.wrap(wrapped, ToolRouterMode.FORMAT));
        assertSame(provider, off.wrap(provider, ToolRouterMode.FORMAT));
    }

    // --- fixtures ---------------------------------------------------------------------------

    private ChatModel router(ChatModel provider, ToolRouterMode mode, int maxRounds) {
        return new ConstrainedToolRouterChatModel(
                provider, mode, manager, objectMapper, maxRounds, meters);
    }

    private static Prompt prompt(ToolCallback tool) {
        return new Prompt(
                List.of(
                        new SystemMessage("You are the documents agent."),
                        new UserMessage("make a pdf")),
                OpenAiChatOptions.builder().model("llama3.2:3b").toolCallbacks(tool).build());
    }

    private static List<ChatResponse> reply(String text) {
        return List.of(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private static List<ChatResponse> toolCallReply(String name, String arguments) {
        AssistantMessage message =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "c1", "function", name, arguments)))
                        .build();
        return List.of(new ChatResponse(List.of(new Generation(message))));
    }

    /** Answers each call with the next scripted reply and records every prompt it received. */
    static final class ScriptedModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        RuntimeException failFirstCallWith;
        private final List<List<ChatResponse>> script;

        ScriptedModel(List<List<ChatResponse>> script) {
            this.script = script;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (prompts.size() == 1 && failFirstCallWith != null) {
                throw failFirstCallWith;
            }
            assertTrue(prompts.size() <= script.size(), "more model calls than scripted replies");
            return script.get(prompts.size() - 1).get(0);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.fromIterable(List.of(call(prompt)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }
    }

    /** A real tool with a required property, so the manager's argument validation is exercised. */
    static final class CountingTool implements ToolCallback {
        final AtomicInteger calls = new AtomicInteger();
        private final ToolDefinition definition =
                ToolDefinition.builder()
                        .name("generate_document")
                        .description("Generate a document.")
                        .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"},"
                                        + "\"filename\":{\"type\":\"string\"}},\"required\":[\"type\",\"filename\"]}")
                        .build();

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            assertNotNull(toolInput);
            return "{\"url\":\"/files/r.pdf\"}";
        }
    }
}
