package com.agenticai.core.chat.structured;

import com.agenticai.core.json.JsonMappers;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one place "ask a model for JSON, get JSON back" is implemented. Before this class existed, a
 * dozen services each carried their own few lines of fence-stripping and {@code indexOf('{')} /
 * {@code lastIndexOf('}')} slicing - all slightly different, and all wrong in the same two ways: a
 * reply with a second brace-y thing after the JSON ("{...} Let me know if you need {more}")
 * produced an unparseable first-to-last slice, and prose before the JSON defeated the variants that
 * only stripped fences.
 *
 * <h2>What it does (all pure - no network, no Spring context)</h2>
 *
 * <ul>
 *   <li><b>Locate</b> the JSON in a model reply: a surrounding markdown fence is stripped; if the
 *       remaining text is a single complete JSON document it is used as-is; otherwise the text is
 *       scanned for the first <em>balanced</em> {@code {...}} / {@code [...]} span (string- and
 *       escape-aware, so braces inside string values do not confuse it) that parses. See {@link
 *       #extractJson}, {@link #extractJsonObject}, {@link #extractJsonArray}.
 *   <li><b>Parse</b> it - to a tree ({@link #readTree}, {@link #readObject}, {@link #readArray},
 *       empty when nothing usable was found) or bound to a type ({@link #parse}, which throws
 *       {@link StructuredOutputException} with a repair-prompt-ready message). Always through the
 *       CALLER's {@link ObjectMapper}, so its module/feature configuration applies.
 *   <li><b>Describe</b> the wanted shape to the model: {@link #formatInstructions} / {@link
 *       #jsonSchema} delegate to Spring AI's {@link BeanOutputConverter} schema generation, so the
 *       prompt and the binding type cannot drift apart.
 *   <li><b>Repair</b> once: {@link #parseWithRepair} runs the caller-supplied re-ask function a
 *       single time with the parse error. The helper never calls a model itself - the caller owns
 *       the client, budget, deadline and turn accounting around a real LLM call.
 *   <li><b>Ask the provider for JSON mode</b> where the options type supports it: {@link
 *       #withJsonMode} (OpenAI-compatible {@code response_format: json_object}, Gemini {@code
 *       responseMimeType: application/json}); a no-op for every other provider.
 * </ul>
 *
 * <h2>Shape rule</h2>
 *
 * If the (fence-stripped) reply is ONE complete JSON document of the requested shape, that is the
 * answer. Otherwise - prose around it, trailing chatter, a document of the wrong shape wrapping the
 * wanted one - the first balanced span of the requested shape that parses wins. The {@code
 * *Tree}/{@code *Json} variants accept any JSON value as a whole-reply document and, when scanning,
 * prefer the first object over the first array (a caller that wants an object checks {@code
 * isObject()} itself when it needs a distinct error for "valid JSON, wrong shape").
 */
public final class StructuredOutput {

    /** MIME type Gemini's {@code responseMimeType} takes to force a JSON reply. */
    public static final String APPLICATION_JSON = "application/json";

    private static final String FENCE = "```";

    /** Syntax-only validator for the mapper-less {@code extract*} overloads. */
    static final ObjectMapper DEFAULT_MAPPER = JsonMappers.plain();

    private StructuredOutput() {}

    // ------------------------------------------------------------------ fences

    /**
     * Trims {@code raw} and removes a surrounding markdown code fence, with or without a language
     * tag: a leading {@code ```lang} line is dropped and the text is cut at the LAST {@code ```}
     * (so chatter after the closing fence goes too). A reply that only ends with a fence has that
     * trailing fence removed. Text without fences is returned trimmed. Never null.
     */
    public static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith(FENCE)) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            } else {
                // Single-line fence: ```json{"a":1}``` - drop the backticks and a language tag
                // only when JSON directly follows it.
                text =
                        text.substring(FENCE.length())
                                .replaceFirst("^[A-Za-z0-9_+-]+(?=\\s*[\\[{])", "");
            }
            int closing = text.lastIndexOf(FENCE);
            if (closing >= 0) {
                text = text.substring(0, closing);
            }
            return text.trim();
        }
        if (text.endsWith(FENCE)) {
            return text.substring(0, text.length() - FENCE.length()).trim();
        }
        return text;
    }

    // ---------------------------------------------------------------- locating

    /** The JSON text (object, array, or a whole-reply scalar) in {@code raw}, or empty. */
    public static Optional<String> extractJson(String raw) {
        return JsonLocator.locate(DEFAULT_MAPPER, raw, JsonLocator.Shape.ANY)
                .map(JsonLocator.Located::text);
    }

    /** The first JSON object in {@code raw} (see the class javadoc's shape rule), or empty. */
    public static Optional<String> extractJsonObject(String raw) {
        return JsonLocator.locate(DEFAULT_MAPPER, raw, JsonLocator.Shape.OBJECT)
                .map(JsonLocator.Located::text);
    }

    /** The first JSON array in {@code raw} (see the class javadoc's shape rule), or empty. */
    public static Optional<String> extractJsonArray(String raw) {
        return JsonLocator.locate(DEFAULT_MAPPER, raw, JsonLocator.Shape.ARRAY)
                .map(JsonLocator.Located::text);
    }

    // ------------------------------------------------------------ tree parsing

    /**
     * The JSON value in {@code raw} as a tree - any value when the whole reply is one JSON
     * document, otherwise the first object found in it, else the first array. Empty when there is
     * none.
     */
    public static Optional<JsonNode> readTree(ObjectMapper mapper, String raw) {
        return JsonLocator.locate(mapper, raw, JsonLocator.Shape.ANY)
                .map(JsonLocator.Located::node);
    }

    /** Like {@link #readTree} but only ever returns an object node ({@code isObject()} holds). */
    public static Optional<JsonNode> readObject(ObjectMapper mapper, String raw) {
        return JsonLocator.locate(mapper, raw, JsonLocator.Shape.OBJECT)
                .map(JsonLocator.Located::node);
    }

    /** Like {@link #readTree} but only ever returns an array node ({@code isArray()} holds). */
    public static Optional<JsonNode> readArray(ObjectMapper mapper, String raw) {
        return JsonLocator.locate(mapper, raw, JsonLocator.Shape.ARRAY)
                .map(JsonLocator.Located::node);
    }

    /**
     * {@link #readObject}, throwing instead of returning empty - the natural {@code parser}
     * argument for {@link #parseWithRepair(String, Function, RepairFunction)} on tree-based call
     * sites.
     */
    public static JsonNode requireObject(ObjectMapper mapper, String raw) {
        return readObject(mapper, raw)
                .orElseThrow(
                        () ->
                                new StructuredOutputException(
                                        "The reply did not contain a JSON object.", raw));
    }

    // ----------------------------------------------------------- typed parsing

    /**
     * Locates the JSON in {@code raw} and binds it to {@code type} with the caller's mapper. A
     * collection/array target is searched for as an array; anything else as any JSON document,
     * objects first (see {@link #readTree}).
     *
     * @throws StructuredOutputException when no JSON is present, or it does not bind to {@code
     *     type}; the message names which, and carries Jackson's own reason.
     */
    public static <T> T parse(ObjectMapper mapper, String raw, Class<T> type) {
        return bind(mapper, raw, mapper.getTypeFactory().constructType(type), type.getSimpleName());
    }

    /** {@link #parse(ObjectMapper, String, Class)} for generic targets ({@code List<Foo>} etc.). */
    public static <T> T parse(ObjectMapper mapper, String raw, TypeReference<T> type) {
        return bind(
                mapper,
                raw,
                mapper.getTypeFactory().constructType(type),
                type.getType().getTypeName());
    }

    private static <T> T bind(ObjectMapper mapper, String raw, JavaType target, String typeName) {
        JsonLocator.Shape shape =
                target.isCollectionLikeType() || target.isArrayType()
                        ? JsonLocator.Shape.ARRAY
                        : JsonLocator.Shape.ANY;
        String json =
                JsonLocator.locate(mapper, raw, shape)
                        .map(JsonLocator.Located::text)
                        .orElseThrow(
                                () ->
                                        new StructuredOutputException(
                                                (raw == null || raw.isBlank()
                                                                ? "The reply was empty"
                                                                : "The reply did not contain valid JSON")
                                                        + " - expected a JSON document for "
                                                        + typeName
                                                        + ".",
                                                raw));
        try {
            return mapper.readValue(json, target);
        } catch (JacksonException e) {
            throw new StructuredOutputException(
                    "The reply's JSON does not match the expected "
                            + typeName
                            + " shape: "
                            + e.getOriginalMessage(),
                    raw,
                    e);
        }
    }

    // ---------------------------------------------------------------- repair

    /**
     * Re-asks the model after a parse failure. Implementations run ONE more model turn and return
     * its text; {@link #repairPrompt} builds a suitable instruction from the error. Anything the
     * function throws (budget exhausted, cancellation, provider outage) propagates untouched - it
     * is that failure, not a parse failure.
     */
    @FunctionalInterface
    public interface RepairFunction {
        String reAsk(String previousReply, StructuredOutputException error);
    }

    /**
     * Applies {@code parser} to {@code raw}; if it throws {@link StructuredOutputException} and a
     * {@code repair} function is supplied, asks for exactly one corrected reply and parses that. A
     * second failure is thrown with the first attached as a suppressed exception.
     */
    public static <T> T parseWithRepair(
            String raw, Function<String, T> parser, RepairFunction repair) {
        try {
            return parser.apply(raw);
        } catch (StructuredOutputException first) {
            if (repair == null) {
                throw first;
            }
            String repaired = repair.reAsk(raw, first);
            try {
                return parser.apply(repaired);
            } catch (StructuredOutputException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    /** {@link #parse(ObjectMapper, String, Class)} with one repair attempt. */
    public static <T> T parseWithRepair(
            ObjectMapper mapper, String raw, Class<T> type, RepairFunction repair) {
        return parseWithRepair(raw, text -> parse(mapper, text, type), repair);
    }

    /**
     * A follow-up user message for a repair turn: what went wrong, plus (optionally) the format
     * instructions again. Never echoes the failed reply - it is already in the conversation.
     */
    public static String repairPrompt(StructuredOutputException error, String formatInstructions) {
        StringBuilder sb =
                new StringBuilder("Your previous reply could not be used: ")
                        .append(error.getMessage())
                        .append(
                                "\nReply again with ONLY the corrected JSON - no markdown code fences, no commentary.");
        if (formatInstructions != null && !formatInstructions.isBlank()) {
            sb.append("\n\n").append(formatInstructions);
        }
        return sb.toString();
    }

    // ------------------------------------------------------ format instructions

    /**
     * Prompt text telling the model to answer with JSON matching {@code type}'s JSON Schema -
     * Spring AI's {@link BeanOutputConverter#getFormat()}, so the schema is generated from the same
     * class the reply is later bound to.
     */
    public static <T> String formatInstructions(Class<T> type) {
        return new BeanOutputConverter<T>(type).getFormat();
    }

    /** The JSON Schema (draft 2020-12) Spring AI generates for {@code type}. */
    public static <T> String jsonSchema(Class<T> type) {
        return new BeanOutputConverter<T>(type).getJsonSchema();
    }

    // ---------------------------------------------------------------- JSON mode

    /** True when {@link #withJsonMode} would change {@code options}' provider request. */
    public static boolean supportsJsonMode(ChatOptions options) {
        return options != null
                && (options.getClass() == OpenAiChatOptions.class
                        || options.getClass() == GoogleGenAiChatOptions.class);
    }

    /**
     * Returns a COPY of {@code options} that asks the provider for a JSON-only reply, or {@code
     * options} itself when the provider has no such switch (Anthropic, Bedrock, ...) or the options
     * already request a structured format. The input is never mutated - options objects are often
     * shared defaults.
     *
     * <ul>
     *   <li>{@link OpenAiChatOptions} (OpenAI and every OpenAI-compatible provider built on it):
     *       {@code response_format: {"type": "json_object"}}. OpenAI rejects this unless the word
     *       "JSON" appears in the messages - pair it with {@link #formatInstructions} or an
     *       explicit "reply with JSON" instruction. An existing {@code json_object} / {@code
     *       json_schema} format is left as-is.
     *   <li>{@link GoogleGenAiChatOptions}: {@code responseMimeType: application/json}. Gemini does
     *       not combine this with function calling on every model - use it on tool-free utility
     *       calls.
     * </ul>
     *
     * <p>Exact-class match on purpose: a subclass's {@code copy()} would return the base type and
     * the generic cast would fail at the caller.
     */
    @SuppressWarnings("unchecked")
    public static <O extends ChatOptions> O withJsonMode(O options) {
        if (options == null) {
            return null;
        }
        if (options.getClass() == OpenAiChatOptions.class) {
            OpenAiChatOptions openAi = (OpenAiChatOptions) options;
            ResponseFormat existing = openAi.getResponseFormat();
            if (existing != null
                    && existing.getType() != null
                    && existing.getType() != ResponseFormat.Type.TEXT) {
                return options;
            }
            OpenAiChatOptions copy =
                    openAi.mutate()
                            .responseFormat(
                                    ResponseFormat.builder()
                                            .type(ResponseFormat.Type.JSON_OBJECT)
                                            .build())
                            .build();
            return (O) copy;
        }
        if (options.getClass() == GoogleGenAiChatOptions.class) {
            GoogleGenAiChatOptions gemini = (GoogleGenAiChatOptions) options;
            if (APPLICATION_JSON.equalsIgnoreCase(gemini.getResponseMimeType())) {
                return options;
            }
            GoogleGenAiChatOptions copy =
                    gemini.mutate().responseMimeType(APPLICATION_JSON).build();
            return (O) copy;
        }
        return options;
    }

    // ------------------------------------------------------ native JSON schema

    /**
     * OpenAI model ids that accept {@code response_format: json_schema}: gpt-4o from the 2024-08-06
     * snapshot on (earlier snapshots 400), gpt-4o-mini, gpt-4.1 family, gpt-5 family, and o1 / o3 /
     * o3-mini / o4-mini (NOT o1-mini / o1-preview). Exact matches with an optional dated snapshot
     * suffix - not prefixes - so audio/realtime/search variants never match.
     */
    private static final java.util.regex.Pattern OPENAI_SCHEMA_MODEL =
            java.util.regex.Pattern.compile(
                    "^(gpt-4o(-2024-(08-06|11-20)|-202[5-9]-\\d\\d-\\d\\d)?"
                            + "|gpt-4o-mini(-2024-07-18|-202[5-9]-\\d\\d-\\d\\d)?"
                            + "|gpt-4\\.1(-mini|-nano)?(-\\d{4}-\\d\\d-\\d\\d)?"
                            + "|gpt-5(\\.\\d+)?(-mini|-nano)?(-\\d{4}-\\d\\d-\\d\\d)?"
                            + "|(o1|o3|o3-mini|o4-mini)(-\\d{4}-\\d\\d-\\d\\d)?)$");

    /**
     * Gemini 1.5+ text models honour a response schema; image/audio/live/embedding variants do not.
     */
    private static final java.util.regex.Pattern GEMINI_SCHEMA_MODEL =
            java.util.regex.Pattern.compile(
                    "^gemini-(1\\.5|2\\.|3)(?!.*(image|tts|audio|live|embedding|native)).*$");

    /**
     * True when {@link #withJsonSchema} can enforce a schema natively for {@code options}: an exact
     * {@link OpenAiChatOptions} on an OpenAI model id in {@link #OPENAI_SCHEMA_MODEL}, or an exact
     * {@link GoogleGenAiChatOptions} on a Gemini text model. Everything else - including
     * OpenAI-compatible providers (Groq, Together, Gemini-compat, OpenRouter "vendor/model" ids),
     * Anthropic, Bedrock and Ollama - is deliberately not matched and keeps its previous behaviour.
     */
    public static boolean supportsJsonSchema(ChatOptions options) {
        if (options == null || options.getModel() == null) {
            return false;
        }
        String m = options.getModel().toLowerCase(Locale.ROOT);
        if (options.getClass() == OpenAiChatOptions.class) {
            return OPENAI_SCHEMA_MODEL.matcher(m).matches();
        }
        if (options.getClass() == GoogleGenAiChatOptions.class) {
            return GEMINI_SCHEMA_MODEL.matcher(m).matches();
        }
        return false;
    }

    /**
     * Native structured output with graceful fallback. When {@code nativeEnabled} (the config
     * kill-switch) is true, the provider supports it ({@link #supportsJsonSchema}) and {@code
     * jsonSchema} parses as a JSON object, returns a COPY of {@code options} that makes the
     * provider enforce the schema:
     *
     * <ul>
     *   <li>OpenAI-compatible: {@code response_format: {type: json_schema, json_schema: {name,
     *       schema, strict}}}. {@code strict} is only requested when the schema meets OpenAI's
     *       strict-mode rules (every object closed with {@code additionalProperties:false} and
     *       every property listed in {@code required}); otherwise it is sent non-strict.
     *   <li>Gemini: {@code responseMimeType: application/json} plus {@code responseSchema}, with
     *       the JSON-Schema-only keywords Gemini rejects ({@code $schema}, {@code
     *       additionalProperties}) removed. A schema using {@code $ref}/{@code $defs} falls back to
     *       plain JSON mode.
     * </ul>
     *
     * In every other case this is exactly {@link #withJsonMode} - the behaviour before native
     * schemas existed. Never mutates the input.
     */
    public static <O extends ChatOptions> O withJsonSchema(
            O options, String schemaName, String jsonSchema, boolean nativeEnabled) {
        if (options == null || !nativeEnabled || !supportsJsonSchema(options)) {
            return withJsonMode(options);
        }
        JsonNode schema = JsonSchemaRules.parseSchema(jsonSchema);
        if (schema == null) {
            return withJsonMode(options);
        }
        if (options.getClass() == OpenAiChatOptions.class) {
            return withOpenAiSchema(options, JsonSchemaRules.schema);
        }
        return withGeminiSchema(options, schema);
    }

    @SuppressWarnings("unchecked")
    private static <O extends ChatOptions> O withOpenAiSchema(O options, JsonNode schema) {
        OpenAiChatOptions openAi = (OpenAiChatOptions) options;
        ResponseFormat existing = openAi.getResponseFormat();
        if (existing != null
                && existing.getType() != null
                && existing.getType() != ResponseFormat.Type.TEXT) {
            return options;
        }
        // Spring AI 2 (official OpenAI SDK model): ResponseFormat carries the schema as a JSON
        // string plus the
        // strict flag; the SDK model supplies the schema name itself, so the name is not sent from
        // here.
        OpenAiChatOptions copy =
                openAi.mutate()
                        .responseFormat(
                                ResponseFormat.builder()
                                        .type(ResponseFormat.Type.JSON_SCHEMA)
                                        .jsonSchema(schema.toString())
                                        .strict(JsonSchemaRules.isStrictCompatible(schema))
                                        .build())
                        .build();
        return (O) copy;
    }

    @SuppressWarnings("unchecked")
    private static <O extends ChatOptions> O withGeminiSchema(O options, JsonNode schema) {
        GoogleGenAiChatOptions gemini = (GoogleGenAiChatOptions) options;
        if (gemini.getResponseSchema() != null && !gemini.getResponseSchema().isBlank()) {
            return options;
        }
        if (JsonSchemaRules.containsKey(schema, "$ref")
                || JsonSchemaRules.containsKey(schema, "$defs")) {
            return withJsonMode(options);
        }
        JsonNode cleaned = schema.deepCopy();
        JsonSchemaRules.stripKeys(cleaned);
        GoogleGenAiChatOptions copy =
                gemini.mutate()
                        .responseMimeType(APPLICATION_JSON)
                        .responseSchema(cleaned.toString())
                        .build();
        return (O) copy;
    }

    // ---------------------------------------- Anthropic forced tool use / Ollama format

    /**
     * Claude model ids that honour {@code tool_choice: {type: tool, name}}: the Claude 3 family and
     * the {@code haiku|sonnet|opus} 4.x+ families (id lower-cased, optional dated/-latest suffix).
     * Claude 2 / instant do not match.
     */
    private static final java.util.regex.Pattern ANTHROPIC_TOOL_FORCING_MODEL =
            java.util.regex.Pattern.compile("^claude-(3|haiku|sonnet|opus)([-.].*)?$");

    /** Upper bound on a schema we are willing to ship as a tool {@code input_schema}. */
    static final int MAX_TOOL_INPUT_SCHEMA_CHARS = 100_000;

    /**
     * True when {@link #withAnthropicForcedTool} can force a tool for {@code options}: an exact
     * {@link AnthropicChatOptions} on a Claude model id that supports forced tool choice. Says
     * nothing about the schema or about extended thinking (that is checked per request, see {@link
     * #withAnthropicForcedTool}).
     */
    public static boolean supportsAnthropicToolForcing(ChatOptions options) {
        return options != null
                && options.getClass() == AnthropicChatOptions.class
                && options.getModel() != null
                && ANTHROPIC_TOOL_FORCING_MODEL
                        .matcher(options.getModel().toLowerCase(Locale.ROOT))
                        .matches();
    }

    /**
     * The {@code input_schema} to hand Anthropic for {@code jsonSchema}, or null when the schema is
     * not representable as a tool input schema - the caller then keeps the prompt-and-validate
     * path. Representable means: parses as a JSON object; the root is {@code "type": "object"}
     * (Anthropic requires an object root); the root is not an {@code anyOf/oneOf/allOf/not/enum}
     * combinator (Anthropic rejects those at the top level); every {@code $ref} is local ({@code
     * #...}); and it is not absurdly large. {@code $schema} is dropped from the returned copy; the
     * input is never mutated.
     */
    public static JsonNode anthropicToolInputSchema(String jsonSchema) {
        if (jsonSchema != null && jsonSchema.length() > MAX_TOOL_INPUT_SCHEMA_CHARS) {
            return null;
        }
        JsonNode schema = JsonSchemaRules.parseSchema(jsonSchema);
        if (schema == null) {
            return null;
        }
        JsonNode type = schema.get("type");
        if (type == null || !type.isString() || !"object".equals(type.asString())) {
            return null;
        }
        for (String combinator : List.of("anyOf", "oneOf", "allOf", "not", "enum")) {
            if (schema.has(combinator)) {
                return null;
            }
        }
        if (JsonSchemaRules.hasNonLocalRef(schema)) {
            return null;
        }
        ObjectNode copy = (ObjectNode) schema.deepCopy();
        copy.remove("$schema");
        return copy;
    }

    /** The Anthropic tool name for {@code schemaName} ({@code ^[a-zA-Z0-9_-]{1,64}$}). */
    public static String anthropicToolName(String schemaName) {
        return JsonSchemaRules.sanitizeSchemaName(schemaName);
    }

    /**
     * Native structured output for Anthropic by FORCED TOOL USE: returns a COPY of {@code options}
     * that offers one tool named {@code toolName} whose {@code input_schema} is {@code inputSchema}
     * and forces it with {@code tool_choice: {type: tool, name}}; the model's tool input is then
     * the structured result (see {@link #unwrapForcedTool}). Internal tool execution is switched
     * off so the framework never tries to "run" the pseudo-tool.
     *
     * <p>Returns {@code options} UNCHANGED (the caller falls back to prompt-and-validate) when: not
     * an Anthropic options type / model ({@link #supportsAnthropicToolForcing}); no usable schema;
     * extended thinking is on (Anthropic rejects a forced tool_choice together with thinking with a
     * 400 - an invalid request is never produced); the request already carries tools or a
     * tool_choice (forcing would starve the real tools). Never mutates the input.
     */
    @SuppressWarnings("unchecked")
    public static <O extends ChatOptions> O withAnthropicForcedTool(
            O options, String toolName, JsonNode inputSchema) {
        if (!supportsAnthropicToolForcing(options)
                || inputSchema == null
                || toolName == null
                || toolName.isBlank()) {
            return options;
        }
        AnthropicChatOptions aco = (AnthropicChatOptions) options;
        var thinking = aco.getThinking();
        if (thinking != null && thinking.type() != AnthropicApi.ThinkingType.DISABLED) {
            return options;
        }
        if (aco.getToolChoice() != null
                || (aco.getToolCallbacks() != null && !aco.getToolCallbacks().isEmpty())
                || (aco.getToolNames() != null && !aco.getToolNames().isEmpty())) {
            return options;
        }
        AnthropicChatOptions copy = aco.copy();
        copy.setThinking(null);
        copy.setToolCallbacks(List.of(new ForcedStructuredTool(toolName, inputSchema.toString())));
        copy.setToolChoice(new AnthropicApi.ToolChoiceTool(toolName, Boolean.TRUE));
        copy.setInternalToolExecutionEnabled(false);
        return (O) copy;
    }

    /**
     * Turns a response to a {@link #withAnthropicForcedTool} request back into plain JSON text: the
     * forced tool's input becomes the one and only generation (any preamble text is dropped so
     * {@code getResult()} is the JSON). When the model did NOT call the tool (it answered in text)
     * the response is returned untouched - the caller's prompt-and-validate parsing then applies,
     * which is the fallback. A tool call whose input is not a JSON object yields an empty-text
     * reply for the same reason (the caller's parse/repair path handles it).
     */
    public static ChatResponse unwrapForcedTool(ChatResponse response, String toolName) {
        if (response == null || response.getResults() == null || toolName == null) {
            return response;
        }
        String arguments = null;
        boolean called = false;
        for (Generation g : response.getResults()) {
            AssistantMessage m = g == null ? null : g.getOutput();
            if (m != null && m.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : m.getToolCalls()) {
                    if (toolName.equals(call.name())) {
                        called = true;
                        arguments = call.arguments();
                    }
                }
            }
        }
        if (!called) {
            return response;
        }
        JsonNode node =
                arguments == null ? null : JsonLocator.parseStrict(DEFAULT_MAPPER, arguments);
        String text = node != null && node.isObject() ? node.toString() : "";
        Generation structured =
                new Generation(
                        AssistantMessage.builder().content(text).build(),
                        ChatGenerationMetadata.builder().finishReason("end_turn").build());
        return ChatResponse.builder().from(response).generations(List.of(structured)).build();
    }

    /** True when {@link #withOllamaFormat} would change {@code options}. */
    public static boolean supportsOllamaFormat(ChatOptions options) {
        return options != null && options.getClass() == OpenAiChatOptions.class;
    }

    /**
     * Ollama (reached through its OpenAI-compatible {@code /v1/chat/completions}): asks the server
     * to constrain decoding to {@code jsonSchema} via {@code response_format: json_schema}
     * (Ollama's {@code format} parameter). {@code strict} is sent false - Ollama has no such
     * notion. A server too old to honour the field ignores it and replies in prose, which is the
     * normal prompt-and-validate fallback. Returns {@code options} unchanged for a non-OpenAI
     * options type, an unusable schema, or an already-set response format. Never mutates the input.
     */
    @SuppressWarnings("unchecked")
    public static <O extends ChatOptions> O withOllamaFormat(
            O options, String schemaName, String jsonSchema) {
        if (!supportsOllamaFormat(options)) {
            return options;
        }
        JsonNode schema = JsonSchemaRules.parseSchema(jsonSchema);
        if (schema == null) {
            return options;
        }
        OpenAiChatOptions openAi = (OpenAiChatOptions) options;
        ResponseFormat existing = openAi.getResponseFormat();
        if (existing != null
                && existing.getType() != null
                && existing.getType() != ResponseFormat.Type.TEXT) {
            return options;
        }
        ResponseFormat.JsonSchema js =
                ResponseFormat.JsonSchema.builder()
                        .name(JsonSchemaRules.sanitizeSchemaName(schemaName))
                        .schema(schema.toString())
                        .strict(false)
                        .build();
        OpenAiChatOptions copy = openAi.copy();
        copy.setResponseFormat(
                ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(js)
                        .build());
        return (O) copy;
    }
}
