package com.agenticai.core.agents.memorystore.decay;

import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.Batch;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.Forget;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.ForgetReason;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.Merge;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.Summarise;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Pure decision logic of the nightly consolidation (phase 1 design 03, sections 3 to 5). It reads
 * snapshots and a clock and returns a {@link ConsolidationPlan}; it touches no store, model or
 * thread. Same input, same clock: same plan.
 *
 * <p>Invariants (each has a property test):
 *
 * <ul>
 *   <li>a pinned row is never an input of any operation;
 *   <li>at most {@code floor(max-fraction-forgotten * rows)} rows are removed per call; hitting the
 *       limit sets {@code breakerTripped} so the caller files proposals instead of applying;
 *   <li>TRUSTED and QUARANTINED rows are planned in separate batches, and a derived summary's trust
 *       is the meet (lowest) of its sources; the plan type has no way to express TRUSTED or ACTIVE
 *       for anything that was not already so;
 *   <li>model-generated rows are never summarised again unless a person re-confirmed them, and a
 *       derived row's generation is {@code max(sources)+1}, never above {@code max-generation};
 *   <li>every row appears in at most one operation, and a merge keeper is never also removed.
 * </ul>
 */
public final class ConsolidationPlanner {

    private final DecayProperties props;
    private final Clock clock;
    private final MemorySalience salience;

    public ConsolidationPlanner(DecayProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.salience = new MemorySalience(props, clock);
    }

    public ConsolidationPlan plan(ConsolidationRequest req) {
        Instant now = clock.instant();
        Map<UUID, ItemSnapshot> byId = new HashMap<>();
        for (ItemSnapshot s : req.candidates()) {
            if (s.status() == ItemSnapshot.Status.ACTIVE) {
                byId.putIfAbsent(s.id(), s);
            }
        }
        List<ItemSnapshot> active = new ArrayList<>(byId.values());
        active.sort(Comparator.comparing(ItemSnapshot::id));
        int total = active.size();
        int cap = (int) Math.floor(props.getMaxFractionForgotten() * total + 1e-9);
        Map<UUID, Double> eff = new HashMap<>();
        for (ItemSnapshot s : active) {
            eff.put(s.id(), salience.effective(s, now));
        }

        State st = new State(cap);

        // 1. Forget.
        List<ItemSnapshot> forgettable = new ArrayList<>();
        for (ItemSnapshot s : active) {
            if (touchable(s, now) && (isTtlExpired(s, now) || eff.get(s.id()) < props.getForgetThreshold())) {
                forgettable.add(s);
            }
        }
        forgettable.sort(Comparator.comparing((ItemSnapshot s) -> !isTtlExpired(s, now))
                .thenComparing(s -> eff.get(s.id()))
                .thenComparing(ItemSnapshot::id));
        int forgets = 0;
        for (ItemSnapshot s : forgettable) {
            if (forgets >= props.getMaxForgetPerSubjectPerNight() || !st.fits(1)) {
                st.deferred++;
                continue;
            }
            ForgetReason reason = isTtlExpired(s, now) ? ForgetReason.QUARANTINE_TTL : ForgetReason.DECAY;
            st.forgets.get(s.trust()).add(new Forget(s.id(), eff.get(s.id()), reason));
            st.claim(s.id(), 1);
            forgets++;
        }

        // 2. Merge near duplicates.
        List<List<UUID>> groups = new ArrayList<>();
        for (Set<UUID> g : req.duplicateGroups()) {
            List<UUID> ids = new ArrayList<>(g);
            ids.sort(null);
            groups.add(ids);
        }
        groups.sort(Comparator.comparing((List<UUID> g) -> g.isEmpty() ? new UUID(0, 0) : g.get(0)));
        for (List<UUID> ids : groups) {
            planMerge(ids, byId, now, st);
        }

        // 3. Summarise.
        planSummaries(active, eff, now, st);

        List<Batch> batches = new ArrayList<>();
        for (MemoryTrustLevel t : List.of(MemoryTrustLevel.TRUSTED, MemoryTrustLevel.QUARANTINED)) {
            Batch b = new Batch(t, st.forgets.get(t), st.merges.get(t), st.summaries.get(t));
            if (!b.isEmpty()) {
                batches.add(b);
            }
        }
        return new ConsolidationPlan(
                req.subjectKey(), isDryRun(req.consolidationEnabledSince(), now), st.tripped, total, cap, st.deferred, batches);
    }

