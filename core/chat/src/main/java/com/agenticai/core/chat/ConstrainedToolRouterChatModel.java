package com.agenticai.core.chat;

import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.providers.ToolRouterMode;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Makes a small model choose between "call this tool with these arguments" and "answer" through a
 * constraint the server enforces, instead of hoping it emits a structured tool call on its own.
 * Wrapped around a row's {@link ChatModel} by {@link LlmModelSupport#chatClientBuilder(ChatModel,
 * ToolRouterMode)} when the row's measured grade asks for it (TEXT or NONE, see {@code
 * PlatformHostedLlmProvider#effectiveToolRouterMode}); a row graded STRUCTURED never sees it, and a
 * request that offers no tools, or runs its own tool loop, is passed straight through.
 *
 * <p>{@link ToolRouterMode#FORMAT} (Ollama, llama.cpp): the request is sent with the tools removed,
 * one extra system message that lists them, and {@code response_format: json_schema} for {@code
 * {action: call|answer, tool: <enum of offered names>, arguments: object, answer: string}}. Ollama
 * turns the schema into constrained decoding, so the reply is always one of the two shapes. {@link
 * ToolRouterMode#REQUIRED} (vLLM): the tools stay, a {@code final_answer} tool is added and {@code
 * tool_choice: required} guarantees one structured call per round.
 *
 * <p>Either way the chosen call runs through the shared {@link ToolCallingManager}, exactly as
 * {@link TextToolCallRecoveringChatModel} does for a recovered text call (argument validation,
 * unknown-tool feedback, round caps), and the model is called again with the tool result until it
 * answers or {@code maxRounds} is reached. Usage is cumulative across rounds. Streaming is answered
 * as one chunk after the loop: a schema-constrained reply has no useful token-by-token form.
 */
public final class ConstrainedToolRouterChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ConstrainedToolRouterChatModel.class);

    static final String ROUTER_METRIC = "chat.tool.constrained_router";
    static final String SCHEMA_NAME = "tool_router";
    static final String FINAL_ANSWER_TOOL = "final_answer";
    /** Marker on a converted assistant message, for step-event consumers and tests. */
    static final String ROUTED_PROPERTY = "routedByConstraint";

    public static final int DEFAULT_MAX_ROUNDS = 6;

    /** Model names whose server refused {@code response_format: json_schema}; process-wide, see {@link #route}. */
    static final Set<String> FORMAT_UNAVAILABLE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ChatModel delegate;
    private final ToolRouterMode mode;
    private final ToolCallingManager toolCallingManager;
    private final ObjectMapper objectMapper;
    private final int maxRounds;
    private final MeterRegistry meterRegistry;
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    /**
     * Reads the router's instructions ({@code shim.router.format}, {@code shim.router.required}) from the prompt
     * registry for the agent and tenant of each request (carried in the request's tool context, see {@link
     * ToolLoopTranscript#forPrompts}); the shipped wording until set.
     */
    public ConstrainedToolRouterChatModel withPrompts(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
        return this;
    }

    public ConstrainedToolRouterChatModel(
        ChatModel delegate, ToolRouterMode mode, ToolCallingManager toolCallingManager, ObjectMapper objectMapper,
        int maxRounds, MeterRegistry meterRegistry
    ) {
        this.delegate = delegate;
        this.mode = mode == null ? ToolRouterMode.FORMAT : mode;
        this.toolCallingManager = toolCallingManager;
        this.objectMapper = objectMapper;
        this.maxRounds = maxRounds > 0 ? maxRounds : DEFAULT_MAX_ROUNDS;
        this.meterRegistry = meterRegistry;
    }

    public ChatModel delegate() {
        return delegate;
    }

    public ToolRouterMode mode() {
        return mode;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<ToolCallback> tools = offeredTools(prompt);
        if (tools.isEmpty() || !(prompt.getOptions() instanceof OpenAiChatOptions)) {
            return delegate.call(prompt);
        }
        return route(prompt, tools, 0, null);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        List<ToolCallback> tools = offeredTools(prompt);
        if (tools.isEmpty() || !(prompt.getOptions() instanceof OpenAiChatOptions)) {
            return delegate.stream(prompt);
        }
        return Mono.fromCallable(() -> route(prompt, tools, 0, null)).subscribeOn(TenantAwareSchedulers.boundedElastic()).flux();
    }

    // --- the loop ----------------------------------------------------------------------

    private ChatResponse route(Prompt original, List<ToolCallback> tools, int round, ChatResponse previous) {
        String modelName = modelName(original);
        if (mode == ToolRouterMode.FORMAT && FORMAT_UNAVAILABLE.contains(modelName)) {
            return delegate.call(original);
        }
        ChatResponse response;
        try {
            response = delegate.call(mode == ToolRouterMode.FORMAT
                ? formatPrompt(original, tools) : requiredPrompt(original, tools));
        } catch (RuntimeException e) {
            if (mode == ToolRouterMode.FORMAT && round == 0 && isConstraintRefusal(e)) {
                // Seen live: Ollama 0.19 answers HTTP 400 "structured output is unavailable" for a
                // model its MLX runner serves (qwen3.5:4b). The provider's own loop still works, so
                // the turn goes through unrouted and this model is not asked again until restart.
                log.warn("{} refused the reply schema ({}); the constrained tool router is off for this"
                    + " model until core restarts. Message: {}", modelName, mode, e.getMessage());
                FORMAT_UNAVAILABLE.add(modelName);
                increment("outcome", "format_unavailable");
                return delegate.call(original);
            }
            throw e;
        }
        if (previous != null) {
            response = TextToolCallRecoveringChatModel.withCumulativeUsage(response, previous);
        }
        Decision decision = mode == ToolRouterMode.FORMAT ? decideFromJson(response, tools) : decideFromToolCall(response);
        if (decision.answer != null) {
            increment("outcome", "answer");
            return answer(response, decision.answer);
        }
        if (decision.call == null) {
            increment("outcome", "passthrough");
            return response;
        }
        if (round >= maxRounds) {
            log.warn("Constrained tool router stopped after {} rounds; releasing the reply (last call: {}).",
                round, decision.call.name());
            increment("outcome", "limit");
            return answer(response, "I could not finish this with the tools available after " + round + " steps.");
        }
        log.info("Constrained tool router ({}) chose tool {} (round={})", mode, decision.call.name(), round + 1);
        increment("outcome", "call");
        ChatResponse converted = converted(response, decision.call);
        ToolExecutionResult result = toolCallingManager.executeToolCalls(original, converted);
        if (result.returnDirect()) {
            return ChatResponse.builder().from(converted)
                .generations(ToolExecutionResult.buildGenerations(result)).build();
        }
        return route(new Prompt(result.conversationHistory(), original.getOptions()), tools, round + 1, converted);
    }

    private record Decision(AssistantMessage.ToolCall call, String answer) {}

    private static String modelName(Prompt prompt) {
        String model = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
        return model == null ? "" : model;
    }

    /** A 4xx that names the constraint: the server understood the request and declined the schema. */
    static boolean isConstraintRefusal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.web.client.RestClientResponseException http) {
                if (!http.getStatusCode().is4xxClientError()) {
                    return false;
                }
                String body = (http.getResponseBodyAsString() + " " + http.getMessage()).toLowerCase(java.util.Locale.ROOT);
                return body.contains("structured output") || body.contains("response_format")
                    || body.contains("json_schema") || body.contains("format");
            }
            if (t instanceof org.springframework.ai.retry.NonTransientAiException
                && t.getMessage() != null) {
                String message = t.getMessage().toLowerCase(java.util.Locale.ROOT);
                return message.contains("structured output") || message.contains("response_format")
                    || message.contains("json_schema");
            }
        }
        return false;
    }

    /** FORMAT mode: the reply is the router JSON. Anything else is released as the answer text. */
    private Decision decideFromJson(ChatResponse response, List<ToolCallback> tools) {
        String text = textOf(response).trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[A-Za-z0-9]*\\s*", "").replaceFirst("\\s*```\\s*$", "");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(text);
        } catch (java.io.IOException e) {
            return new Decision(null, text.isEmpty() ? null : text);
        }
        if (node == null || !node.isObject()) {
            return new Decision(null, text);
        }
        String action = node.path("action").asText("");
        if ("call".equalsIgnoreCase(action) && node.hasNonNull("tool")) {
            String tool = node.get("tool").asText();
            JsonNode arguments = node.get("arguments");
            String json = arguments == null || !arguments.isObject() ? "{}" : arguments.toString();
            return new Decision(new AssistantMessage.ToolCall(
                "router_" + UUID.randomUUID().toString().substring(0, 8), "function", tool, json), null);
        }
        if (node.hasNonNull("answer")) {
            return new Decision(null, node.get("answer").asText());
        }
        // A tool-call shape without the wrapper ({"name": ..., "arguments": ...}), or free text.
        List<AssistantMessage.ToolCall> bare = TextToolCallParser.parse(
            text, tools.stream().map(t -> t.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()),
            objectMapper);
        return bare.isEmpty() ? new Decision(null, text) : new Decision(bare.get(0), null);
    }

    /** REQUIRED mode: the reply carries one structured call; {@code final_answer} ends the loop. */
    private Decision decideFromToolCall(ChatResponse response) {
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output == null || !output.hasToolCalls()) {
                continue;
            }
            AssistantMessage.ToolCall call = output.getToolCalls().get(0);
            if (FINAL_ANSWER_TOOL.equals(call.name())) {
                try {
                    JsonNode args = objectMapper.readTree(call.arguments() == null ? "{}" : call.arguments());
                    return new Decision(null, args.path("answer").asText(""));
                } catch (java.io.IOException e) {
                    return new Decision(null, call.arguments());
                }
            }
            return new Decision(call, null);
        }
        String text = textOf(response);
        return new Decision(null, text.isEmpty() ? null : text);
    }

    // --- request shaping ---------------------------------------------------------------

    /** The original prompt with the tools removed, a catalog system message added and the router schema enforced. */
    Prompt formatPrompt(Prompt original, List<ToolCallback> tools) {
        OpenAiChatOptions options = ((OpenAiChatOptions) original.getOptions()).copy();
        options.setToolCallbacks(List.of());
        options.setToolNames(Set.of());
        options.setInternalToolExecutionEnabled(false);
        options = com.agenticai.core.chat.structured.StructuredOutput.withOllamaFormat(
            options, SCHEMA_NAME, routerSchema(tools).toString());
        return new Prompt(withRouterInstructions(original, tools), options);
    }

    /** The original prompt plus a {@code final_answer} tool and {@code tool_choice: required}; tools run here, not in the provider. */
    Prompt requiredPrompt(Prompt original, List<ToolCallback> tools) {
        OpenAiChatOptions options = ((OpenAiChatOptions) original.getOptions()).copy();
        List<ToolCallback> withAnswer = new ArrayList<>(tools);
        withAnswer.add(new FinalAnswerTool());
        options.setToolCallbacks(withAnswer);
        options.setToolNames(Set.of());
        options.setInternalToolExecutionEnabled(false);
        options.setToolChoice("required");
        List<Message> messages = new ArrayList<>(original.getInstructions());
        messages.add(insertionIndex(messages), new SystemMessage(
            ChatPrompts.text(prompts, ChatPrompts.ROUTER_REQUIRED, ToolLoopRunHooks.promptRun(original),
                "tool", FINAL_ANSWER_TOOL)));
        return new Prompt(messages, options);
    }

    ObjectNode routerSchema(List<ToolCallback> tools) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("action").put("type", "string").putArray("enum").add("call").add("answer");
        ArrayNode names = properties.putObject("tool").put("type", "string").putArray("enum");
        for (ToolCallback tool : tools) {
            names.add(tool.getToolDefinition().name());
        }
        properties.putObject("arguments").put("type", "object");
        properties.putObject("answer").put("type", "string");
        schema.putArray("required").add("action");
        return schema;
    }

    private List<Message> withRouterInstructions(Prompt original, List<ToolCallback> tools) {
        List<Message> instructions = original.getInstructions();
        StringBuilder text = new StringBuilder(
            ChatPrompts.text(prompts, ChatPrompts.ROUTER_FORMAT, ToolLoopRunHooks.promptRun(original)) + "\nTools:\n");
        for (ToolCallback tool : tools) {
            ToolDefinition definition = tool.getToolDefinition();
            text.append("- ").append(definition.name()).append(": ").append(oneLine(definition.description()))
                .append(" Parameters: ").append(oneLine(definition.inputSchema())).append('\n');
        }
        List<Message> messages = new ArrayList<>(instructions);
        messages.add(insertionIndex(messages), new SystemMessage(text.toString()));
        return messages;
    }

    /** Right after the leading system message(s), so provider templates see system text first. */
    private static int insertionIndex(List<Message> messages) {
        int i = 0;
        while (i < messages.size() && messages.get(i).getMessageType() == MessageType.SYSTEM) {
            i++;
        }
        return i;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** Never executed: in REQUIRED mode the loop ends when the model calls it. */
    private static final class FinalAnswerTool implements ToolCallback {
        private final ToolDefinition definition = ToolDefinition.builder()
            .name(FINAL_ANSWER_TOOL)
            .description("Send your reply to the user. Call this when no other tool is needed or you have the results you need.")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\",\"description\":\"The reply, in full.\"}},\"required\":[\"answer\"]}")
            .build();

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return toolInput;
        }
    }

    // --- response shaping --------------------------------------------------------------

    private static ChatResponse converted(ChatResponse source, AssistantMessage.ToolCall call) {
        AssistantMessage assistant = AssistantMessage.builder()
            .content("")
            .properties(Map.of(ROUTED_PROPERTY, Boolean.TRUE))
            .toolCalls(List.of(call))
            .build();
        Generation generation = new Generation(
            assistant, ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build());
        return ChatResponse.builder().from(source).generations(List.of(generation)).build();
    }

    private static ChatResponse answer(ChatResponse source, String text) {
        Generation generation = new Generation(
            new AssistantMessage(text), ChatGenerationMetadata.builder().finishReason("STOP").build());
        return ChatResponse.builder().from(source).generations(List.of(generation)).build();
    }

    /** The offered tool callbacks, or empty when this request cannot be routed (no tools, or the caller runs the loop). */
    static List<ToolCallback> offeredTools(Prompt prompt) {
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return List.of();
        }
        if (Boolean.FALSE.equals(options.getInternalToolExecutionEnabled())) {
            return List.of();
        }
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        if (options.getToolCallbacks() != null) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                byName.putIfAbsent(callback.getToolDefinition().name(), callback);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Generation generation : response.getResults()) {
            if (generation != null && generation.getOutput() != null && generation.getOutput().getText() != null) {
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
            meterRegistry.counter(ROUTER_METRIC, tags).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record metric {}", ROUTER_METRIC, e);
        }
    }
}
