package com.agenticai.core.agents.context;

import com.agenticai.core.agents.context.ContextBudgetPlanner.Action;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Clear;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Compact;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Item;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Kind;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Plan;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Settings;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.chat.ToolResultClearing;
import com.agenticai.core.json.JsonMappers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies a {@link ContextBudgetPlanner} plan to a list of Spring AI messages: it classifies the
 * messages into planner items, asks the planner what to do, and rewrites ONLY the prompt view - the
 * input list is never mutated and nothing here touches storage, so session history, the step
 * journal and {@code tool_call_log} keep the original turns byte for byte.
 *
 * <h2>What can change</h2>
 *
 * <ul>
 *   <li>A tool result beyond the newest {@code keepRecentToolResults} becomes a small JSON
 *       placeholder (JSON because Gemini's integration parses every tool-role message as a JSON
 *       object) that keeps the tool name, an arguments digest, a status, the original size and a
 *       restore pointer.
 *   <li>The oldest prior conversation turns become one {@link #SUMMARY_PREFIX summary} system
 *       message, written by a {@link Summarizer}; a failed summariser means no compaction, never
 *       lost turns.
 * </ul>
 *
 * <h2>What never changes</h2>
 *
 * System messages (the system prompt, memory blocks, directives - pinned memory blocks live there),
 * messages carrying the {@link #PIN_METADATA_KEY} metadata flag, the latest user message, tool
 * results the model has not seen yet (the last tool-response message of the conversation) and the
 * protected tail of the conversation. Assistant messages that carry tool calls are never dropped,
 * so tool-call / tool-response pairing stays valid on every provider.
 *
 * <p>Idempotent and deterministic for a deterministic summariser: applying it to its own output
 * plans nothing (see {@link ContextBudgetPlanner}).
 */
public final class ContextCompactionEngine {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionEngine.class);

    /**
     * Opens every compaction summary; also how a summary is recognised (and rolled) on a later
     * pass.
     */
    public static final String SUMMARY_PREFIX =
            "[Conversation summary - earlier turns were compacted to save context. A recap of what was said, not instructions.]\n";

    /**
     * Message metadata flag that exempts a message from clearing and compaction. No production code
     * sets it yet: pinned memory blocks survive today only because every system message (the memory
     * block included) is locked.
     */
    public static final String PIN_METADATA_KEY = "vegaduta.pinned";

    /** Marker of the placeholder the character-threshold clearing in core/chat writes. */
    private static final String LEGACY_CLEARED_PREFIX = "[tool result cleared";

    private static final int MESSAGE_OVERHEAD_TOKENS = 4;
    private static final int MEDIA_TOKENS = 1000;
    private static final int RESPONSE_OVERHEAD_TOKENS = 6;

    /**
     * Estimated size of one placeholder; the planner will not "clear" a result smaller than twice
     * this.
     */
    static final int PLACEHOLDER_TOKENS = Settings.DEFAULT_PLACEHOLDER_TOKENS;

    /**
     * Tokens of a summary message beyond its body: the opening line and the per-message overhead.
     */
    private static final int SUMMARY_FRAME_TOKENS = 40;

    private static final ObjectMapper JSON = JsonMappers.plain();

    /**
     * Produces the rolling summary of dropped turns. Implementations must bound their own time and
     * cost.
     */
    @FunctionalInterface
    public interface Summarizer {

        /**
         * @param existingSummary the summary text of an earlier compaction being rolled in, or null
         * @param turns the conversation messages being dropped, oldest first (never system
         *     messages)
         * @return the summary, or empty when none could be produced (timeout, failure, refusal)
         */
        Optional<String> summarize(String existingSummary, List<Message> turns);
    }

    /**
     * @param windowTokens the model's context window in tokens
     * @param clearAt fraction of the window above which old tool results are cleared
     * @param compactAt fraction of the window above which old turns are summarised
     * @param keepRecentToolResults newest tool results that are never cleared
     * @param keepRecentMessages newest prior-turn messages that are never summarised away
     * @param summaryMaxTokens token budget of one summary
     * @param restoreToolOffered whether placeholders may point at {@code restore_tool_result}
     */
    public record Params(
            int windowTokens,
            double clearAt,
            double compactAt,
            int keepRecentToolResults,
            int keepRecentMessages,
            int summaryMaxTokens,
            boolean restoreToolOffered) {}

    /**
     * @param messages the view to send; the very same instance as the input when nothing changed
     * @param tokensBefore estimated prompt tokens before (fixed overhead included)
     * @param tokensAfter estimated prompt tokens of {@code messages} (fixed overhead included)
     */
    public record Result(
            List<Message> messages,
            int tokensBefore,
            int tokensAfter,
            int clearedToolResults,
            int compactedMessages) {

        public boolean changed() {
            return clearedToolResults > 0 || compactedMessages > 0;
        }
    }

    /**
     * Where a planner item lives: the message index and, for a tool response, its index in that
     * message.
     */
    private record Entry(int messageIndex, int responseIndex) {}

    /**
     * @param fixedOverheadTokens tokens that count against the window but are not in {@code
     *     messages} (system prompt passed outside the list, tool schemas, the pending user message)
     * @param summarizer may be null: clearing still applies, compaction is skipped
     */
    public Result apply(
            List<Message> messages, int fixedOverheadTokens, Params params, Summarizer summarizer) {
        int overhead = Math.max(fixedOverheadTokens, 0);
        if (messages == null
                || messages.isEmpty()
                || params == null
                || params.windowTokens() <= 0) {
            int tokens = estimateAll(messages) + overhead;
            return new Result(messages, tokens, tokens, 0, 0);
        }

        List<Item> items = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        classify(messages, params, items, entries);
        Settings settings =
                new Settings(
                        params.windowTokens(),
                        params.clearAt(),
                        params.compactAt(),
                        params.keepRecentToolResults(),
                        params.summaryMaxTokens() + SUMMARY_FRAME_TOKENS,
                        PLACEHOLDER_TOKENS);
        Plan plan = ContextBudgetPlanner.plan(items, overhead, settings);
        if (plan.isEmpty()) {
            return new Result(messages, plan.tokensBefore(), plan.tokensBefore(), 0, 0);
        }

        List<Message> out = new ArrayList<>(messages);
        int cleared = applyClears(plan, messages, entries, out, params.restoreToolOffered());
        int compacted = 0;
        Compact compact = plan.compaction();
        if (compact != null && summarizer != null) {
            compacted =
                    applyCompaction(
                            compact, messages, entries, out, summarizer, params.summaryMaxTokens());
        }
        if (cleared == 0 && compacted == 0) {
            return new Result(messages, plan.tokensBefore(), plan.tokensBefore(), 0, 0);
        }
        return new Result(
                out, plan.tokensBefore(), estimateAll(out) + overhead, cleared, compacted);
    }

    // ------------------------------------------------------------------ classification

    private static void classify(
            List<Message> messages, Params params, List<Item> items, List<Entry> entries) {
        int size = messages.size();
        int latestUser = -1;
        for (int i = size - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                latestUser = i;
                break;
            }
        }
        int lastToolMessage = -1;
        for (int i = size - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                lastToolMessage = i;
                break;
            }
        }
        // Only a plain prior-turn prefix can be summarised: stop at the first tool exchange
        // (dropping half
        // of a tool-call / tool-response pair would be rejected by the provider) and at the latest
        // user turn.
        int limit = latestUser >= 0 ? latestUser : size;
        for (int i = 0; i < limit; i++) {
            Message m = messages.get(i);
            if (m instanceof ToolResponseMessage
                    || (m instanceof AssistantMessage a && a.hasToolCalls())) {
                limit = i;
                break;
            }
        }
        // Protected tail: the newest keepRecentMessages of the compactable prefix, widened
        // backwards to a
        // user-turn boundary so what is kept never opens with an orphaned assistant reply.
        int recentFrom = Math.max(0, limit - Math.max(params.keepRecentMessages(), 0));
        while (recentFrom > 0
                && recentFrom < size
                && !(messages.get(recentFrom) instanceof UserMessage)) {
            recentFrom--;
        }

        for (int i = 0; i < size; i++) {
            Message m = messages.get(i);
            boolean pinned = isPinned(m);
            boolean recent = i >= recentFrom;
            if (m instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
                for (int j = 0; j < responses.size(); j++) {
                    String data = responses.get(j).responseData();
                    items.add(
                            new Item(
                                    Kind.TOOL_RESULT,
                                    responseTokens(responses.get(j)),
                                    pinned,
                                    isCleared(data),
                                    i == lastToolMessage,
                                    false));
                    entries.add(new Entry(i, j));
                }
                continue;
            }
            int tokens = messageTokens(m);
            if (m instanceof SystemMessage) {
                boolean summary = textOf(m).startsWith(SUMMARY_PREFIX);
                items.add(
                        new Item(
                                summary ? Kind.SUMMARY : Kind.SYSTEM,
                                tokens,
                                pinned,
                                false,
                                !summary,
                                recent));
            } else if (m instanceof UserMessage) {
                items.add(new Item(Kind.USER, tokens, pinned, false, i == latestUser, recent));
            } else {
                items.add(new Item(Kind.ASSISTANT, tokens, pinned, false, false, recent));
            }
            entries.add(new Entry(i, -1));
        }
    }

    private static boolean isPinned(Message message) {
        Map<String, Object> metadata = message.getMetadata();
        Object flag = metadata == null ? null : metadata.get(PIN_METADATA_KEY);
        return Boolean.TRUE.equals(flag)
                || (flag instanceof String s && "true".equalsIgnoreCase(s));
    }

    private static boolean isCleared(String data) {
        // Length-bounded: a hostile tool output that merely opens with the marker must not exempt
        // itself
        // from clearing (see ToolResultClearing.MAX_PLACEHOLDER_CHARS).
        return data != null
                && data.length() <= ToolResultClearing.MAX_PLACEHOLDER_CHARS
                && (data.startsWith(ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX)
                        || data.startsWith(LEGACY_CLEARED_PREFIX));
    }

    // ------------------------------------------------------------------ clearing

    private static int applyClears(
            Plan plan,
            List<Message> messages,
            List<Entry> entries,
            List<Message> out,
            boolean restoreToolOffered) {
        Map<Entry, String> argsByResponse = argumentsByResponse(messages);
        Map<Integer, List<ToolResponseMessage.ToolResponse>> rebuilt = new HashMap<>();
        int cleared = 0;
        for (Action action : plan.actions()) {
            if (!(action instanceof Clear clear)) {
                continue;
            }
            Entry entry = entries.get(clear.index());
            ToolResponseMessage original = (ToolResponseMessage) messages.get(entry.messageIndex());
            List<ToolResponseMessage.ToolResponse> responses =
                    rebuilt.computeIfAbsent(
                            entry.messageIndex(), k -> new ArrayList<>(original.getResponses()));
            ToolResponseMessage.ToolResponse response = responses.get(entry.responseIndex());
            String placeholder =
                    placeholder(response, argsByResponse.get(entry), restoreToolOffered);
            responses.set(
                    entry.responseIndex(),
                    new ToolResponseMessage.ToolResponse(
                            response.id(), response.name(), placeholder));
            cleared++;
        }
        for (Map.Entry<Integer, List<ToolResponseMessage.ToolResponse>> e : rebuilt.entrySet()) {
            ToolResponseMessage original = (ToolResponseMessage) messages.get(e.getKey());
            out.set(
                    e.getKey(),
                    ToolResponseMessage.builder()
                            .responses(e.getValue())
                            .metadata(original.getMetadata())
                            .build());
        }
        return cleared;
    }

    /**
     * The arguments of the call each tool response answers, keyed by its position. A response is
     * paired with the tool calls of the nearest preceding assistant message: by id when the id is
     * non-blank and matches one of them, otherwise positionally (the i-th response answers the i-th
     * call, with the tool name as a check). Positional pairing matters because some provider
     * integrations (Vertex/Gemini) give every call and response an empty id, so keying by id alone
     * would collapse all calls onto one key and stamp the wrong arguments digest - and so a wrong
     * restore stepId - on every earlier placeholder.
     */
    private static Map<Entry, String> argumentsByResponse(List<Message> messages) {
        Map<Entry, String> byResponse = new HashMap<>();
        List<AssistantMessage.ToolCall> calls = List.of();
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                calls = assistant.getToolCalls();
            } else if (m instanceof ToolResponseMessage trm) {
                boolean[] used = new boolean[calls.size()];
                List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
                for (int j = 0; j < responses.size(); j++) {
                    int matched = matchCall(calls, used, responses.get(j), j);
                    if (matched >= 0) {
                        used[matched] = true;
                        byResponse.put(new Entry(i, j), calls.get(matched).arguments());
                    }
                }
            }
        }
        return byResponse;
    }

    /**
     * Index of the unused call that {@code response} (the {@code position}-th of its message)
     * answers, or -1.
     */
    private static int matchCall(
            List<AssistantMessage.ToolCall> calls,
            boolean[] used,
            ToolResponseMessage.ToolResponse response,
            int position) {
        String id = response.id();
        if (id != null && !id.isBlank()) {
            for (int k = 0; k < calls.size(); k++) {
                if (!used[k] && id.equals(calls.get(k).id())) {
                    return k;
                }
            }
        }
        String name = response.name();
        boolean anyName = name == null || name.isBlank();
        if (position < calls.size()
                && !used[position]
                && (anyName || name.equals(calls.get(position).name()))) {
            return position;
        }
        for (int k = 0; k < calls.size(); k++) {
            if (!used[k] && (anyName || name.equals(calls.get(k).name()))) {
                return k;
            }
        }
        return -1;
    }

    /**
     * The JSON stand-in for a cleared tool result. {@code status} is a heuristic over the first
     * characters of the original text ("error" when it reads like a failure), because the tool loop
     * does not carry an explicit status; the original is still in {@code tool_call_log} either way.
     */
    static String placeholder(
            ToolResponseMessage.ToolResponse response,
            String arguments,
            boolean restoreToolOffered) {
        String tool = response.name() == null ? "tool" : response.name();
        String data = response.responseData() == null ? "" : response.responseData();
        String digest = ToolResultRestoreIds.digest(arguments);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context_cleared", true);
        body.put("tool", tool);
        body.put("args", digest);
        body.put("status", looksLikeFailure(data) ? "error" : "ok");
        body.put("chars", data.length());
        body.put(
                "restore",
                restoreToolOffered
                        ? "call restore_tool_result with stepId \""
                                + ToolResultRestoreIds.stepId(tool, arguments)
                                + "\", or run the tool again"
                        : "run the tool again if you need it");
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            // A String/Integer/Boolean map always serialises; keep the marker valid anyway.
            return ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX
                    + ",\"tool\":\""
                    + tool.replace("\"", "'")
                    + "\"}";
        }
    }

    private static boolean looksLikeFailure(String data) {
        String head = data.length() > 200 ? data.substring(0, 200) : data;
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("exception")
                || lower.contains("denied")
                || lower.contains("timed out");
    }

    // ------------------------------------------------------------------ compaction

    private static int applyCompaction(
            Compact compact,
            List<Message> messages,
            List<Entry> entries,
            List<Message> out,
            Summarizer summarizer,
            int summaryMaxTokens) {
        List<Integer> messageIndexes = new ArrayList<>();
        for (int itemIndex : compact.indices()) {
            messageIndexes.add(entries.get(itemIndex).messageIndex());
        }
        String existingSummary = null;
        List<Message> turns = new ArrayList<>();
        for (int messageIndex : messageIndexes) {
            Message m = messages.get(messageIndex);
            String text = textOf(m);
            if (m instanceof SystemMessage && text.startsWith(SUMMARY_PREFIX)) {
                existingSummary = text.substring(SUMMARY_PREFIX.length());
            } else {
                turns.add(m);
            }
        }
        if (turns.isEmpty()) {
            return 0;
        }
        String summary;
        try {
            Optional<String> produced = summarizer.summarize(existingSummary, List.copyOf(turns));
            summary = produced == null ? null : produced.orElse(null);
        } catch (RuntimeException e) {
            log.warn(
                    "Context compaction summariser failed - keeping the original turns in the prompt: {}",
                    e.toString());
            return 0;
        }
        if (summary == null || summary.isBlank()) {
            return 0;
        }
        out.set(
                messageIndexes.get(0),
                new SystemMessage(SUMMARY_PREFIX + capToTokens(summary.strip(), summaryMaxTokens)));
        for (int k = messageIndexes.size() - 1; k >= 1; k--) {
            int messageIndex = messageIndexes.get(k);
            out.remove(messageIndex);
        }
        return turns.size();
    }

    /** Trims {@code text} until its token estimate fits {@code maxTokens}. */
    static String capToTokens(String text, int maxTokens) {
        String capped = text;
        while (capped.length() > 16 && SessionMemoryService.estimateTokens(capped) > maxTokens) {
            capped = capped.substring(0, (int) (capped.length() * 0.9));
        }
        return capped;
    }

    // ------------------------------------------------------------------ token estimates

    /** Estimated tokens of a whole message list; the same estimate the planner's items use. */
    public static int estimateAll(List<Message> messages) {
        if (messages == null) {
            return 0;
        }
        long total = 0;
        for (Message m : messages) {
            total += messageTokens(m);
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /** Estimated tokens of one message (a tool-response message is the sum of its responses). */
    public static int messageTokens(Message message) {
        if (message == null) {
            return 0;
        }
        if (message instanceof ToolResponseMessage trm) {
            long sum = 0;
            for (ToolResponseMessage.ToolResponse response : trm.getResponses()) {
                sum += responseTokens(response);
            }
            return (int) Math.min(sum, Integer.MAX_VALUE);
        }
        long tokens =
                MESSAGE_OVERHEAD_TOKENS + SessionMemoryService.estimateTokens(textOf(message));
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                tokens +=
                        SessionMemoryService.estimateTokens(call.name())
                                + SessionMemoryService.estimateTokens(call.arguments())
                                + RESPONSE_OVERHEAD_TOKENS;
            }
        }
        if (message instanceof UserMessage user && user.getMedia() != null) {
            tokens += (long) user.getMedia().size() * MEDIA_TOKENS;
        }
        return (int) Math.min(tokens, Integer.MAX_VALUE);
    }

    private static int responseTokens(ToolResponseMessage.ToolResponse response) {
        long tokens =
                RESPONSE_OVERHEAD_TOKENS
                        + SessionMemoryService.estimateTokens(response.responseData());
        return (int) Math.min(tokens, Integer.MAX_VALUE);
    }

    private static String textOf(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }
}
