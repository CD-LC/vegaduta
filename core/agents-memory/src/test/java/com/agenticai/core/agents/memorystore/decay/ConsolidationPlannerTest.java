package com.agenticai.core.agents.memorystore.decay;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.Batch;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.Forget;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.ForgetReason;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.Merge;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan.Summarise;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsolidationPlannerTest {

    static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    static final UUID AGENT = new UUID(1, 1);
    static final Instant LONG_AGO = NOW.minus(Duration.ofDays(1000));

    final DecayProperties props = new DecayProperties();
    final ConsolidationPlanner planner =
            new ConsolidationPlanner(props, Clock.fixed(NOW, ZoneOffset.UTC));

    static ItemSnapshot row(long n, MemoryTrustLevel trust, double imp, long ageDays) {
        return new ItemSnapshot(
                new UUID(0, n),
                AGENT,
                "episodic",
                trust,
                ItemSnapshot.Status.ACTIVE,
                false,
                false,
                imp,
                0,
                NOW.minus(Duration.ofDays(ageDays)),
                null,
                List.of(),
                0,
                false,
                false);
    }

    static ItemSnapshot with(
            ItemSnapshot s,
            boolean pinned,
            boolean person,
            int gen,
            boolean model,
            boolean reconf) {
        return new ItemSnapshot(
                s.id(),
                s.agentId(),
                s.kind(),
                s.trust(),
                s.status(),
                pinned,
                person,
                s.importance(),
                s.uses(),
                s.createdAt(),
                s.lastUsedAt(),
                gen > 0 ? List.of(new UUID(9, 9)) : List.of(),
                gen,
                model,
                reconf);
    }

    ConsolidationPlan plan(List<ItemSnapshot> items) {
        return plan(items, List.of(), NOW.minus(Duration.ofDays(30)));
    }

    ConsolidationPlan plan(List<ItemSnapshot> items, List<Set<UUID>> dups, Instant since) {
        return planner.plan(new ConsolidationRequest("u1", items, dups, since));
    }

    /** Padding of healthy rows so the 30% breaker leaves room. */
    static List<ItemSnapshot> healthy(int n) {
        List<ItemSnapshot> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(row(10_000 + i, MemoryTrustLevel.TRUSTED, 0.9, 20));
        }
        return l;
    }

    static List<Forget> forgets(ConsolidationPlan p) {
        return p.batches().stream().flatMap(b -> b.forgets().stream()).toList();
    }

    static List<Summarise> sums(ConsolidationPlan p) {
        return p.batches().stream().flatMap(b -> b.summaries().stream()).toList();
    }

    static List<Merge> merges(ConsolidationPlan p) {
        return p.batches().stream().flatMap(b -> b.merges().stream()).toList();
    }

    @Test
    void forgetsLowSalienceOldRowsOnly() {
        List<ItemSnapshot> items = new ArrayList<>(healthy(10));
        items.add(row(1, MemoryTrustLevel.TRUSTED, 0.0, 400)); // S ~ 0
        items.add(row(2, MemoryTrustLevel.TRUSTED, 0.0, 5)); // too young
        ConsolidationPlan p = plan(items);
        assertThat(forgets(p)).extracting(Forget::id).containsExactly(new UUID(0, 1));
        assertThat(forgets(p).get(0).reason()).isEqualTo(ForgetReason.DECAY);
    }

    @Test
    void minAgeProtectsEveryOperation() {
        List<ItemSnapshot> items = new ArrayList<>(healthy(10));
        items.add(row(1, MemoryTrustLevel.TRUSTED, 0.0, 13));
        assertThat(plan(items).isEmpty()).isTrue();
    }

    @Test
    void pinnedAndPersonAuthoredAreNeverTouched() {
        List<ItemSnapshot> items = new ArrayList<>(healthy(10));
        items.add(with(row(1, MemoryTrustLevel.TRUSTED, 0, 900), true, false, 0, false, false));
        items.add(with(row(2, MemoryTrustLevel.TRUSTED, 0, 900), false, true, 0, false, false));
        assertThat(plan(items).isEmpty()).isTrue();
        props.setProtectPersonAuthored(false);
        assertThat(forgets(plan(items)).stream().map(Forget::id)).containsExactly(new UUID(0, 2));
    }

    @Test
    void quarantineTtlForgetsEvenHighImportanceUnapprovedRows() {
        List<ItemSnapshot> items = new ArrayList<>(healthy(10));
        items.add(row(1, MemoryTrustLevel.QUARANTINED, 1.0, 31));
        items.add(row(2, MemoryTrustLevel.QUARANTINED, 1.0, 20));
        ConsolidationPlan p = plan(items);
        assertThat(forgets(p)).extracting(Forget::id).containsExactly(new UUID(0, 1));
        assertThat(forgets(p).get(0).reason()).isEqualTo(ForgetReason.QUARANTINE_TTL);
        assertThat(p.batches().get(0).trustClass()).isEqualTo(MemoryTrustLevel.QUARANTINED);
    }

    @Test
    void summariseBandBatchesTrustedAndQuarantinedSeparately() {
        List<ItemSnapshot> items = new ArrayList<>(healthy(30));
        // importance .45 idle ~ forever: S = .2025 -> in band; quarantined x.5 = .10 (forget band)
        // so
        // use importance .8: trusted S=.36 not in band; choose per-trust importance for band.
        for (int i = 0; i < 4; i++) {
            items.add(row(100 + i, MemoryTrustLevel.TRUSTED, 0.5, 900));
            items.add(row(200 + i, MemoryTrustLevel.QUARANTINED, 0.9, 25));
        }
        ConsolidationPlan p = plan(items);
        List<Summarise> s = sums(p);
        assertThat(s).hasSize(2);
        for (Batch b : p.batches()) {
            for (Summarise x : b.summaries()) {
                assertThat(x.trust()).isEqualTo(b.trustClass());
                assertThat(x.sourceIds()).hasSize(4);
            }
        }
        assertThat(s.stream().map(Summarise::trust))
                .containsExactlyInAnyOrder(MemoryTrustLevel.TRUSTED, MemoryTrustLevel.QUARANTINED);
    }

    @Test
    void mixedTrustBatchYieldsQuarantinedSummary() {
        props.setMixTrustBatches(true);
        List<ItemSnapshot> items = new ArrayList<>(healthy(30));
        items.add(row(1, MemoryTrustLevel.TRUSTED, 0.5, 900));
        items.add(row(2, MemoryTrustLevel.QUARANTINED, 0.9, 25));
        ConsolidationPlan p = plan(items);
        assertThat(sums(p)).hasSize(1);
        assertThat(sums(p).get(0).trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
    }

    @Test
    void summaryLineageGenerationAndImportance() {
        List<ItemSnapshot> items = new ArrayList<>(healthy(30));
        items.add(with(row(1, MemoryTrustLevel.TRUSTED, 0.5, 900), false, false, 1, true, true));
        items.add(row(2, MemoryTrustLevel.TRUSTED, 0.55, 900));
        Summarise s = sums(plan(items)).get(0);
        assertThat(s.generation()).isEqualTo(2);
        assertThat(s.importance()).isEqualTo(0.45, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(s.sourceIds()).containsExactlyInAnyOrder(new UUID(0, 1), new UUID(0, 2));
    }

    @Test
    void modelGeneratedInputsExcludedUnlessReconfirmed() {
        List<ItemSnapshot> items = new ArrayList<>(healthy(30));
        items.add(with(row(1, MemoryTrustLevel.TRUSTED, 0.5, 900), false, false, 1, true, false));
        items.add(with(row(2, MemoryTrustLevel.TRUSTED, 0.5, 900), false, false, 0, true, false));
        items.add(row(3, MemoryTrustLevel.TRUSTED, 0.5, 900));
        items.add(row(4, MemoryTrustLevel.TRUSTED, 0.5, 900));
        List<Summarise> s = sums(plan(items));
        assertThat(s).hasSize(1);
        assertThat(s.get(0).sourceIds()).containsExactlyInAnyOrder(new UUID(0, 3), new UUID(0, 4));
    }

    @Test
    void generationCapExcludesInputs() {
        props.setMaxGeneration(2);
        List<ItemSnapshot> items = new ArrayList<>(healthy(30));
        items.add(with(row(1, MemoryTrustLevel.TRUSTED, 0.5, 900), false, false, 2, true, true));
        items.add(row(2, MemoryTrustLevel.TRUSTED, 0.5, 900));
        items.add(row(3, MemoryTrustLevel.TRUSTED, 0.5, 900));
        List<Summarise> s = sums(plan(items));
        assertThat(s).hasSize(1);
        assertThat(s.get(0).sourceIds()).doesNotContain(new UUID(0, 1));
        assertThat(s.get(0).generation()).isEqualTo(1);
    }

    @Test
    void mergeKeepsTrustedAndNeverDropsPinned() {
        List<ItemSnapshot> items = new ArrayList<>(healthy(10));
        ItemSnapshot trusted = row(1, MemoryTrustLevel.TRUSTED, 0.9, 40);
        ItemSnapshot quarantined = row(2, MemoryTrustLevel.QUARANTINED, 0.9, 20);
        ItemSnapshot pinnedDup =
                with(row(3, MemoryTrustLevel.TRUSTED, 0.9, 40), true, false, 0, false, false);
        items.addAll(List.of(trusted, quarantined, pinnedDup));
        ConsolidationPlan p =
                plan(
                        items,
                        List.of(Set.of(trusted.id(), quarantined.id())),
                        NOW.minus(Duration.ofDays(30)));
        assertThat(merges(p)).hasSize(1);
        assertThat(merges(p).get(0).keepId()).isEqualTo(trusted.id());
        assertThat(merges(p).get(0).dropIds()).containsExactly(quarantined.id());
        assertThat(p.batches().get(0).trustClass()).isEqualTo(MemoryTrustLevel.QUARANTINED);

        ConsolidationPlan q =
                plan(
                        items,
                        List.of(Set.of(trusted.id(), pinnedDup.id())),
                        NOW.minus(Duration.ofDays(30)));
        assertThat(merges(q)).hasSize(1);
        assertThat(merges(q).get(0).keepId()).isEqualTo(pinnedDup.id());
        assertThat(merges(q).get(0).dropIds()).containsExactly(trusted.id());
    }

    @Test
    void circuitBreakerCapsRemovalsAndTrips() {
        List<ItemSnapshot> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(row(i, MemoryTrustLevel.TRUSTED, 0.0, 400));
        }
        ConsolidationPlan p = plan(items);
        assertThat(p.removalCap()).isEqualTo(3);
        assertThat(p.removalCount()).isEqualTo(3);
        assertThat(p.breakerTripped()).isTrue();
        assertThat(p.mayAutoApply()).isFalse();
        assertThat(p.deferred()).isEqualTo(7);
    }

    @Test
    void forgetCapPerSubject() {
        props.setMaxFractionForgotten(1.0);
        props.setMaxForgetPerSubjectPerNight(5);
        List<ItemSnapshot> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add(row(i, MemoryTrustLevel.TRUSTED, 0.0, 400));
        }
        assertThat(forgets(plan(items))).hasSize(5);
    }

    @Test
    void dryRunForFirstSevenDaysAfterEnabling() {
        List<ItemSnapshot> items = healthy(3);
        assertThat(plan(items, List.of(), NOW.minus(Duration.ofDays(6))).dryRun()).isTrue();
        assertThat(plan(items, List.of(), NOW.minus(Duration.ofDays(7))).dryRun()).isFalse();
        assertThat(plan(items, List.of(), null).dryRun()).isTrue();
        props.setDryRunDays(30);
        assertThat(plan(items, List.of(), NOW.minus(Duration.ofDays(20))).mayAutoApply()).isFalse();
    }

    @Test
    void nonActiveRowsAreIgnored() {
        ItemSnapshot s = row(1, MemoryTrustLevel.TRUSTED, 0, 900);
        ItemSnapshot pending =
                new ItemSnapshot(
                        s.id(),
                        AGENT,
                        "x",
                        MemoryTrustLevel.TRUSTED,
                        ItemSnapshot.Status.PENDING,
                        false,
                        false,
                        0,
                        0,
                        s.createdAt(),
                        null,
                        List.of(),
                        0,
                        false,
                        false);
        assertThat(plan(List.of(pending)).isEmpty()).isTrue();
    }

    @Test
    void planTypeCannotExpressActiveOrApproval() {
        for (Class<?> c :
                List.of(
                        ConsolidationPlan.class,
                        Batch.class,
                        Forget.class,
                        Merge.class,
                        Summarise.class)) {
            for (RecordComponent rc : c.getRecordComponents()) {
                assertThat(rc.getType().getName()).doesNotContain("Status");
                assertThat(rc.getName().toLowerCase())
                        .doesNotContain("status")
                        .doesNotContain("approved")
                        .doesNotContain("active");
            }
        }
    }

    @Test
    void randomisedInvariantsHold() {
        int nonEmpty = 0;
        int trips = 0;
        int merged = 0;
        int summed = 0;
        for (long seed = 1; seed <= 300; seed++) {
            Random r = new Random(seed);
            int n = 1 + r.nextInt(60);
            List<ItemSnapshot> items = new ArrayList<>();
            Map<UUID, ItemSnapshot> byId = new java.util.HashMap<>();
            for (int i = 0; i < n; i++) {
                MemoryTrustLevel t =
                        r.nextInt(3) == 0 ? MemoryTrustLevel.QUARANTINED : MemoryTrustLevel.TRUSTED;
                ItemSnapshot s =
                        new ItemSnapshot(
                                new UUID(seed, i),
                                new UUID(0, r.nextInt(2)),
                                List.of("preference", "episodic", "task-context").get(r.nextInt(3)),
                                t,
                                r.nextInt(10) == 0
                                        ? ItemSnapshot.Status.PENDING
                                        : ItemSnapshot.Status.ACTIVE,
                                r.nextInt(8) == 0,
                                r.nextInt(8) == 0,
                                r.nextDouble() * 0.6,
                                r.nextInt(25),
                                NOW.minus(Duration.ofDays(r.nextInt(700))),
                                r.nextBoolean() ? null : NOW.minus(Duration.ofDays(r.nextInt(300))),
                                r.nextInt(6) == 0 ? List.of(new UUID(5, 5)) : List.of(),
                                r.nextInt(5),
                                r.nextInt(6) == 0,
                                r.nextInt(4) == 0);
                items.add(s);
                byId.put(s.id(), s);
            }
            List<Set<UUID>> dups = new ArrayList<>();
            for (int g = 0; g < r.nextInt(5); g++) {
                Set<UUID> grp = new HashSet<>();
                for (int k = 0; k < 2 + r.nextInt(3); k++) {
                    grp.add(items.get(r.nextInt(n)).id());
                }
                dups.add(grp);
            }
            props.setMixTrustBatches(seed % 5 == 0);
            props.setMaxFractionForgotten(seed % 3 == 0 ? 0.1 : 0.3);
            ConsolidationRequest req =
                    new ConsolidationRequest(
                            "u", items, dups, NOW.minus(Duration.ofDays(r.nextInt(20))));
            ConsolidationPlan p = planner.plan(req);

            // determinism, also under input shuffling
            assertThat(planner.plan(req)).isEqualTo(p);
            List<ItemSnapshot> shuffled = new ArrayList<>(items);
            java.util.Collections.shuffle(shuffled, new Random(seed));
            assertThat(
                            planner.plan(
                                    new ConsolidationRequest(
                                            "u", shuffled, dups, req.consolidationEnabledSince())))
                    .isEqualTo(p);

            long active =
                    items.stream().filter(s -> s.status() == ItemSnapshot.Status.ACTIVE).count();
            assertThat(p.removalCount())
                    .isLessThanOrEqualTo(
                            (int) Math.floor(props.getMaxFractionForgotten() * active + 1e-9));
            assertThat(forgets(p).size())
                    .isLessThanOrEqualTo(props.getMaxForgetPerSubjectPerNight());
            assertThat(sums(p).size()).isLessThanOrEqualTo(props.getMaxSummariesPerSubject());

            Set<UUID> touched = new HashSet<>();
            Set<UUID> keepers = new HashSet<>();
            for (Batch b : p.batches()) {
                List<UUID> removed = new ArrayList<>();
                b.forgets().forEach(f -> removed.add(f.id()));
                b.merges()
                        .forEach(
                                m -> {
                                    removed.addAll(m.dropIds());
                                    keepers.add(m.keepId());
                                });
                for (Summarise s : b.summaries()) {
                    removed.addAll(s.sourceIds());
                    List<ItemSnapshot> src = s.sourceIds().stream().map(byId::get).toList();
                    boolean allTrusted =
                            src.stream().allMatch(x -> x.trust() == MemoryTrustLevel.TRUSTED);
                    assertThat(s.trust() == MemoryTrustLevel.TRUSTED).isEqualTo(allTrusted);
                    assertThat(s.generation())
                            .isEqualTo(
                                    src.stream().mapToInt(ItemSnapshot::generation).max().getAsInt()
                                            + 1);
                    assertThat(s.generation()).isLessThanOrEqualTo(props.getMaxGeneration());
                    assertThat(s.sourceIds().size()).isBetween(2, props.getSummaryBatchSize());
                    for (ItemSnapshot x : src) {
                        assertThat(
                                        x.modelGenerated()
                                                        || x.generation() > 0
                                                        || !x.sources().isEmpty()
                                                ? x.humanReconfirmed()
                                                : true)
                                .isTrue();
                    }
                    assertThat(s.importance()).isBetween(0.0, 1.0);
                }
                for (UUID id : removed) {
                    ItemSnapshot x = byId.get(id);
                    assertThat(touched.add(id)).as("seed %d: %s once", seed, id).isTrue();
                    assertThat(x.pinned()).isFalse();
                    assertThat(x.personAuthored()).isFalse();
                    assertThat(x.status()).isEqualTo(ItemSnapshot.Status.ACTIVE);
                    assertThat(x.createdAt())
                            .isBeforeOrEqualTo(NOW.minus(Duration.ofDays(props.getMinAgeDays())));
                    if (!props.isMixTrustBatches()) {
                        assertThat(x.trust()).isEqualTo(b.trustClass());
                    }
                }
            }
            assertThat(java.util.Collections.disjoint(keepers, touched)).isTrue();
            nonEmpty += p.isEmpty() ? 0 : 1;
            trips += p.breakerTripped() ? 1 : 0;
            merged += merges(p).isEmpty() ? 0 : 1;
            summed += sums(p).isEmpty() ? 0 : 1;
            if (p.breakerTripped()) {
                assertThat(p.mayAutoApply()).isFalse();
            }
        }
        // the generator must actually exercise every operation, or the invariants are vacuous
        assertThat(nonEmpty).isGreaterThan(100);
        assertThat(trips).isGreaterThan(0);
        assertThat(merged).isGreaterThan(0);
        assertThat(summed).isGreaterThan(0);
    }

    @Test
    void roundedDownCapOfSmallSubjectsIsZero() {
        List<ItemSnapshot> items =
                List.of(
                        row(1, MemoryTrustLevel.TRUSTED, 0, 900),
                        row(2, MemoryTrustLevel.TRUSTED, 0.9, 5));
        ConsolidationPlan p = plan(items);
        assertThat(p.removalCap()).isZero();
        assertThat(p.isEmpty()).isTrue();
    }
}
