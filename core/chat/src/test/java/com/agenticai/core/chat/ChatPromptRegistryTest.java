package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.providers.ToolRouterMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/**
 * The text the chat module writes for the model lives in the prompt registry (audit-B H21, H22, M18): the shipped
 * text of every key equals the literal that used to be in the code, and a tenant or agent override reaches the real
 * paths: the tool loop's stops, the answer to a tool name that was never offered, the tool-router shim and the
 * structured-output shim.
 */
class ChatPromptRegistryTest {

    private static final String TOOL = "search_docs";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final TenantContext TENANT = TenantContext.of("t1", "owner", Set.of());

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, String key, String text) {
        TenantContext.set(TENANT);
        store.put(new PromptOverride(
                scope, scope == PromptScope.AGENT ? AGENT.toString() : "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
        TenantContext.clear();
    }

    private static String shipped(String key, String... pairs) {
        return ChatPrompts.text(PromptResolver.defaultsOnly(), key, ChatPrompts.Run.NONE, pairs);
    }

    // ------------------------------------------------------------------ the catalog

    @Test
    void everyChatKeyIsRegisteredAndTheCatalogHasNoProblems() {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();

        assertThat(catalog.problems()).isEmpty();
        for (String key : List.of(
                "toolresult.unavailable.generate_image", "toolresult.unavailable.speak",
                "toolresult.unavailable.renamed_speak", "toolresult.unavailable.run_code",
                "toolresult.unavailable.unknown_tool", "toolresult.tool_budget.rounds", "message.tool_budget.hard_stop",
                "shim.router.format", "shim.router.required", "shim.structured.schema_instruction")) {
            assertThat(catalog.find(key)).as(key).isPresent();
        }
        assertThat(catalog.find("message.tool_budget.hard_stop").orElseThrow().securityCritical()).isFalse();
        assertThat(catalog.find("toolresult.tool_budget.rounds").orElseThrow().securityCritical()).isTrue();
    }

    // ------------------------------------------------------------------ golden: shipped text == previous literal

    @Test
    void theUnavailableToolTextsAreByteIdenticalToTheLiteralsTheyReplaced() {
        String imageGeneration = "Image generation is not switched on for this workspace, so this tool cannot run. It needs an "
                + "OpenAI API key to be added and activated on the Providers page of the console "
                + "(image generation reuses the same OpenAI key as chat). Tell the user exactly that, in "
                + "their own language, and answer the rest of their message normally. Do not call this tool "
                + "again in this conversation.";
        String speak = "Voice/text-to-speech is not switched on for this workspace, so this tool cannot run. It needs "
                + "either an ElevenLabs API key on the Providers page plus at least one cloned voice, or a "
                + "Sarvam credential attached to this agent. Tell the user that and do not call this tool "
                + "again in this conversation.";
        String renamed = "This tool was renamed to \"speak\". Call \"speak\" instead - if it is also not offered, "
                + "voice/text-to-speech is not switched on for this workspace. Do not call this name again.";
        String runCode = "Code execution is not switched on for this workspace, so this tool cannot run. It needs an "
                + "E2B API key on the Providers page. Tell the user that and do not call this tool again "
                + "in this conversation.";
        String unknownFormat = "There is no tool called \"%s\". It was not among the tools provided with this request, so it "
                + "cannot be called - you invented the name. Do not call it again. Answer the user directly "
                + "using only the tools you were actually given, and if what they asked for genuinely needs "
                + "a capability you do not have, say so plainly and briefly.";

        assertThat(UnavailableToolCallback.forToolName("generate_image").message()).isEqualTo(imageGeneration);
        assertThat(UnavailableToolCallback.forToolName(" Generate_Image ").message()).isEqualTo(imageGeneration);
        assertThat(UnavailableToolCallback.forToolName("speak").message()).isEqualTo(speak);
        assertThat(UnavailableToolCallback.forToolName("speak_with_cloned_voice").message()).isEqualTo(renamed);
        assertThat(UnavailableToolCallback.forToolName("sarvam_speak").message()).isEqualTo(renamed);
        assertThat(UnavailableToolCallback.forToolName("run_code").message()).isEqualTo(runCode);
        assertThat(UnavailableToolCallback.forToolName("search_web_exa").message())
                .isEqualTo(String.format(unknownFormat, "search_web_exa"));
        assertThat(UnavailableToolCallback.forToolName(null).message()).isEqualTo(String.format(unknownFormat, (Object) null));
    }

    @Test
    void theToolLoopStopTextsEqualTheShippedPropertyDefaults() {
        ToolLoopProperties defaults = new ToolLoopProperties();
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();

        assertThat(catalog.defaultText("toolresult.tool_budget.rounds")).isEqualTo(defaults.getBudgetExhaustedMessage());
        assertThat(catalog.defaultText("message.tool_budget.hard_stop")).isEqualTo(defaults.getHardStopReply());
        assertThat(shipped(ChatPrompts.TOOL_BUDGET_ROUNDS, "rounds", "7"))
                .isEqualTo(defaults.getBudgetExhaustedMessage().replace("{rounds}", "7"));
    }

    @Test
    void theRouterAndStructuredShimTextsAreByteIdenticalToTheLiteralsTheyReplaced() {
        assertThat(shipped(ChatPrompts.ROUTER_FORMAT)).isEqualTo(
                "You can use the tools below. Reply with exactly one JSON object and nothing else: "
                        + "{\"action\":\"call\",\"tool\":\"<name>\",\"arguments\":{...}} to run a tool "
                        + "(arguments must follow that tool's parameters), or "
                        + "{\"action\":\"answer\",\"answer\":\"<your reply to the user>\"} when no tool is needed "
                        + "or you already have the results you need. Call one tool at a time; the result comes back "
                        + "as a tool message.");
        assertThat(shipped(ChatPrompts.ROUTER_REQUIRED, "tool", "final_answer")).isEqualTo(
                "Every reply must be a tool call. Call " + "final_answer"
                        + " with your reply to the user when no other tool is needed or you have the results you need.");
        assertThat(shipped(ChatPrompts.STRUCTURED_SCHEMA_INSTRUCTION, "schema", "{\"type\":\"object\"}")).isEqualTo(
                "Reply with ONLY one JSON value that is valid for the JSON Schema below. No markdown code fences,"
                        + " no commentary before or after.\nJSON Schema: " + "{\"type\":\"object\"}");
    }

    // ------------------------------------------------------------------ the tool loop

    private ArgumentSanitizingToolCallingManager manager() {
        return new ArgumentSanitizingToolCallingManager(
                ToolCallingManager.builder().build(), objectMapper, 3, 8, true, null)
                .withPrompts(resolver);
    }

    private static ToolCallback tool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(TOOL)
                        .description("stub")
                        .inputSchema("{\"type\": \"object\", \"properties\": {}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    /** A prompt three tool rounds into the invocation, so the next round is past a cap of 3. */
    private static Prompt pastTheCap(ToolLoopTranscript transcript) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("do the thing"));
        for (int i = 0; i < 3; i++) {
            String id = "prior-" + i;
            messages.add(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", TOOL, "{}"))).build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(id, TOOL, "ok"))).build());
        }
        ToolCallingChatOptions.Builder options = ToolCallingChatOptions.builder().toolCallbacks(List.of(tool()));
        if (transcript != null) {
            options.toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));
        }
        return new Prompt(messages, options.build());
    }

    private static ChatResponse responseCalling(String... names) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            calls.add(new AssistantMessage.ToolCall("call-" + i, "function", names[i], "{}"));
        }
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }

    private static String lastResponse(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        return ((ToolResponseMessage) history.get(history.size() - 1)).getResponses().get(0).responseData();
    }

    private String oldSoftStop(String message, int rounds) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "tool_budget_exhausted");
        body.put("result", message.replace("{rounds}", String.valueOf(rounds)));
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void theSoftStopIsByteIdenticalToTheOldOneByDefault() throws Exception {
        ToolExecutionResult result = manager().executeToolCalls(pastTheCap(null), responseCalling(TOOL));

        assertThat(lastResponse(result)).isEqualTo(oldSoftStop(new ToolLoopProperties().getBudgetExhaustedMessage(), 3));
    }

    @Test
    void aTenantOverrideOfTheSoftStopReachesTheModelEvenWhenTheLoopThreadCarriesNoTenant() throws Exception {
        override(PromptScope.TENANT, ChatPrompts.TOOL_BUDGET_ROUNDS, "Out of rounds after {rounds}. Answer now.");
        ToolLoopTranscript transcript = new ToolLoopTranscript().forPrompts(AGENT, TenantScope.of(TENANT));
        TenantContext.clear(); // the loop runs on a thread that knows nothing about the turn

        ToolExecutionResult result = manager().executeToolCalls(pastTheCap(transcript), responseCalling(TOOL));

        JsonNode body = objectMapper.readTree(lastResponse(result));
        assertThat(body.get("error").asText()).isEqualTo("tool_budget_exhausted");
        assertThat(body.get("result").asText()).isEqualTo("Out of rounds after 3. Answer now.");
    }

    @Test
    void anAgentOverrideBeatsTheTenantsForThatAgentOnly() throws Exception {
        override(PromptScope.TENANT, ChatPrompts.TOOL_BUDGET_ROUNDS, "Tenant says stop at {rounds}.");
        override(PromptScope.AGENT, ChatPrompts.TOOL_BUDGET_ROUNDS, "Agent says stop at {rounds}.");

        ToolLoopTranscript forAgent = new ToolLoopTranscript().forPrompts(AGENT, TenantScope.of(TENANT));
        ToolLoopTranscript forOther = new ToolLoopTranscript().forPrompts(OTHER_AGENT, TenantScope.of(TENANT));

        assertThat(objectMapper.readTree(lastResponse(manager().executeToolCalls(pastTheCap(forAgent), responseCalling(TOOL))))
                .get("result").asText()).isEqualTo("Agent says stop at 3.");
        assertThat(objectMapper.readTree(lastResponse(manager().executeToolCalls(pastTheCap(forOther), responseCalling(TOOL))))
                .get("result").asText()).isEqualTo("Tenant says stop at 3.");
    }

    @Test
    void theOldPropertiesStillWorkAsAnAliasAndTheRegistryWinsOverThem() throws Exception {
        ArgumentSanitizingToolCallingManager aliased = manager().withStopTexts("Property soft stop after {rounds}.", "Property hard stop.");

        assertThat(objectMapper.readTree(lastResponse(aliased.executeToolCalls(pastTheCap(null), responseCalling(TOOL))))
                .get("result").asText()).isEqualTo("Property soft stop after 3.");
        ToolExecutionResult soft = aliased.executeToolCalls(pastTheCap(null), responseCalling(TOOL));
        Prompt next = new Prompt(new ArrayList<>(soft.conversationHistory()), ToolCallingChatOptions.builder().toolCallbacks(List.of(tool())).build());
        assertThat(lastResponse(aliased.executeToolCalls(next, responseCalling(TOOL)))).isEqualTo("Property hard stop.");

        override(PromptScope.TENANT, ChatPrompts.TOOL_BUDGET_ROUNDS, "Registry soft stop after {rounds}.");
        override(PromptScope.TENANT, ChatPrompts.HARD_STOP, "Registry hard stop.");
        ToolLoopTranscript transcript = new ToolLoopTranscript().forPrompts(AGENT, TenantScope.of(TENANT));
        ToolExecutionResult softAgain = aliased.executeToolCalls(pastTheCap(transcript), responseCalling(TOOL));
        assertThat(objectMapper.readTree(lastResponse(softAgain)).get("result").asText()).isEqualTo("Registry soft stop after 3.");
        List<Message> continued = new ArrayList<>(softAgain.conversationHistory());
        Prompt hardPrompt = new Prompt(continued, ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(tool()))
                .toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript)).build());
        ToolExecutionResult hard = aliased.executeToolCalls(hardPrompt, responseCalling(TOOL));
        assertThat(hard.returnDirect()).isTrue();
        assertThat(lastResponse(hard)).isEqualTo("Registry hard stop.");
    }

    @Test
    void theAnswerToAToolNameThatWasNeverOfferedFollowsTheRegistry() throws Exception {
        ToolLoopTranscript transcript = new ToolLoopTranscript().forPrompts(AGENT, TenantScope.of(TENANT));
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")), ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(tool()))
                .toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript)).build());

        String byDefault = lastResponse(manager().executeToolCalls(prompt, responseCalling("made_up_tool")));
        assertThat(objectMapper.readTree(byDefault).get("result").asText())
                .isEqualTo(UnavailableToolCallback.forToolName("made_up_tool").message());

        override(PromptScope.AGENT, "toolresult.unavailable.unknown_tool", "No such tool: {tool}. Use only what you were given.");
        String overridden = lastResponse(manager().executeToolCalls(prompt, responseCalling("made_up_tool")));
        assertThat(objectMapper.readTree(overridden).get("result").asText())
                .isEqualTo("No such tool: made_up_tool. Use only what you were given.");
    }

    @Test
    void aGatedToolExplanationFollowsATenantOverride() {
        override(PromptScope.TENANT, "toolresult.unavailable.generate_image", "Ask your admin to enable images.");
        TenantContext.set(TENANT);

        assertThat(UnavailableToolCallback.forToolName("generate_image", resolver, ChatPrompts.Run.NONE).message())
                .isEqualTo("Ask your admin to enable images.");
        assertThat(UnavailableToolCallback.forToolName("speak", resolver, ChatPrompts.Run.NONE).message())
                .isEqualTo(shipped("toolresult.unavailable.speak"));
    }

    // ------------------------------------------------------------------ the tool-router shim

    private ConstrainedToolRouterChatModel router(ToolRouterMode mode) {
        ChatModel unused = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };
        return new ConstrainedToolRouterChatModel(unused, mode, ToolCallingManager.builder().build(), objectMapper, 6, null)
                .withPrompts(resolver);
    }

    private static Prompt routedPrompt(ToolLoopTranscript transcript) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model("llama3.2:3b").toolCallbacks(tool());
        if (transcript != null) {
            options.toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));
        }
        return new Prompt(List.of(new SystemMessage("You are the documents agent."), new UserMessage("make a pdf")), options.build());
    }

    @Test
    void theFormatRouterInstructionIsTheOldOneByDefaultAndFollowsAnAgentOverride() {
        ConstrainedToolRouterChatModel router = router(ToolRouterMode.FORMAT);
        String oldHead = "You can use the tools below. Reply with exactly one JSON object and nothing else: "
                + "{\"action\":\"call\",\"tool\":\"<name>\",\"arguments\":{...}} to run a tool "
                + "(arguments must follow that tool's parameters), or "
                + "{\"action\":\"answer\",\"answer\":\"<your reply to the user>\"} when no tool is needed "
                + "or you already have the results you need. Call one tool at a time; the result comes back "
                + "as a tool message.\nTools:\n";

        String text = router.formatPrompt(routedPrompt(null), List.of(tool())).getInstructions().get(1).getText();
        assertThat(text).isEqualTo(oldHead + "- search_docs: stub Parameters: {\"type\": \"object\", \"properties\": {}}\n");

        override(PromptScope.AGENT, ChatPrompts.ROUTER_FORMAT, "Answer in JSON only.");
        ToolLoopTranscript transcript = new ToolLoopTranscript().forPrompts(AGENT, TenantScope.of(TENANT));
        String overridden = router.formatPrompt(routedPrompt(transcript), List.of(tool())).getInstructions().get(1).getText();
        assertThat(overridden).isEqualTo("Answer in JSON only.\nTools:\n- search_docs: stub Parameters: {\"type\": \"object\", \"properties\": {}}\n");
        String other = router.formatPrompt(
                routedPrompt(new ToolLoopTranscript().forPrompts(OTHER_AGENT, TenantScope.of(TENANT))), List.of(tool()))
                .getInstructions().get(1).getText();
        assertThat(other).startsWith("You can use the tools below.");
    }

    @Test
    void theRequiredRouterInstructionIsTheOldOneByDefaultAndFollowsATenantOverride() {
        ConstrainedToolRouterChatModel router = router(ToolRouterMode.REQUIRED);

        String text = router.requiredPrompt(routedPrompt(null), List.of(tool())).getInstructions().get(1).getText();
        assertThat(text).isEqualTo("Every reply must be a tool call. Call final_answer with your reply to the user when no "
                + "other tool is needed or you have the results you need.");

        override(PromptScope.TENANT, ChatPrompts.ROUTER_REQUIRED, "Use {tool} to reply.");
        ToolLoopTranscript transcript = new ToolLoopTranscript().forPrompts(null, TenantScope.of(TENANT));
        assertThat(router.requiredPrompt(routedPrompt(transcript), List.of(tool())).getInstructions().get(1).getText())
                .isEqualTo("Use final_answer to reply.");
    }

    // ------------------------------------------------------------------ the structured-output shim

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}},\"required\":[\"title\"]}";

    /** Answers with valid JSON and records the prompt it was sent. */
    private static final class Recording implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"title\":\"x\"}"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    private static Prompt schemaPrompt(String model) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(ResponseFormat.JsonSchema.builder().name("answer").schema(SCHEMA).strict(false).build())
                        .build())
                .build();
        return new Prompt(List.of(new SystemMessage("You are terse."), new UserMessage("Rate it.")), options);
    }

    @Test
    void thePromptBasedSchemaInstructionIsTheOldOneByDefaultAndFollowsATenantOverride() {
        String model = "schema-shim-model";
        OllamaStructuredFallbackChatModel.FORMAT_REFUSED.add(model);
        try {
            Recording provider = new Recording();
            OllamaStructuredFallbackChatModel shim = new OllamaStructuredFallbackChatModel(provider, SCHEMA).withPrompts(resolver);

            shim.call(schemaPrompt(model));
            String system = provider.prompts.get(0).getInstructions().get(0).getText();
            assertThat(system).isEqualTo("You are terse.\n\n"
                    + "Reply with ONLY one JSON value that is valid for the JSON Schema below. No markdown code fences,"
                    + " no commentary before or after.\nJSON Schema: " + objectMapper.readTree(SCHEMA));

            override(PromptScope.TENANT, ChatPrompts.STRUCTURED_SCHEMA_INSTRUCTION, "JSON only, matching {schema}");
            TenantContext.set(TENANT);
            shim.call(schemaPrompt(model));
            assertThat(provider.prompts.get(1).getInstructions().get(0).getText())
                    .isEqualTo("You are terse.\n\nJSON only, matching " + objectMapper.readTree(SCHEMA));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            OllamaStructuredFallbackChatModel.FORMAT_REFUSED.remove(model);
        }
    }

    // ------------------------------------------------------------------ fail-safe

    @Test
    void aBrokenRegistryFallsBackToTheShippedTextInsteadOfBreakingTheLoop() {
        store.failWith = new IllegalStateException("db down");
        TenantContext.set(TENANT);

        assertThat(ChatPrompts.text(resolver, ChatPrompts.TOOL_BUDGET_ROUNDS, new ChatPrompts.Run(AGENT, null), "rounds", "2"))
                .isEqualTo(new ToolLoopProperties().getBudgetExhaustedMessage().replace("{rounds}", "2"));
        assertThat(ChatPrompts.text(null, "no.such_key.here", ChatPrompts.Run.NONE)).isEqualTo("[no.such_key.here]");
        assertThat(resolver.resolve(ChatPrompts.HARD_STOP, PromptContext.forAgent(AGENT)).textOrNull())
                .isEqualTo(new ToolLoopProperties().getHardStopReply());
    }
}