    /** True while consolidation has been enabled for fewer than {@code dry-run-days} (or never). */
    public boolean isDryRun(Instant enabledSince, Instant now) {
        return enabledSince == null
                || enabledSince.plus(Duration.ofDays(props.getDryRunDays())).isAfter(now);
    }

    public boolean isDryRun(Instant enabledSince) {
        return isDryRun(enabledSince, clock.instant());
    }

    private void planMerge(List<UUID> ids, Map<UUID, ItemSnapshot> byId, Instant now, State st) {
        List<ItemSnapshot> members = new ArrayList<>();
        for (UUID id : ids) {
            ItemSnapshot s = byId.get(id);
            if (s != null) {
                members.add(s);
            }
        }
        if (members.size() < 2) {
            return;
        }
        // Keeper: pinned first, then higher trust, then most recently used, most used, oldest, id.
        members.sort(Comparator.comparing((ItemSnapshot s) -> !s.pinned())
                .thenComparing(s -> s.trust() != MemoryTrustLevel.TRUSTED)
                .thenComparing(ConsolidationPlanner::lastTouched, Comparator.reverseOrder())
                .thenComparing(ItemSnapshot::uses, Comparator.reverseOrder())
                .thenComparing(ItemSnapshot::createdAt)
                .thenComparing(ItemSnapshot::id));
        ItemSnapshot keeper = members.get(0);
        if (st.claimed.contains(keeper.id())) {
            return;
        }
        Map<MemoryTrustLevel, List<UUID>> drops = new EnumMap<>(MemoryTrustLevel.class);
        for (MemoryTrustLevel t : MemoryTrustLevel.values()) {
            drops.put(t, new ArrayList<>());
        }
        for (ItemSnapshot s : members.subList(1, members.size())) {
            if (touchable(s, now) && !st.claimed.contains(s.id())) {
                drops.get(s.trust()).add(s.id());
            }
        }
        boolean keeperUsed = false;
        for (MemoryTrustLevel t : List.of(MemoryTrustLevel.TRUSTED, MemoryTrustLevel.QUARANTINED)) {
            List<UUID> d = drops.get(t);
            if (d.isEmpty()) {
                continue;
            }
            if (!st.fits(d.size())) {
                st.deferred++;
                continue;
            }
            st.merges.get(t).add(new Merge(keeper.id(), d));
            for (UUID id : d) {
                st.claim(id, 1);
            }
            keeperUsed = true;
        }
        if (keeperUsed) {
            st.claimed.add(keeper.id());
        }
    }

