package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.TurnToolActivity.Snapshot;
import com.agenticai.core.agents.turn.TurnToolActivity.Tally;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides whether a turn that already ran tools may be run again from the start on another provider
 * (the BYOK fallback provider, the next rung of the hosted pool). A failover re-runs the whole turn, tools
 * included, and "no text has been streamed yet" does not mean nothing happened: a tool-calling turn
 * commonly streams no text while it works.
 *
 * <p>The turn may be replayed when every tool call that reached its tool is either read-only or
 * recorded by the effect journal, so that an identical repeat is served from the journal instead of
 * reaching the provider again ({@code app.chat.resilience.replay-after-journaled-effects}). It may not
 * when a tool is still running (it can finish after the turn gave up on it), when a tool with side
 * effects ran without a journal record (the journal is off, or the call threw after the provider may
 * have acted), or when the journal is distrusted by configuration.
 *
 * <p>The journal proves idempotency per call, for an IDENTICAL call: a model that repeats a send with
 * different arguments on the replay is not protected by it. That is why the setting exists.
 *
 * <p>"Read-only" is the tool classification when the tool has one ({@code ToolReplaySafety}), else the
 * conservative tool-name rule {@link VerifierEscalationRouter#replaySafe} uses (unknown names are
 * treated as mutating).
 */
final class FailoverReplayGate {

    /** The answer: whether to replay, and the tools that stand in the way (comma-separated). */
    record Verdict(boolean allowed, String toolsThatRan) {
        static final Verdict ALLOWED = new Verdict(true, "");
    }

    private final ChatResilienceProperties props;
    private final Function<String, Optional<Boolean>> classification;

    /**
     * @param classification the tool classification's replay verdict ({@code true} safe, {@code false}
     *     unsafe, empty when unclassified); {@code null} = judge by name only
     */
    FailoverReplayGate(ChatResilienceProperties props, Function<String, Optional<Boolean>> classification) {
        this.props = props;
        this.classification = classification;
    }

    Verdict check(TurnToolActivity activity) {
        if (activity == null) {
            return Verdict.ALLOWED;
        }
        Snapshot snapshot = activity.snapshot();
        List<String> blockers = new ArrayList<>();
        for (String running : snapshot.inFlight()) {
            if (!blockers.contains(running)) {
                blockers.add(running);
            }
        }
        for (Map.Entry<String, Tally> entry : snapshot.finished().entrySet()) {
            String tool = entry.getKey();
            Tally tally = entry.getValue();
            int unprotected = props.isReplayAfterJournaledEffects() ? tally.unjournaled() : tally.reachedTool();
            if (unprotected > 0
                    && !blockers.contains(tool)
                    && !VerifierEscalationRouter.replaySafe(List.of(tool), classification)) {
                blockers.add(tool);
            }
        }
        return blockers.isEmpty() ? Verdict.ALLOWED : new Verdict(false, String.join(", ", blockers));
    }
}
