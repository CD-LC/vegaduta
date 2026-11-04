package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.memeval.MemoryRun.ProbeOutcome;
import com.agenticai.core.quality.memeval.MemoryRun.StoreItem;
import com.agenticai.core.quality.memeval.MemoryRun.TurnCost;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.Session;
import java.util.List;

/**
 * The system under test, as seen by the memory eval. The reference implementation is
 * {@link InMemoryReferenceSubject} (no LLM, no DB); a runner against the platform implements this same
 * interface, starting from {@link PlatformMemoryEvalSubject}.
 *
 * <p>Extension points a platform runner needs (design: scorecard doc Addendum D):
 * <ul>
 *   <li>{@link #start(MemoryScenario, MemoryArm, int, long, CaseEnvironment)} receives a fresh tenant and
 *       user id per (case, arm, repeat), the {@link EvalClock} the runner winds by each session's gap, and
 *       the arm's memory switches. The four-argument {@code start} stays the entry point of subjects that
 *       need none of this (the in-memory reference).
 *   <li>{@link ProbeOutcome#injected()} is the recall trace: a platform subject fills it from the
 *       {@code memory_recall} turn-journal events of the probe turn, mapping physical ids back with
 *       {@link CaseEnvironment.CaseIdentity#logicalUser(String)}.
 * </ul>
 */
public interface MemoryEvalSubject {

    String name();

    /** Starts an isolated execution of one scenario in one arm; state must not bleed between starts. */
    Execution start(MemoryScenario scenario, MemoryArm arm, int repeat, long seed);

    /**
     * Starts an execution with the identity, clock and switches the runner prepared. The default
     * ignores the environment and delegates, so existing subjects keep working; the runner always calls
     * this overload.
     */
    default Execution start(MemoryScenario scenario, MemoryArm arm, int repeat, long seed, CaseEnvironment env) {
        return start(scenario, arm, repeat, seed);
    }

    /** One execution; the runner replays sessions in order and asks probes after their session. */
    interface Execution {

        /** Plays the session (the {@link EvalClock} is already advanced by its gap); returns the cost of each turn. */
        List<TurnCost> playSession(Session session);

        /** Asks a probe in a fresh thread for the probe's own user and tenant. */
        ProbeOutcome ask(Probe probe);

        /** The memory store as it is now. */
        List<StoreItem> store();
    }
}