    private void planSummaries(List<ItemSnapshot> active, Map<UUID, Double> eff, Instant now, State st) {
        Map<String, List<ItemSnapshot>> groups = new TreeMap<>();
        for (ItemSnapshot s : active) {
            double e = eff.get(s.id());
            boolean band = e >= props.getForgetThreshold() && e < props.getSummariseThreshold();
            boolean modelOk = !s.derivedByModel() || s.humanReconfirmed();
            boolean genOk = s.generation() + 1 <= props.getMaxGeneration();
            if (band && modelOk && genOk && touchable(s, now) && !st.claimed.contains(s.id())) {
                String trustKey = props.isMixTrustBatches() ? "*" : s.trust().name();
                String key = trustKey + "|" + s.agentId() + "|" + s.kind().toLowerCase(java.util.Locale.ROOT);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
        }
        int made = 0;
        for (List<ItemSnapshot> g : groups.values()) {
            g.sort(Comparator.comparing((ItemSnapshot s) -> eff.get(s.id())).thenComparing(ItemSnapshot::id));
            for (int from = 0; from < g.size(); from += props.getSummaryBatchSize()) {
                List<ItemSnapshot> chunk = g.subList(from, Math.min(g.size(), from + props.getSummaryBatchSize()));
                if (chunk.size() < 2) {
                    continue;
                }
                if (made >= props.getMaxSummariesPerSubject() || !st.fits(chunk.size())) {
                    st.deferred++;
                    continue;
                }
                MemoryTrustLevel trust = meet(chunk);
                int gen = chunk.stream().mapToInt(ItemSnapshot::generation).max().orElse(0) + 1;
                double imp = Math.max(
                        0d, chunk.stream().mapToDouble(ItemSnapshot::importance).max().orElse(0) - 0.1);
                List<UUID> ids = chunk.stream().map(ItemSnapshot::id).toList();
                ItemSnapshot first = chunk.get(0);
                st.summaries.get(trust).add(new Summarise(ids, first.agentId(), first.kind(), trust, gen, imp));
                for (UUID id : ids) {
                    st.claim(id, 1);
                }
                made++;
            }
        }
    }

    /** Lowest trust of the inputs; an empty input is QUARANTINED (fail closed). */
    static MemoryTrustLevel meet(List<ItemSnapshot> inputs) {
        if (inputs.isEmpty()) {
            return MemoryTrustLevel.QUARANTINED;
        }
        for (ItemSnapshot s : inputs) {
            if (s.trust() != MemoryTrustLevel.TRUSTED) {
                return MemoryTrustLevel.QUARANTINED;
            }
        }
        return MemoryTrustLevel.TRUSTED;
    }

    /** Not pinned, old enough, and not a protected person-authored row. */
    private boolean touchable(ItemSnapshot s, Instant now) {
        if (s.pinned()) {
            return false;
        }
        if (props.isProtectPersonAuthored() && s.personAuthored()) {
            return false;
        }
        return !s.createdAt().plus(Duration.ofDays(props.getMinAgeDays())).isAfter(now);
    }

    private boolean isTtlExpired(ItemSnapshot s, Instant now) {
        return s.trust() == MemoryTrustLevel.QUARANTINED
                && !s.createdAt().plus(Duration.ofDays(props.getQuarantineTtlDays())).isAfter(now);
    }

    private static Instant lastTouched(ItemSnapshot s) {
        return s.lastUsedAt() != null && s.lastUsedAt().isAfter(s.createdAt()) ? s.lastUsedAt() : s.createdAt();
    }

    /** Mutable accumulator for one planning call. */
    private static final class State {
        final int cap;
        int removed;
        int deferred;
        boolean tripped;
        final Set<UUID> claimed = new HashSet<>();
        final Map<MemoryTrustLevel, List<Forget>> forgets = lists();
        final Map<MemoryTrustLevel, List<Merge>> merges = lists();
        final Map<MemoryTrustLevel, List<Summarise>> summaries = lists();

        State(int cap) {
            this.cap = cap;
        }

        private static <T> Map<MemoryTrustLevel, List<T>> lists() {
            Map<MemoryTrustLevel, List<T>> m = new EnumMap<>(MemoryTrustLevel.class);
            for (MemoryTrustLevel t : MemoryTrustLevel.values()) {
                m.put(t, new ArrayList<>());
            }
            return m;
        }

        /** Whether {@code n} more removals stay under the circuit breaker; trips it when not. */
        boolean fits(int n) {
            if (removed + n > cap) {
                tripped = true;
                return false;
            }
            return true;
        }

        void claim(UUID id, int removals) {
            claimed.add(id);
            removed += removals;
        }
    }
}
