package com.agenticai.core.agents.openai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates an Open Responses request and folds it into the {@link OpenAiChatRequest} the shared
 * prompt builder ({@link OpenAiChatRequest#toPrompt}) already understands, so {@code /v1/responses}
 * and {@code /v1/chat/completions} build the agent prompt the same way (system/developer turns and
 * earlier turns become a transcript, or only the last user message when the caller holds a
 * server-side session).
 *
 * <h2>Policy: nothing is silently pretended</h2>
 *
 * <ul>
 *   <li><b>Accepted</b>: {@code model}, {@code input} (a string, or an array of message items with
 *       {@code role} + string or {@code input_text}/{@code output_text} parts), {@code
 *       instructions}, {@code stream}, {@code metadata} (echoed back).
 *   <li><b>Accepted but not applied</b> (the agent owns its sampling, as on chat completions):
 *       {@code temperature}, {@code max_output_tokens}. They are range-checked and the response
 *       reports them as {@code null}, never as if they had been applied.
 *   <li><b>Rejected with 400 {@code unsupported_parameter}</b>: {@code previous_response_id}
 *       (responses are not stored: send the {@code X-Session-Id} header to continue a server-held
 *       session), {@code conversation}, {@code background=true}, a non-empty {@code tools} array or
 *       a forcing {@code tool_choice} (no request-side tool pass-through: the agent runs its own
 *       configured tools), and any input item or content part that is not text (images, files,
 *       function calls and their outputs, reasoning items, item references).
 *   <li>Everything else in the body is ignored, like on the chat endpoint.
 * </ul>
 *
 * All failures are {@link OpenAiCompatException}s with client-safe messages.
 */
final class OpenResponsesInputMapper {

    static final String UNSUPPORTED = "unsupported_parameter";
    static final String INVALID = "invalid_request";
    private static final int MAX_METADATA_ENTRIES = 16;
    private static final Set<String> ROLES = Set.of("user", "assistant", "system", "developer");
    private static final Set<String> TEXT_PART_TYPES = Set.of("input_text", "output_text");

    private OpenResponsesInputMapper() {}

    /**
     * @throws OpenAiCompatException 400 for any unsupported or malformed field
     */
    static OpenAiChatRequest toChatRequest(OpenResponsesRequest r) {
        rejectUnsupported(r);
        validateSampling(r);
        validateMetadata(r.metadata());

        List<OpenAiChatRequest.Message> messages = new ArrayList<>();
        String instructions = instructionsText(r.instructions());
        if (instructions != null) {
            messages.add(message("system", instructions));
        }
        JsonNode input = r.input();
        if (isAbsent(input)) {
            throw bad(INVALID, "'input' is required: a string or an array of message items");
        }
        if (input.isString()) {
            messages.add(message("user", input.asString()));
        } else if (input.isArray()) {
            int index = 0;
            for (JsonNode item : input) {
                messages.add(toMessage(item, index++));
            }
        } else {
            throw bad(INVALID, "'input' must be a string or an array of message items");
        }
        boolean hasUserText = false;
        for (OpenAiChatRequest.Message m : messages) {
            if ("user".equals(m.role()) && !m.text().isBlank()) {
                hasUserText = true;
                break;
            }
        }
        if (!hasUserText) {
            throw bad(INVALID, "'input' must contain at least one user message with text content");
        }
        return new OpenAiChatRequest(r.model(), messages, r.stream(), null);
    }

    /** The {@code instructions} string, or null when absent/blank. */
    static String instructionsText(JsonNode instructions) {
        if (isAbsent(instructions)) {
            return null;
        }
        if (!instructions.isString()) {
            throw bad(INVALID, "'instructions' must be a string");
        }
        return instructions.asString().isBlank() ? null : instructions.asString();
    }

    private static void rejectUnsupported(OpenResponsesRequest r) {
        if (r.previousResponseId() != null && !r.previousResponseId().isBlank()) {
            throw bad(
                    UNSUPPORTED,
                    "'previous_response_id' is not supported: responses are not stored. Send an"
                        + " X-Session-Id header to continue a server-held conversation, or resend"
                        + " the earlier turns in 'input'.");
        }
        if (!isAbsent(r.conversation())) {
            throw bad(
                    UNSUPPORTED,
                    "'conversation' is not supported. Send an X-Session-Id header to continue "
                            + "a server-held conversation.");
        }
        if (Boolean.TRUE.equals(r.background())) {
            throw bad(
                    UNSUPPORTED,
                    "'background' is not supported: use stream=true for long-running turns.");
        }
        JsonNode tools = r.tools();
        if (!isAbsent(tools) && (!tools.isArray() || tools.size() > 0)) {
            throw bad(
                    UNSUPPORTED,
                    "'tools' is not supported: the agent runs its own configured tools, "
                            + "request-side tools are not passed through.");
        }
        JsonNode choice = r.toolChoice();
        if (!isAbsent(choice)
                && !(choice.isString()
                        && ("auto".equals(choice.asString())
                                || "none".equals(choice.asString())))) {
            throw bad(
                    UNSUPPORTED,
                    "'tool_choice' is not supported: only 'auto' and 'none' are accepted.");
        }
    }

    private static void validateSampling(OpenResponsesRequest r) {
        Double t = r.temperature();
        if (t != null && (t.isNaN() || t < 0.0 || t > 2.0)) {
            throw bad(INVALID, "'temperature' must be between 0 and 2");
        }
        Integer max = r.maxOutputTokens();
        if (max != null && max < 1) {
            throw bad(INVALID, "'max_output_tokens' must be at least 1");
        }
    }

    private static void validateMetadata(JsonNode metadata) {
        if (isAbsent(metadata)) {
            return;
        }
        if (!metadata.isObject()) {
            throw bad(INVALID, "'metadata' must be an object of string values");
        }
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw bad(INVALID, "'metadata' can have at most " + MAX_METADATA_ENTRIES + " entries");
        }
        Iterator<Map.Entry<String, JsonNode>> it = metadata.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isString()) {
                throw bad(INVALID, "'metadata' values must be strings");
            }
        }
    }

    private static OpenAiChatRequest.Message toMessage(JsonNode item, int index) {
        String at = "'input[" + index + "]'";
        if (item == null || !item.isObject()) {
            throw bad(INVALID, at + " must be an object");
        }
        JsonNode type = item.get("type");
        if (!isAbsent(type) && !(type.isString() && "message".equals(type.asString()))) {
            throw bad(
                    UNSUPPORTED,
                    at
                            + " has unsupported item type '"
                            + safe(type)
                            + "': only message items "
                            + "are supported");
        }
        JsonNode role = item.get("role");
        if (isAbsent(role) || !role.isString() || !ROLES.contains(role.asString())) {
            throw bad(INVALID, at + " needs a 'role' of user, assistant, system or developer");
        }
        return message(role.asString(), contentText(item.get("content"), at));
    }

    private static String contentText(JsonNode content, String at) {
        if (isAbsent(content)) {
            return "";
        }
        if (content.isString()) {
            return content.asString();
        }
        if (!content.isArray()) {
            throw bad(INVALID, at + " 'content' must be a string or an array of content parts");
        }
        StringBuilder joined = new StringBuilder();
        for (JsonNode part : content) {
            JsonNode type = part == null ? null : part.get("type");
            if (isAbsent(type) || !type.isString() || !TEXT_PART_TYPES.contains(type.asString())) {
                throw bad(
                        UNSUPPORTED,
                        at
                                + " has unsupported content part type '"
                                + safe(type)
                                + "': only input_text is supported");
            }
            JsonNode text = part.get("text");
            if (isAbsent(text) || !text.isString()) {
                throw bad(
                        INVALID,
                        at
                                + " content part of type '"
                                + type.asString()
                                + "' needs a string 'text'");
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(text.asString());
        }
        return joined.toString();
    }

    private static OpenAiChatRequest.Message message(String role, String text) {
        return new OpenAiChatRequest.Message(role, JsonNodeFactory.instance.textNode(text), null);
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    /** A type name for an error message, capped and stripped of anything but a plain identifier. */
    private static String safe(JsonNode type) {
        if (isAbsent(type) || !type.isString()) {
            return "unknown";
        }
        String s = type.asString();
        s = s.length() > 40 ? s.substring(0, 40) : s;
        return s.replaceAll("[^A-Za-z0-9_.:-]", "?");
    }

    private static OpenAiCompatException bad(String code, String message) {
        return new OpenAiCompatException(400, "invalid_request_error", code, message);
    }
}
