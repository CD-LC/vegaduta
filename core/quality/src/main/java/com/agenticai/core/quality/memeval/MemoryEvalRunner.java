package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.memeval.MemoryRun.ProbeOutcome;
import com.agenticai.core.quality.memeval.MemoryRun.TurnCost;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives a {@link MemoryEvalSubject} through scenarios: sessions in order, probes after their
 * session, the same seed for every arm of a case so arms pair exactly (scorecard doc section 5).
 */
public final class MemoryEvalRunner {

    private MemoryEvalRunner() {}

    public static MemoryRun run(
            MemoryEvalSubject subject,
            MemoryScenario scenario,
            MemoryArm arm,
            int repeat,
            long seed) {
        CaseEnvironment env = CaseEnvironment.of(scenario, arm, repeat);
        MemoryEvalSubject.Execution exec = subject.start(scenario, arm, repeat, seed, env);
        List<TurnCost> turns = new ArrayList<>();
        List<ProbeOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < scenario.sessions().size(); i++) {
            env.clock().advanceDays(scenario.sessions().get(i).gapDays());
            turns.addAll(exec.playSession(scenario.sessions().get(i)));
            for (Probe probe : scenario.probes()) {
                if (scenario.sessionIndex(probe.after()) == i) {
                    ProbeOutcome outcome = exec.ask(probe);
                    outcomes.add(outcome);
                    if (outcome.cost() != null) {
                        turns.add(outcome.cost());
                    }
                }
            }
        }
        return new MemoryRun(scenario.id(), arm, repeat, seed, outcomes, exec.store(), turns);
    }

    /** All scenarios x arms x repeats. The per-(scenario, repeat) seed is shared by every arm. */
    public static List<MemoryRun> runAll(
            MemoryEvalSubject subject,
            List<MemoryScenario> scenarios,
            List<MemoryArm> arms,
            int repeats,
            long baseSeed) {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats must be >= 1, got " + repeats);
        }
        List<MemoryRun> out = new ArrayList<>();
        for (MemoryScenario scenario : scenarios) {
            for (int r = 0; r < repeats; r++) {
                long seed = baseSeed ^ (scenario.seed() * 31L + r);
                for (MemoryArm arm : arms) {
                    out.add(run(subject, scenario, arm, r, seed));
                }
            }
        }
        return out;
    }
}
