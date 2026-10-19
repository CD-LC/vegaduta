package com.agenticai.core.workflow;

import com.agenticai.core.workflow.expression.NodeOutput;
import java.util.List;
import java.util.Map;

/**
 * Per-execution facts an expression can read beyond the node input: the loop / fan_out / sub_flow item
 * ({@code $item}, {@code $index}, {@code $items}) and how the run was started. Plain data so it crosses
 * the Temporal activity boundary inside {@code NodeExecutionActivityInput}; every component is nullable
 * so an older serialized payload (no scope at all) still reads as "ordinary node in a production run".
 *
 * @param item          this iteration's item as the raw text the body node receives (JSON text or a string)
 * @param index         zero-based iteration number; also the {@code seq} the node's output is stored under
 * @param items         every item of the iteration set, or null when the set was too large to carry along
 * @param mode          {@code production} (default), {@code draft} or {@code test}; surfaced as {@code $run.mode}
 * @param skipRecording true for throwaway executions (step test) that have no run row to attach outputs to
 * @param supplied      step test only: upstream outputs the caller supplied, keyed {@code id:<nodeId>} and
 *                      {@code label:<label>} (the trigger also under {@code id:__trigger}). Non-null means "this is
 *                      a test, $node / $trigger read from here"; null in every real run. Never crosses Temporal.
 */
public record ExpressionScope(
    String item, Integer index, List<String> items, String mode, Boolean skipRecording, Map<String, NodeOutput> supplied
) {

    public ExpressionScope(String item, Integer index, List<String> items, String mode, Boolean skipRecording) {
        this(item, index, items, mode, skipRecording, null);
    }

    /** Above this many characters across all items, {@code $items} is not carried (Temporal payload limits). */
    static final int MAX_ITEMS_CHARS = 100_000;

    public static ExpressionScope iteration(String item, int index, List<String> allItems) {
        long total = 0;
        if (allItems != null) {
            for (String s : allItems) {
                total += s == null ? 0 : s.length();
            }
        }
        return new ExpressionScope(item, index, total <= MAX_ITEMS_CHARS ? allItems : null, null, null);
    }

    /** A step test: no run row exists, so nothing may be recorded against it. */
    public static ExpressionScope test() {
        return test(Map.of());
    }

    /** A step test that can read the given upstream outputs through {@code $node} / {@code $trigger}. */
    public static ExpressionScope test(Map<String, NodeOutput> supplied) {
        return new ExpressionScope(null, null, null, "test", Boolean.TRUE, supplied == null ? Map.of() : supplied);
    }

    /**
     * The scope a node of a run in {@code mode} executes under: {@code scope} with the mode stamped on it, a
     * mode-only scope when there is none, and {@code scope} untouched (possibly null) for production - so a
     * production run binds exactly what it bound before pins existed and can never look a pin up.
     */
    public static ExpressionScope forMode(ExpressionScope scope, RunMode mode) {
        if (mode == null || !mode.honoursPins()) {
            return scope;
        }
        if (scope == null) {
            return new ExpressionScope(null, null, null, mode.wire(), null, null);
        }
        return new ExpressionScope(scope.item, scope.index, scope.items, mode.wire(), scope.skipRecording, scope.supplied);
    }

    /**
     * True when a pinned node must be replaced by its pin. A step test is excluded ({@code supplied} non-null):
     * it runs the node under test for real, and its upstream pins arrive through {@code supplied}.
     */
    boolean honoursPins() {
        return supplied == null && RunMode.fromWire(mode).honoursPins();
    }

    /**
     * True when this execution belongs to a dry run ({@link RunMode#DRAFT_DRY_RUN}): nodes that would send
     * something out are simulated. Never true for a step test, which has its own side-effect refusal.
     */
    boolean simulatesSends() {
        return supplied == null && RunMode.fromWire(mode).simulatesSends();
    }

    int seq() {
        return index == null ? 0 : Math.max(index, 0);
    }

    String modeOrDefault() {
        return mode == null || mode.isBlank() ? "production" : mode;
    }

    boolean recordingSkipped() {
        return Boolean.TRUE.equals(skipRecording);
    }
}
