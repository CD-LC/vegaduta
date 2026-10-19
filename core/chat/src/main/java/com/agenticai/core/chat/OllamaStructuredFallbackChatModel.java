package com.agenticai.core.chat;

import com.agenticai.core.chat.structured.JsonSchemaCheck;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.chat.structured.StructuredOutputException;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import reactor.core.publisher.Flux;

/**
 * Keeps a structured-output request alive on an Ollama model whose server refuses {@code
 * response_format: json_schema}. Seen live: Ollama 0.19 answers HTTP 400 "structured output is
 * unavailable" for a model its MLX runner serves (qwen3.5:4b). Before this wrapper that 400 failed
 * the call; now:
 *
 * <ol>
 *   <li>The request goes out unchanged (schema-constrained decoding is the better path).
 *   <li>On a 4xx that names the constraint ({@link ConstrainedToolRouterChatModel#isConstraintRefusal}),
 *       the model is remembered as refusing until core restarts, and the same request is sent again
 *       <b>without</b> the response format, with the schema written into the system message instead
 *       (prompt-based JSON).
 *   <li>The reply is parsed ({@link StructuredOutput#extractJson}) and checked against the schema
 *       ({@link JsonSchemaCheck}). A reply that does not fit gets exactly one repair turn that says
 *       what was wrong ({@link StructuredOutput#repairPrompt}).
 *   <li>Whatever comes back last is returned: clean JSON text when it fitted, otherwise the model's
 *       own text untouched, so the caller's own parse-and-repair path decides, exactly as it does
 *       for any provider without native schemas. This wrapper never throws on a bad reply.
 * </ol>
 *
 * <p>Only wrapped around an Ollama chat model when the caller asked for a schema and {@code
 * app.llm.structured-output.ollama-format.enabled} is on; a request without a JSON-schema response
 * format, and every streaming call, pass straight through.
 */
final class OllamaStructuredFallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OllamaStructuredFallbackChatModel.class);

    /** Models whose server refused the response format; process-wide, like the tool router's set. */
    static final Set<String> FORMAT_REFUSED = ConcurrentHashMap.newKeySet();

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ChatModel delegate;
    private final JsonNode schema;
    private final String schemaText;

    OllamaStructuredFallbackChatModel(ChatModel delegate, String jsonSchema) {
        this.delegate = delegate;
        JsonNode parsed = null;
        try {
            parsed = MAPPER.readTree(jsonSchema);
        } catch (IOException | RuntimeException e) {
            // an unusable schema still works as prompt text; it just cannot be checked
        }
        this.schema = parsed != null && parsed.isObject() ? parsed : null;
        this.schemaText = this.schema != null ? this.schema.toString() : String.valueOf(jsonSchema);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!(prompt != null && prompt.getOptions() instanceof OpenAiChatOptions options) || !asksForSchema(options)) {
            return delegate.call(prompt);
        }
        String model = options.getModel() == null ? "" : options.getModel();
        if (!FORMAT_REFUSED.contains(model)) {
            try {
                return delegate.call(prompt);
            } catch (RuntimeException e) {
                if (!ConstrainedToolRouterChatModel.isConstraintRefusal(e)) {
                    throw e;
                }
                FORMAT_REFUSED.add(model);
                log.warn("Ollama refused the reply schema for model '{}'; structured replies use prompt-based JSON"
                        + " with validation until core restarts. Message: {}", model, e.getMessage());
            }
        }
        return promptBased(prompt, options);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static boolean asksForSchema(OpenAiChatOptions options) {
        ResponseFormat format = options.getResponseFormat();
        return format != null && format.getType() == ResponseFormat.Type.JSON_SCHEMA;
    }

    // --- the fallback -------------------------------------------------------------------

    private ChatResponse promptBased(Prompt original, OpenAiChatOptions options) {
        OpenAiChatOptions plain = options.copy();
        plain.setResponseFormat(null);
        List<Message> messages = withSchemaInstruction(original.getInstructions());
        ChatResponse response = delegate.call(new Prompt(messages, plain));
        String text = textOf(response);
        String problem = problemWith(text);
        if (problem == null) {
            return withText(response, StructuredOutput.extractJson(text).orElse(text));
        }
        log.info("Prompt-based structured reply did not fit the schema ({}); asking once more.", problem);
        List<Message> repair = new ArrayList<>(messages);
        repair.add(new AssistantMessage(text));
        repair.add(new UserMessage(StructuredOutput.repairPrompt(
                new StructuredOutputException(problem, text), schemaInstruction())));
        ChatResponse second = TextToolCallRecoveringChatModel.withCumulativeUsage(
                delegate.call(new Prompt(repair, plain)), response);
        String repairedText = textOf(second);
        if (problemWith(repairedText) == null) {
            return withText(second, StructuredOutput.extractJson(repairedText).orElse(repairedText));
        }
        log.warn("Prompt-based structured reply still does not fit the schema after one repair: {}", problem);
        return second;
    }

    /** The schema as an extra paragraph of the first system message (a new one when there is none). */
    private List<Message> withSchemaInstruction(List<Message> original) {
        List<Message> messages = new ArrayList<>(original);
        String instruction = schemaInstruction();
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage system) {
            messages.set(0, new SystemMessage(system.getText() + "\n\n" + instruction));
        } else {
            messages.add(0, new SystemMessage(instruction));
        }
        return messages;
    }

    private String schemaInstruction() {
        return "Reply with ONLY one JSON value that is valid for the JSON Schema below. No markdown code fences,"
                + " no commentary before or after.\nJSON Schema: " + schemaText;
    }

    /** Null when {@code text} holds JSON that fits the schema; otherwise what is wrong with it. */
    private String problemWith(String text) {
        Optional<String> json = StructuredOutput.extractJson(text);
        if (json.isEmpty()) {
            return "the reply contained no JSON value";
        }
        if (schema == null) {
            return null;
        }
        try {
            List<String> problems = JsonSchemaCheck.problems(schema, MAPPER.readTree(json.get()));
            return problems.isEmpty() ? null : String.join("; ", problems);
        } catch (IOException | RuntimeException e) {
            return "the reply was not valid JSON";
        }
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static ChatResponse withText(ChatResponse response, String text) {
        if (response == null || response.getResult() == null) {
            return response;
        }
        ChatGenerationMetadata meta = response.getResult().getMetadata();
        Generation generation = new Generation(AssistantMessage.builder().content(text).build(), meta);
        return ChatResponse.builder().from(response).generations(List.of(generation)).build();
    }
}
