package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.memeval.MemoryRun.ProbeOutcome;
import com.agenticai.core.quality.memeval.MemoryRun.StoreItem;
import com.agenticai.core.quality.memeval.MemoryRun.TurnCost;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.Session;
import java.util.List;

/**
 * The system under test, as seen by the memory eval. The reference implementation is
 * {@link InMemoryReferenceSubject} (no LLM, no DB); a runner against the platform (fresh tenant and
 * user per case, arm and repeat, a {@code Clock} advanced by the scripted gaps) is a separate work
 * package and implements this same interface.
 */
public interface MemoryEvalSubject {

    String name();

    /** Starts an isolated execution of one scenario in one arm; state must not bleed between starts. */
    Execution start(MemoryScenario scenario, MemoryArm arm, int repeat, long seed);

    /** One execution; the runner replays sessions in order and asks probes after their session. */
    interface Execution {

        /** Plays the session (clock already advanced by its gap); returns the cost of each turn. */
        List<TurnCost> playSession(Session session);

        /** Asks a probe in a fresh thread for the probe's own user and tenant. */
        ProbeOutcome ask(Probe probe);

        /** The memory store as it is now. */
        List<StoreItem> store();
    }
}
