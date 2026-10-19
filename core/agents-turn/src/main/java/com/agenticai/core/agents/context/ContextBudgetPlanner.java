package com.agenticai.core.agents.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, deterministic planner for context-window budgeting: given the items that make up a prompt (with
 * token estimates) and the model's window, it returns the ordered list of actions - clear old tool
 * results, then compact the oldest conversation - that brings the prompt back under budget. No I/O, no
 * clocks, no Spring types: the same input always yields the same plan, which is what makes the
 * "running it twice yields the same view" guarantee testable.
 *
 * <p>Design lineage (ideas only, no code reused): Anthropic's context editing (clear old tool results
 * past a trigger, keep the most recent N), Claude Code's auto-compact (summarise the oldest part of the
 * conversation when the window is nearly full), Manus' restorable compression (what is dropped can be
 * fetched again) and LangGraph's summarisation middleware (summary replaces a prefix of the messages).
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li><b>Trigger.</b> Nothing happens while {@code total <= clearAt * window}. {@code total} is the sum
 *       of the item tokens plus the caller's fixed overhead (system prompt, tool schemas) that no action
 *       can shrink.</li>
 *   <li><b>Clear.</b> Above that, every tool result that is not locked, not pinned, not already cleared
 *       and not among the most recent {@code keepRecentToolResults} tool results becomes a
 *       {@link Clear}. All eligible results are cleared in one go, not one at a time until the number
 *       dips under the threshold: a stable prefix keeps the provider's prompt cache warm, a prompt that
 *       is edited a little every round does not.</li>
 *   <li><b>Compact.</b> If the total, after clearing, is still above {@code compactAt * window}, the
 *       oldest compactable conversation items are replaced by ONE summary: a {@link Compact}. It drops
 *       the shortest oldest run that brings the total back to {@code clearAt * window} (or the longest
 *       run available when nothing suffices), and only a run that ends right before a user turn (or
 *       before the protected tail) - so what remains never opens with an orphaned assistant reply.
 *       Pinned and locked items are never dropped.</li>
 * </ol>
 *
 * <p>Idempotence: a cleared result is flagged {@link Item#cleared()} by the caller and is never
 * re-cleared; a plan that reached its target leaves nothing above the trigger; a compaction that could
 * not reach the target leaves only the summary (plus protected items) compactable, and dropping a
 * summary to write a same-sized one is never worthwhile ({@link Compact#tokensRemoved()} must exceed the
 * summary budget), so a second pass over the result plans nothing.
 */
public final class ContextBudgetPlanner {

    private ContextBudgetPlanner() {
    }

    /** What a prompt item is, as far as budgeting is concerned. */
    public enum Kind {
        /** System prompt, memory block, any directive: never touched. */
        SYSTEM,
        /** A user turn. */
        USER,
        /** An assistant turn (text, or the message that carries tool calls). */
        ASSISTANT,
        /** One tool response. */
        TOOL_RESULT,
        /** An earlier compaction summary: compactable, and rolled into the next one. */
        SUMMARY
    }

    /**
     * One budgeted prompt item.
     *
     * @param tokens estimated tokens
     * @param pinned user-marked: never cleared, never dropped
     * @param cleared a tool result that is already a placeholder
     * @param locked never cleared or dropped: the system prompt, the latest user message, and tool
     *     results the model has not seen yet
     * @param recent kept out of compaction (the protected tail of the conversation) but still
     *     clearable when it is a tool result
     */
    public record Item(Kind kind, int tokens, boolean pinned, boolean cleared, boolean locked, boolean recent) {

        public Item {
            tokens = Math.max(tokens, 0);
        }

        /** A plain item: not pinned, not cleared, not locked, not in the protected tail. */
        public static Item of(Kind kind, int tokens) {
            return new Item(kind, tokens, false, false, false, false);
        }

        public Item withPinned(boolean value) {
            return new Item(kind, tokens, value, cleared, locked, recent);
        }

        public Item withCleared(boolean value) {
            return new Item(kind, tokens, pinned, value, locked, recent);
        }

        public Item withLocked(boolean value) {
            return new Item(kind, tokens, pinned, cleared, value, recent);
        }

        public Item withRecent(boolean value) {
            return new Item(kind, tokens, pinned, cleared, locked, value);
        }
    }

    /**
     * @param windowTokens the model's context window; {@code <= 0} disables planning
     * @param clearAt fraction of the window above which old tool results are cleared
     * @param compactAt fraction of the window above which the oldest conversation is compacted
     * @param keepRecentToolResults the newest tool results that are never cleared
     * @param summaryTokens token budget one compaction summary may use
     * @param placeholderTokens estimated tokens of one cleared-result placeholder
     */
    public record Settings(
        int windowTokens, double clearAt, double compactAt, int keepRecentToolResults, int summaryTokens,
        int placeholderTokens
    ) {

        public static final double DEFAULT_CLEAR_AT = 0.6;
        public static final double DEFAULT_COMPACT_AT = 0.85;
        public static final int DEFAULT_KEEP_RECENT_TOOL_RESULTS = 3;
        public static final int DEFAULT_SUMMARY_TOKENS = 800;
        public static final int DEFAULT_PLACEHOLDER_TOKENS = 48;

        public Settings {
            windowTokens = Math.max(windowTokens, 0);
            clearAt = clamp(clearAt, 0.05, 1.0, DEFAULT_CLEAR_AT);
            compactAt = Math.max(clamp(compactAt, 0.05, 1.0, DEFAULT_COMPACT_AT), clearAt);
            keepRecentToolResults = Math.max(keepRecentToolResults, 0);
            summaryTokens = Math.max(summaryTokens, 1);
            placeholderTokens = Math.max(placeholderTokens, 1);
        }

        private static double clamp(double value, double min, double max, double fallback) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return fallback;
            }
            return Math.min(Math.max(value, min), max);
        }
    }

    /** One planned step; the plan lists them in the order they must be applied. */
    public sealed interface Action permits Clear, Compact {
    }

    /** Replace the tool result at {@code index} (an index into the planner's item list) with a placeholder. */
    public record Clear(int index, int tokensSaved) implements Action {
    }

    /**
     * Replace the items at {@code indices} (ascending, into the planner's item list) with ONE summary
     * placed where the first of them stood.
     *
     * @param tokensRemoved tokens of the dropped items
     * @param summaryTokens the budget the replacement summary is allowed
     */
    public record Compact(List<Integer> indices, int tokensRemoved, int summaryTokens) implements Action {
        public Compact {
            indices = List.copyOf(indices);
        }
    }

    /**
     * @param tokensBefore estimated prompt tokens before any action
     * @param tokensAfter estimated prompt tokens once every action is applied (placeholders and the
     *     summary are estimated at their budgets)
     */
    public record Plan(int tokensBefore, int tokensAfter, List<Action> actions) {

        public Plan {
            actions = Collections.unmodifiableList(new ArrayList<>(actions));
        }

        public boolean isEmpty() {
            return actions.isEmpty();
        }

        public int clearCount() {
            int n = 0;
            for (Action action : actions) {
                if (action instanceof Clear) {
                    n++;
                }
            }
            return n;
        }

        /** The compaction step, or {@code null} when the plan has none. */
        public Compact compaction() {
            for (Action action : actions) {
                if (action instanceof Compact compact) {
                    return compact;
                }
            }
            return null;
        }
    }

    /**
     * Plans the actions for {@code items}.
     *
     * @param fixedOverheadTokens tokens that count against the window but are not in {@code items}
     *     (system prompt text passed outside the list, tool schemas, the pending user message)
     */
    public static Plan plan(List<Item> items, int fixedOverheadTokens, Settings settings) {
        long itemTokens = 0;
        for (Item item : items) {
            itemTokens += item.tokens();
        }
        int before = saturate(itemTokens + Math.max(fixedOverheadTokens, 0));
        if (items.isEmpty() || settings.windowTokens() <= 0) {
            return new Plan(before, before, List.of());
        }
        long clearLimit = limit(settings.clearAt(), settings.windowTokens());
        if (before <= clearLimit) {
            return new Plan(before, before, List.of());
        }

        List<Action> actions = new ArrayList<>();
        long total = before;

        // Phase 1 - clear old tool results, keeping the newest N (cleared ones count towards N).
        List<Integer> toolResults = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind() == Kind.TOOL_RESULT) {
                toolResults.add(i);
            }
        }
        int clearable = Math.max(0, toolResults.size() - settings.keepRecentToolResults());
        for (int p = 0; p < clearable; p++) {
            int index = toolResults.get(p);
            Item item = items.get(index);
            if (item.locked() || item.pinned() || item.cleared()) {
                continue;
            }
            int saved = item.tokens() - settings.placeholderTokens();
            if (saved < settings.placeholderTokens()) {
                continue; // tiny result: the placeholder would save next to nothing
            }
            actions.add(new Clear(index, saved));
            total -= saved;
        }

        // Phase 2 - compact the oldest conversation when clearing was not enough.
        if (total > limit(settings.compactAt(), settings.windowTokens())) {
            Compact compact = planCompaction(items, total, clearLimit, settings);
            if (compact != null) {
                actions.add(compact);
                total = total - compact.tokensRemoved() + compact.summaryTokens();
            }
        }
        return new Plan(before, saturate(total), actions);
    }

    private static Compact planCompaction(List<Item> items, long total, long target, Settings settings) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean conversational = item.kind() == Kind.USER || item.kind() == Kind.ASSISTANT
                || item.kind() == Kind.SUMMARY;
            if (conversational && !item.locked() && !item.pinned() && !item.recent()) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        long removed = 0;
        boolean sawRealItem = false;
        int best = -1;
        long bestRemoved = 0;
        for (int p = 0; p < candidates.size(); p++) {
            int index = candidates.get(p);
            Item item = items.get(index);
            removed += item.tokens();
            sawRealItem |= item.kind() != Kind.SUMMARY;
            if (!closesATurn(items, index) || !sawRealItem || removed <= settings.summaryTokens()) {
                continue;
            }
            best = p;
            bestRemoved = removed;
            if (total - removed + settings.summaryTokens() <= target) {
                break; // the shortest run that gets back under the clear line
            }
        }
        if (best < 0) {
            return null;
        }
        return new Compact(candidates.subList(0, best + 1), saturate(bestRemoved), settings.summaryTokens());
    }

    /** True when the item after {@code index} is a user turn, a protected item, or there is none. */
    private static boolean closesATurn(List<Item> items, int index) {
        if (index + 1 >= items.size()) {
            return true;
        }
        Item next = items.get(index + 1);
        return next.kind() == Kind.USER || next.kind() == Kind.SYSTEM || next.locked() || next.recent();
    }

    private static long limit(double fraction, int window) {
        return (long) Math.floor(fraction * window + 1e-6); // epsilon: 0.6 * 10_000 must not floor to 5_999
    }

    private static int saturate(long value) {
        return (int) Math.min(Math.max(value, 0L), Integer.MAX_VALUE);
    }
}
