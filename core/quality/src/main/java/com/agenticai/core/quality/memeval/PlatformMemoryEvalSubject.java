package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.memeval.MemoryRun.ProbeOutcome;
import com.agenticai.core.quality.memeval.MemoryRun.StoreItem;
import com.agenticai.core.quality.memeval.MemoryRun.TurnCost;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.Session;
import java.util.List;

/**
 * SKELETON of the runner that drives the real platform instead of {@link InMemoryReferenceSubject}.
 * Nothing here is implemented or wired: it fixes the seams so the work package that builds it (in a
 * module that may depend on the platform; core/quality may not depend on agents-turn, see the module
 * boundary baselines) only fills in the four hooks. See scorecard doc Addendum D for the contract.
 *
 * <p>Responsibilities of an implementation, per execution (one scenario, one arm, one repeat):
 * <ol>
 *   <li><b>Fresh identity.</b> {@link #provision} creates a tenant and user for every logical id of the
 *       scenario using {@link CaseEnvironment.CaseIdentity#tenantId} / {@code userId}, and an agent whose
 *       effective config is byte-identical across arms except for the memory switches
 *       ({@link CaseEnvironment#switches()}: recall maps to the {@code memory.recall.enabled} behaviour
 *       setting; there is no write switch yet, so for the B arm writes are suppressed by the recall-off
 *       and write-off profile the harness adds). Tenants are Postgres schemas: never reuse one.
 *   <li><b>Clock.</b> The same {@link EvalClock} instance is installed as the {@code memoryClock} bean
 *       (or via the memory services' {@code useClock}); the runner winds it before each session.
 *   <li><b>Replay.</b> {@link #replaySession} sends the session's turns as the session's user (source
 *       USER, or the tool-output, document, agent-message channel for untrusted turns), applies
 *       {@link Session#forgets()} through the real erase path and {@link Session#shares()} through the
 *       agent share-memory toggle, then returns the per-turn token and latency cost.
 *   <li><b>Probe.</b> {@link #askInFreshThread} asks in a new thread as the probe's user and tenant,
 *       on a different thread than the writer (AGENTS.md rule 12), and builds the {@link ProbeOutcome}:
 *       answer, tool calls, and the recall trace {@code injected} read from the probe turn's
 *       {@code memory_recall} / {@code memory_recall_ids} journal events (fact ids only), resolved to
 *       text and mapped back to logical user and tenant ids.
 *   <li><b>Store.</b> {@link #readStore} returns the memory rows of every tenant of the case as
 *       {@link StoreItem}s (logical ids), including graph memory for the forget check.
 * </ol>
 *
 * <p>Pooled-thread consolidation (S15) needs no hook: the platform's consolidation job runs on its own
 * pooled thread while the sessions are replayed, and a defect shows up as a foreign canary in
 * {@link ProbeOutcome#injected()} or in {@link #readStore}.
 */
public abstract class PlatformMemoryEvalSubject implements MemoryEvalSubject {

    /** Not supported: a platform subject cannot run without the runner's {@link CaseEnvironment}. */
    @Override
    public final Execution start(MemoryScenario scenario, MemoryArm arm, int repeat, long seed) {
        throw new UnsupportedOperationException("a platform subject needs a CaseEnvironment; use MemoryEvalRunner");
    }

    @Override
    public final Execution start(MemoryScenario scenario, MemoryArm arm, int repeat, long seed, CaseEnvironment env) {
        provision(scenario, arm, env);
        return new Execution() {
            @Override
            public List<TurnCost> playSession(Session session) {
                return replaySession(env, session);
            }

            @Override
            public ProbeOutcome ask(Probe probe) {
                return askInFreshThread(env, probe);
            }

            @Override
            public List<StoreItem> store() {
                return readStore(env);
            }
        };
    }

    /** Creates the case's tenants, users and agent; applies the arm's memory switches; installs the clock. */
    protected abstract void provision(MemoryScenario scenario, MemoryArm arm, CaseEnvironment env);

    /** Plays one session (clock already wound), applying its forgets and shares; returns the cost per turn. */
    protected abstract List<TurnCost> replaySession(CaseEnvironment env, Session session);

    /** Asks one probe in a fresh thread for the probe's user and tenant and returns the observed outcome. */
    protected abstract ProbeOutcome askInFreshThread(CaseEnvironment env, Probe probe);

    /** The memory store of the whole case as it is now, in logical ids. */
    protected abstract List<StoreItem> readStore(CaseEnvironment env);
}
