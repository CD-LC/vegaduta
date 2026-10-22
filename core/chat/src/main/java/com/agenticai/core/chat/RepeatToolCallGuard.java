package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stops a model from asking for the same lookup twice in one reply.
 *
 * <p>Seen on qwen3.5:4b: after a {@code web_search} result came back, the model issued the
 * byte-identical query again before it would answer (once, in the benchmark, then answered on the
 * third call); the only bound on that was the 25-round cap, minutes of laptop time. A repeated
 * lookup returns what it already returned, so running it again costs time and tokens and teaches
 * the model nothing.
 *
 * <p>Applies only to the tools named in {@code app.chat.tools.repeat-guard.tools} (default: the
 * idempotent retrieval tools). Tools that read state that changes (the browser, the terminal, the
 * workspace, job status) are left alone: asking twice there is legitimate. Counted per model
 * invocation, statelessly from the prompt, like the loop cap in {@link
 * ArgumentSanitizingToolCallingManager}.
 *
 * <ol>
 *   <li>A round whose every call repeats an earlier call of this invocation (same tool, same
 *       arguments up to key order and whitespace) is answered, not executed, with a notice that
 *       tells the model to use the result it already has.
 *   <li>If the model repeats again after that notice, the loop ends ({@code returnDirect}) with a
 *       short fixed reply; neither stage throws, so a stream whose headers are flushed still ends
 *       cleanly.
 * </ol>
 *
 * A round that mixes new and repeated calls runs normally: refusing part of a round would need
 * per-call plumbing for a shape that is rare.
 */
final class RepeatToolCallGuard {

    /** Writes the arguments with object keys ordered, so key order never hides a repeat. */
    private static final ObjectMapper CANONICAL = JsonMappers.sortedMapKeys();

    /** Default of {@code app.chat.tools.repeat-guard.tools}. */
    static final String DEFAULT_TOOLS =
            "web_search,fetch_web_page,search_knowledge_base,list_knowledge_collections,"
                + "search_conversation_history,recall_related_facts,vegaduta_api_guide,vegaduta_setup_guide";

    static final String DUPLICATE_MARKER = "duplicate_tool_call";
    static final String METRIC = "chat.tool.repeat_blocked";

    static final String STOP_REPLY =
            "I stopped here: I kept asking for the same lookup. Please rephrase or narrow the"
                + " request, or ask me to continue.";

    private static final RepeatToolCallGuard DISABLED = new RepeatToolCallGuard(Set.of(), false);

    private final Set<String> tools;
    private final boolean all;

    private RepeatToolCallGuard(Set<String> tools, boolean all) {
        this.tools = tools;
        this.all = all;
    }

    static RepeatToolCallGuard disabled() {
        return DISABLED;
    }

    /**
     * {@code names}: comma-separated tool names, {@code *} for every tool, blank to turn the guard
     * off.
     */
    static RepeatToolCallGuard of(String names) {
        if (names == null || names.isBlank()) {
            return DISABLED;
        }
        Set<String> set = new HashSet<>();
        boolean all = false;
        for (String name : names.split(",")) {
            String clean = name.trim().toLowerCase(Locale.ROOT);
            if (clean.equals("*")) {
                all = true;
            } else if (!clean.isEmpty()) {
                set.add(clean);
            }
        }
        return set.isEmpty() && !all ? DISABLED : new RepeatToolCallGuard(Set.copyOf(set), all);
    }

    boolean enabled() {
        return all || !tools.isEmpty();
    }

    /**
     * The result to hand back instead of executing this round, or {@code null} when the round
     * should run.
     *
     * @param onBlocked called with the tool name of each call that was refused (metrics)
     */
    ToolExecutionResult answerRepeats(
            Prompt prompt,
            ChatResponse response,
            ObjectMapper mapper,
            java.util.function.Consumer<String> onBlocked) {
        if (!enabled()) {
            return null;
        }
        AssistantMessage requesting = firstToolCallingOutput(response);
        if (requesting == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        boolean noticeAlreadySent = false;
        for (Message message : currentInvocation(prompt.getInstructions())) {
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    seen.add(key(call, mapper));
                }
            } else if (message instanceof ToolResponseMessage responses
                    && carriesNotice(responses)) {
                noticeAlreadySent = true;
            }
        }
        List<AssistantMessage.ToolCall> calls = requesting.getToolCalls();
        for (AssistantMessage.ToolCall call : calls) {
            if (!guarded(call.name()) || !seen.contains(key(call, mapper))) {
                return null;
            }
        }
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(requesting);
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall call = calls.get(i);
            onBlocked.accept(call.name());
            String data =
                    noticeAlreadySent ? (i == 0 ? STOP_REPLY : "") : notice(call.name(), mapper);
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), data));
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(noticeAlreadySent)
                .build();
    }

    private boolean guarded(String name) {
        return name != null && (all || tools.contains(name.trim().toLowerCase(Locale.ROOT)));
    }

    private static String notice(String tool, ObjectMapper mapper) {
        String text =
                "You already called "
                        + tool
                        + " with exactly these arguments in this reply, so it was NOT run again:"
                        + " its result is above. Use that result and answer the user now. If it did"
                        + " not help, call it with a different query, or answer with what you have"
                        + " and say what is missing.";
        try {
            return mapper.writeValueAsString(Map.of("error", DUPLICATE_MARKER, "result", text));
        } catch (Exception e) {
            return "{\"error\":\""
                    + DUPLICATE_MARKER
                    + "\",\"result\":\"Use the earlier result and answer now.\"}";
        }
    }

    private static boolean carriesNotice(ToolResponseMessage message) {
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            String data = response.responseData();
            if (data != null && data.contains("\"" + DUPLICATE_MARKER + "\"")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tool name plus the arguments with object keys ordered and whitespace dropped; raw text if not
     * JSON.
     */
    private static String key(AssistantMessage.ToolCall call, ObjectMapper mapper) {
        String arguments = call.arguments() == null ? "" : call.arguments().trim();
        String canonical = arguments;
        try {
            JsonNode tree = mapper.readTree(arguments.isEmpty() ? "{}" : arguments);
            canonical = CANONICAL.writeValueAsString(mapper.convertValue(tree, Object.class));
        } catch (Exception ignored) {
            // not JSON: compare the text as it came
        }
        return (call.name() == null ? "" : call.name()) + "\u0000" + canonical;
    }

    /** Messages of THIS model invocation: everything after the last user message. */
    private static List<Message> currentInvocation(List<Message> instructions) {
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage) {
                return instructions.subList(i + 1, instructions.size());
            }
        }
        return instructions;
    }

    private static AssistantMessage firstToolCallingOutput(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return null;
        }
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation == null ? null : generation.getOutput();
            if (output != null && output.hasToolCalls()) {
                return output;
            }
        }
        return null;
    }
}
