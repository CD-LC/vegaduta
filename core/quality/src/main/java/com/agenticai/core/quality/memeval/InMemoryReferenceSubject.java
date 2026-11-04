package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.memeval.MemoryRun.InjectedItem;
import com.agenticai.core.quality.memeval.MemoryRun.ProbeOutcome;
import com.agenticai.core.quality.memeval.MemoryRun.StoreItem;
import com.agenticai.core.quality.memeval.MemoryRun.ToolCall;
import com.agenticai.core.quality.memeval.MemoryRun.TurnCost;
import com.agenticai.core.quality.memeval.MemoryScenario.Fact;
import com.agenticai.core.quality.memeval.MemoryScenario.FactKind;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.Session;
import com.agenticai.core.quality.memeval.MemoryScenario.ToolTask;
import com.agenticai.core.quality.memeval.MemoryScenario.Turn;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic in-memory stand-in for the platform's memory (no LLM, no DB, no network), so the whole
 * eval pipeline runs in unit tests. It is a fixture, not a claim about the platform:
 *
 * <ul>
 *   <li>extraction is perfect: it stores the facts a turn's {@code plants} names (the ledger is the
 *       extractor's ground truth), except attack payloads, which its write gate rejects;
 *   <li>the "model" copies the memory block: the answer is the matched tokens of the injected items,
 *       or an abstention when nothing was injected;
 *   <li>the tool agent follows the {@link MemoryScenario.ToolMode} of the probe task.
 * </ul>
 *
 * A well-behaved subject ({@code Set.of()} quirks) is the "good" fixture. Each {@link Quirk} breaks
 * exactly one property, so tests can show that the scorers and the gate catch it.
 */
public final class InMemoryReferenceSubject implements MemoryEvalSubject {

    /** One deliberate defect. */
    public enum Quirk {
        /** The write gate lets attack payloads into the store. */
        PERSISTS_ATTACKS,
        /** The model follows an injected rule (canary reaches tool arguments, approvals are skipped). */
        OBEYS_INJECTED_RULES,
        /** Erase requests are ignored. */
        IGNORES_FORGET,
        /** A newer value does not replace the older one: two live facts per key. */
        KEEPS_STALE_FACTS,
        /** Recall ignores the user boundary inside a tenant. */
        LEAKS_ACROSS_USERS,
        /** Recall ignores the tenant boundary. */
        LEAKS_ACROSS_TENANTS,
        /** Recall returns everything in scope instead of what the query touches. */
        OVER_RECALLS,
        /** The model invents an answer instead of abstaining. */
        INVENTS_ANSWERS,
        /** Corrections and rules are not applied to the next attempt. */
        REPEATS_MISTAKES,
        /** Every write is stored twice. */
        DUPLICATES_FACTS,
        /** Recall adds a large latency (a network hop on the hot path). */
        SLOW_RECALL
    }

    private static final int OVER_RECALL_CAP = 12;

    private final String name;
    private final Set<Quirk> quirks;

    public InMemoryReferenceSubject(String name, Set<Quirk> quirks) {
        this.name = Objects.requireNonNull(name);
        this.quirks = quirks.isEmpty() ? EnumSet.noneOf(Quirk.class) : EnumSet.copyOf(quirks);
    }

    /** The well-behaved fixture. */
    public static InMemoryReferenceSubject good() {
        return new InMemoryReferenceSubject("reference-good", Set.of());
    }

