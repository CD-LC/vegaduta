package com.agenticai.core.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Recognises a tool call that a model wrote as <em>text</em> instead of sending it through the
 * provider's structured {@code tool_calls} channel, and turns it into the {@link
 * AssistantMessage.ToolCall}s the normal tool loop would have received.
 *
 * <p>Small and self-hosted models (the free Ollama pool, Groq's 8B tier, OpenRouter {@code :free}
 * rows) routinely do this: the reply is the literal {@code {"name": "generate_document",
 * "parameters": {...}}}, a {@code <tool_call>...</tool_call>} block (Qwen, Hermes), a {@code
 * <function=name>...</function>} block (Llama 3.x), a Mistral {@code [TOOL_CALLS] [...]} prefix, or
 * any of these inside a Markdown code fence. Spring AI only runs calls that arrive structured, so
 * the user saw the raw JSON as the answer (incident: the Documents Agent in the playground on
 * 2026-10-15, "0 tool calls", the reply was exactly that JSON).
 *
 * <p>Deliberately strict so that it never touches an ordinary reply:
 *
 * <ul>
 *   <li>the whole reply must be the call (bare JSON forms), or a short lead-in followed only by
 *       tagged calls ({@code <tool_call>}, {@code <function=}); prose with a JSON example in the
 *       middle is not recovered;
 *   <li>every call must name a tool that was actually offered on this request; one unknown name
 *       rejects the whole reply, since explaining a JSON shape is a legitimate answer;
 *   <li>arguments must be a JSON object (a JSON-encoded string of one is unwrapped).
 * </ul>
 *
 * {@link #couldBeToolCall} is the streaming half: whether the text seen so far is still a prefix of
 * one of those shapes, so a stream can be held back only while that is true. A reply whose first
 * character is a letter passes through on the first token.
 */
final class TextToolCallParser {

    /** Lead-in text a model may put before a tagged call ("Let me generate that.\n<tool_call>"). */
    static final int MAX_LEAD_IN_CHARS = 400;

    private static final String TOOL_CALL_OPEN = "<tool_call>";
    private static final String TOOL_CALL_CLOSE = "</tool_call>";
    private static final String FUNCTION_OPEN = "<function=";
    private static final String PYTHON_TAG = "<|python_tag|>";
    private static final String MISTRAL_PREFIX = "[TOOL_CALLS]";
    private static final String FENCE = "```";

    private static final Pattern TOOL_CALL_BLOCK =
            Pattern.compile("<tool_call>\\s*(.*?)\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern FUNCTION_BLOCK =
            Pattern.compile(
                    "<function=([A-Za-z0-9_.:-]+)>\\s*(.*?)\\s*</function>", Pattern.DOTALL);

    private static final Pattern PARAMETER_BLOCK =
            Pattern.compile(
                    "<parameter=([A-Za-z0-9_.:-]+)>\\s*?\\n?(.*?)\\n?\\s*</parameter>",
                    Pattern.DOTALL);

    private static final List<String> NAME_KEYS =
            List.of("name", "tool", "tool_name", "function_name");
    private static final List<String> ARGS_KEYS =
            List.of("parameters", "arguments", "args", "input", "params", "tool_input");
    private static final Set<String> STRUCTURAL_KEYS =
            Set.of(
                    "name",
                    "tool",
                    "tool_name",
                    "function_name",
                    "function",
                    "type",
                    "id",
                    "call_id");

    private TextToolCallParser() {}

    // --- streaming prefix check -------------------------------------------

    /**
     * True while {@code textSoFar} could still turn out to be a text-form tool call: empty or
     * whitespace, or a prefix of one of the recognised shapes. False as soon as the first
     * non-whitespace characters rule every shape out, which for a prose reply is the first token.
     */
    static boolean couldBeToolCall(CharSequence textSoFar) {
        String s = stripLeading(textSoFar.toString());
        if (s.isEmpty()) {
            return true;
        }
        return switch (s.charAt(0)) {
            case '{' -> true;
            case '[' -> prefixOrStartsWith(s, MISTRAL_PREFIX) || nextNonWhitespaceIs(s, 1, '{');
            case '`' -> prefixOrStartsWith(s, FENCE) && fenceBodyCouldBeToolCall(s);
            case '<' ->
                    prefixOrStartsWith(s, TOOL_CALL_OPEN)
                            || prefixOrStartsWith(s, FUNCTION_OPEN)
                            || prefixOrStartsWith(s, PYTHON_TAG);
            default -> leadInCouldPrecedeTag(s);
        };
    }

    private static boolean fenceBodyCouldBeToolCall(String s) {
        if (s.length() <= FENCE.length()) {
            return true;
        }
        int newline = s.indexOf('\n', FENCE.length());
        if (newline < 0) {
            // Still on the "```json" line: only an info-string so far.
            String infoString = s.substring(FENCE.length()).trim();
            return infoString.isEmpty() || infoString.chars().allMatch(Character::isLetterOrDigit);
        }
        String body = stripLeading(s.substring(newline + 1));
        return body.isEmpty()
                || body.charAt(0) == '{'
                || body.charAt(0) == '['
                || body.charAt(0) == '<';
    }

    /**
     * A reply that starts with prose can still be "short lead-in + tagged call" ({@link
     * #MAX_LEAD_IN_CHARS}), so hold back while the text is shorter than that and has no tag yet;
     * the moment a tag appears the normal prefix logic applies to what follows it.
     */
    private static boolean leadInCouldPrecedeTag(String s) {
        int tag = indexOfFirstTag(s);
        if (tag >= 0) {
            return tag <= MAX_LEAD_IN_CHARS;
        }
        // No tag yet: only worth waiting if the text is short enough that one could still
        // follow inside the lead-in budget, and it does not already read as a full sentence
        // of prose (a newline-free, mid-sentence fragment is the ambiguous case).
        return s.length() <= 24 && s.indexOf('\n') < 0;
    }

    private static int indexOfFirstTag(String s) {
        int a = s.indexOf(TOOL_CALL_OPEN);
        int b = s.indexOf(FUNCTION_OPEN);
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }

    private static boolean prefixOrStartsWith(String s, String marker) {
        return s.length() < marker.length() ? marker.startsWith(s) : s.startsWith(marker);
    }

    private static boolean nextNonWhitespaceIs(String s, int from, char expected) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == expected;
            }
        }
        return true;
    }

    // --- full-text parse ----------------------------------------------------

    /**
     * The tool calls encoded in {@code text}, or an empty list when it is not (entirely) a
     * text-form tool call to tools in {@code knownTools}. Never throws.
     */
    static List<AssistantMessage.ToolCall> parse(
            String text, Set<String> knownTools, ObjectMapper objectMapper) {
        if (text == null || knownTools == null || knownTools.isEmpty()) {
            return List.of();
        }
        try {
            List<AssistantMessage.ToolCall> calls = parseUnchecked(text, objectMapper);
            for (AssistantMessage.ToolCall call : calls) {
                if (!knownTools.contains(call.name())) {
                    return List.of();
                }
            }
            return calls;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<AssistantMessage.ToolCall> parseUnchecked(
            String text, ObjectMapper objectMapper) {
        String s = text.strip();
        s = stripPrefix(s, PYTHON_TAG);
        s = stripPrefix(s, MISTRAL_PREFIX);
        s = unfence(s);

        if (s.contains(FUNCTION_OPEN)) {
            // Qwen wraps <function=...> blocks in <tool_call> ... </tool_call> (and sometimes
            // leaves a
            // stray closing tag): the wrapper carries no information, the function blocks do.
            return taggedCalls(
                    s.replace(TOOL_CALL_OPEN, "").replace(TOOL_CALL_CLOSE, "").strip(),
                    FUNCTION_BLOCK,
                    true,
                    objectMapper);
        }
        if (s.contains(TOOL_CALL_OPEN)) {
            return taggedCalls(s, TOOL_CALL_BLOCK, false, objectMapper);
        }
        JsonNode root = readJson(s, objectMapper);
        if (root == null) {
            return List.of();
        }
        return callsFrom(root, objectMapper);
    }

    /**
     * Every {@code <tool_call>}/{@code <function=...>} block in order. Text before the first block
     * is allowed up to {@link #MAX_LEAD_IN_CHARS}; anything but whitespace between or after the
     * blocks means the reply was prose that happened to contain one, and nothing is recovered.
     */
    private static List<AssistantMessage.ToolCall> taggedCalls(
            String s, Pattern block, boolean nameInTag, ObjectMapper objectMapper) {
        Matcher matcher = block.matcher(s);
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        int consumedTo = -1;
        while (matcher.find()) {
            if (consumedTo < 0) {
                if (matcher.start() > MAX_LEAD_IN_CHARS) {
                    return List.of();
                }
            } else if (!s.substring(consumedTo, matcher.start()).isBlank()) {
                return List.of();
            }
            consumedTo = matcher.end();
            if (nameInTag) {
                JsonNode args = readJson(unfence(matcher.group(2)), objectMapper);
                if (args == null) {
                    args = parameterTags(matcher.group(2), objectMapper);
                }
                if (args == null || !args.isObject()) {
                    return List.of();
                }
                calls.add(toolCall(matcher.group(1), args, calls.size(), objectMapper));
            } else {
                JsonNode body = readJson(unfence(matcher.group(1)), objectMapper);
                if (body == null) {
                    return List.of();
                }
                List<AssistantMessage.ToolCall> inner = callsFrom(body, objectMapper);
                if (inner.isEmpty()) {
                    return List.of();
                }
                calls.addAll(inner);
            }
        }
        if (consumedTo < 0 || !s.substring(consumedTo).isBlank()) {
            return List.of();
        }
        return calls;
    }

    /**
     * Arguments written as {@code <parameter=path>value</parameter>} blocks (the Qwen coder
     * format); {@code null} when the body is anything else. Values are kept as the text between the
     * tags, one leading and one trailing line break removed.
     */
    private static JsonNode parameterTags(String body, ObjectMapper objectMapper) {
        if (body == null || !body.contains("<parameter=")) {
            return null;
        }
        Matcher m = PARAMETER_BLOCK.matcher(body);
        ObjectNode args = objectMapper.createObjectNode();
        int end = 0;
        while (m.find()) {
            if (!body.substring(end, m.start()).isBlank()) {
                return null;
            }
            args.put(m.group(1), m.group(2));
            end = m.end();
        }
        return args.isEmpty() || !body.substring(end).isBlank() ? null : args;
    }

    /** One object, an array of objects, or {@code {"tool_calls": [...]}}. */
    private static List<AssistantMessage.ToolCall> callsFrom(
            JsonNode root, ObjectMapper objectMapper) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode element : root) {
                AssistantMessage.ToolCall call = singleCall(element, calls.size(), objectMapper);
                if (call == null) {
                    return List.of();
                }
                calls.add(call);
            }
            return calls;
        }
        if (root.isObject()) {
            JsonNode nested = root.get("tool_calls");
            if (nested != null && nested.isArray() && root.size() == 1) {
                return callsFrom(nested, objectMapper);
            }
            AssistantMessage.ToolCall call = singleCall(root, 0, objectMapper);
            return call == null ? List.of() : List.of(call);
        }
        return List.of();
    }

    /** {@code null} when {@code node} does not carry a tool name and object arguments. */
    private static AssistantMessage.ToolCall singleCall(
            JsonNode node, int index, ObjectMapper objectMapper) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode function = node.get("function");
        String name = textOfFirst(node, NAME_KEYS);
        if (name == null && function != null) {
            name = function.isTextual() ? function.asText() : textOfFirst(function, NAME_KEYS);
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        JsonNode args = firstPresent(node, ARGS_KEYS);
        if (args == null && function != null && function.isObject()) {
            args = firstPresent(function, ARGS_KEYS);
        }
        if (args == null) {
            // {"name": "search", "query": "..."}: the remaining fields are the arguments.
            ObjectNode rest = objectMapper.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                if (!STRUCTURAL_KEYS.contains(field.getKey())) {
                    rest.set(field.getKey(), field.getValue());
                }
            }
            args = rest;
        } else if (args.isTextual()) {
            JsonNode unwrapped = readJson(args.asText(), objectMapper);
            if (unwrapped == null || !unwrapped.isObject()) {
                return null;
            }
            args = unwrapped;
        }
        if (!args.isObject()) {
            return null;
        }
        return toolCall(name.trim(), args, index, objectMapper);
    }

    private static AssistantMessage.ToolCall toolCall(
            String name, JsonNode args, int index, ObjectMapper objectMapper) {
        String arguments;
        try {
            arguments = objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            arguments = "{}";
        }
        String id = "textcall_" + index + "_" + UUID.randomUUID().toString().substring(0, 8);
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Whole-input parse: Jackson's {@code readTree} stops after the first value by default, which
     * would make "a JSON example followed by prose" look like a bare tool call.
     */
    private static JsonNode readJson(String s, ObjectMapper objectMapper) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return objectMapper
                    .reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOfFirst(JsonNode node, List<String> keys) {
        JsonNode value = firstPresent(node, keys);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static JsonNode firstPresent(JsonNode node, List<String> keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    /** Drops one surrounding Markdown code fence, with or without an info string. */
    static String unfence(String s) {
        String t = s.strip();
        if (!t.startsWith(FENCE)) {
            return t;
        }
        int newline = t.indexOf('\n');
        if (newline < 0) {
            return t;
        }
        String infoString = t.substring(FENCE.length(), newline).trim().toLowerCase(Locale.ROOT);
        if (!infoString.isEmpty() && !infoString.chars().allMatch(Character::isLetterOrDigit)) {
            return t;
        }
        String body = t.substring(newline + 1);
        if (body.stripTrailing().endsWith(FENCE)) {
            body = body.stripTrailing();
            body = body.substring(0, body.length() - FENCE.length());
        }
        return body.strip();
    }

    private static String stripPrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()).strip() : s;
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }
}