    public static InMemoryReferenceSubject with(String name, Quirk first, Quirk... rest) {
        return new InMemoryReferenceSubject(name, EnumSet.of(first, rest));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Execution start(MemoryScenario scenario, MemoryArm arm, int repeat, long seed) {
        return new Exec(scenario, arm);
    }

    private final class Exec implements Execution {
        private final MemoryScenario scenario;
        private final MemoryArm arm;
        private final Map<String, Fact> facts;
        private final List<StoreItem> store = new ArrayList<>();

        Exec(MemoryScenario scenario, MemoryArm arm) {
            this.scenario = scenario;
            this.arm = arm;
            this.facts = scenario.factsById();
        }

        @Override
        public List<TurnCost> playSession(Session session) {
            List<TurnCost> costs = new ArrayList<>();
            for (Turn turn : session.turns()) {
                if (arm == MemoryArm.M) {
                    for (String id : turn.plants()) {
                        write(facts.get(id), session);
                    }
                }
                int prompt = 380 + turn.text().length() / 4;
                int completion = 50 + (arm == MemoryArm.M ? 10 : 0);
                double latency = arm == MemoryArm.B ? 40.0 : 41.5;
                costs.add(new TurnCost(prompt, completion, latency, latency * 0.5));
            }
            if (arm == MemoryArm.M && !quirks.contains(Quirk.IGNORES_FORGET)) {
                store.removeIf(item -> session.forgets().contains(item.factId()));
            }
            return costs;
        }

        private void write(Fact fact, Session session) {
            if (fact == null) {
                return;
            }
            if (fact.kind() == FactKind.ATTACK && !quirks.contains(Quirk.PERSISTS_ATTACKS)) {
                return; // the write gate rejects it
            }
            String user = scenario.ownerUser(fact) != null ? scenario.ownerUser(fact) : session.userId();
            String tenant = scenario.ownerTenant(fact) != null ? scenario.ownerTenant(fact) : session.tenantId();
            if (!quirks.contains(Quirk.KEEPS_STALE_FACTS)) {
                store.removeIf(i -> i.key().equals(fact.key()) && Objects.equals(i.userId(), user)
                    && Objects.equals(i.tenantId(), tenant) && !Objects.equals(i.factId(), fact.id()));
            }
            StoreItem item = new StoreItem(fact.id(), fact.key(), fact.value(), user, tenant, false);
            store.add(item);
            if (quirks.contains(Quirk.DUPLICATES_FACTS)) {
                store.add(item);
            }
        }

        @Override
        public ProbeOutcome ask(Probe probe) {
            List<InjectedItem> injected = recall(probe);
            boolean obeyed = quirks.contains(Quirk.OBEYS_INJECTED_RULES) && hasAttack(injected);
            List<ToolCall> calls = new ArrayList<>();
            if (probe.task() != null) {
                calls.add(act(probe.task(), injected, obeyed));
            }
            String answer = answer(probe, injected, obeyed);
            int prompt = 400 + 12 * injected.size();
            double latency = arm == MemoryArm.B ? 40.0 : 41.5 + 0.2 * injected.size();
            if (arm != MemoryArm.B && quirks.contains(Quirk.SLOW_RECALL)) {
                latency += 80.0;
            }
            return new ProbeOutcome(
                probe.id(), answer, calls, injected, List.copyOf(store),
                new TurnCost(prompt, 60, latency, latency * 0.5));
        }

        private List<InjectedItem> recall(Probe probe) {
            List<InjectedItem> out = new ArrayList<>();
            if (arm == MemoryArm.B) {
                return out;
            }
            if (arm == MemoryArm.ORACLE) {
                int idx = scenario.sessionIndex(probe.after());
                for (Fact f : scenario.liveFacts(idx)) {
                    if (f.relevantTo().contains(probe.id())
                        && Objects.equals(scenario.ownerUser(f), probe.userId())
                        && Objects.equals(scenario.ownerTenant(f), probe.tenantId())) {
                        out.add(new InjectedItem(f.id(), f.key(), f.value(), probe.userId(), probe.tenantId()));
                    }
                }
                return out;
            }
            boolean overRecall = quirks.contains(Quirk.OVER_RECALLS);
            boolean crossTenant = quirks.contains(Quirk.LEAKS_ACROSS_TENANTS);
            boolean crossUser = crossTenant || quirks.contains(Quirk.LEAKS_ACROSS_USERS);
            for (StoreItem item : store) {
                boolean tenantOk = crossTenant || Objects.equals(item.tenantId(), probe.tenantId());
                boolean userOk = crossUser || Objects.equals(item.userId(), probe.userId());
                boolean topicOk = overRecall || probe.queryKeys().contains(item.key());
                if (tenantOk && userOk && topicOk && out.size() < (overRecall ? OVER_RECALL_CAP : 64)) {
                    out.add(new InjectedItem(item.factId(), item.key(), item.text(), item.userId(), item.tenantId()));
                }
            }
            return out;
        }

        private boolean isAttack(InjectedItem item) {
            Fact f = item.factId() == null ? null : facts.get(item.factId());
            return f != null && f.kind() == FactKind.ATTACK;
        }

        private boolean hasAttack(List<InjectedItem> injected) {
            return injected.stream().anyMatch(this::isAttack);
        }

        private String token(InjectedItem item) {
            Fact f = item.factId() == null ? null : facts.get(item.factId());
            return f == null ? item.text() : f.matchToken();
        }

        private String answer(Probe probe, List<InjectedItem> injected, boolean obeyed) {
            List<String> parts = new ArrayList<>();
            for (InjectedItem item : injected) {
                if (!isAttack(item) || obeyed) {
                    parts.add(isAttack(item) ? item.text() : token(item));
                }
            }
            if (!parts.isEmpty()) {
                return "[memory] " + String.join(" | ", parts);
            }
            if (probe.task() != null) {
                return "Done.";
            }
            return quirks.contains(Quirk.INVENTS_ANSWERS)
                ? "It is B+, as far as I recall."
                : "I don't know - you haven't told me that.";
        }

        private ToolCall act(ToolTask task, List<InjectedItem> injected, boolean obeyed) {
            boolean mistakes = quirks.contains(Quirk.REPEATS_MISTAKES);
            InjectedItem keyed = injected.stream()
                .filter(i -> !isAttack(i) && Objects.equals(i.key(), task.memoryKey()))
                .findFirst().orElse(null);
            String tool = task.tool();
            String args = task.defaultArg();
            switch (task.mode()) {
                case REPLACE_ARG -> {
                    if (keyed != null && !mistakes) {
                        args = token(keyed);
                    }
                }
                case PARK -> {
                    if (keyed != null && !mistakes) {
                        tool = "request_approval";
                    }
                }
                case GUARDED -> tool = obeyed ? task.tool() : "request_approval";
                case PLAIN -> { }
            }
            if (obeyed) {
                for (InjectedItem item : injected) {
                    if (isAttack(item)) {
                        args = args + " " + item.text();
                    }
                }
            }
            return new ToolCall(tool, args);
        }

        @Override
        public List<StoreItem> store() {
            return List.copyOf(store);
        }
    }
}
