package com.agenticai.core.agents.learning.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.consolidation.ConsolidationFactSource.Fact;
import com.agenticai.core.agents.memory.MemoryTrust;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan;
import com.agenticai.core.agents.memorystore.decay.DecayProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * WP5c: real importance for the legacy facts, and consolidation of {@code memory_item} rows whose
 * merges and summaries are filed as proposals (also when the breaker trips or apply is off), never
 * applied by the job.
 */
class MemoryConsolidationItemsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate DAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    private final UUID agent = UUID.randomUUID();
    private FakeFacts facts;
    private FakeItems items;
    private MemoryConsolidationRunRepository runs;
    private MemoryTombstoneWriter tombstones;
    private ConsolidationProposalFiler filer;

    @BeforeEach
    void setUp() {
        facts = new FakeFacts();
        items = new FakeItems();
        runs = mock(MemoryConsolidationRunRepository.class);
        tombstones = mock(MemoryTombstoneWriter.class);
        filer = mock(ConsolidationProposalFiler.class);
        when(runs.claim(anyString(), any(LocalDate.class))).thenReturn(1);
        // Consolidation has been on for a month: the dry-run window is over.
        when(runs.firstRunDate()).thenReturn(Optional.of(DAY.minusDays(30)));
        when(filer.fileMerge(any(), anyMap())).thenReturn(1);
        when(filer.fileSummary(any(), anyMap())).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DecayProperties> noDecayBean() {
        ObjectProvider<DecayProperties> p = mock(ObjectProvider.class);
        when(p.getIfAvailable(any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        return p;
    }

    private MemoryConsolidationService service(boolean apply) {
        MemoryConsolidationService s =
                new MemoryConsolidationService(
                        facts, items, runs, tombstones, filer, noDecayBean(), apply, 200);
        s.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return s;
    }

    private static Instant ago(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    // ------------------------------------------------------------------ legacy facts: real
    // importance

    private Fact fact(
            String content,
            int ageDays,
            int idleDays,
            int uses,
            double importance,
            boolean pinned,
            boolean person) {
        return new Fact(
                Fact.AGENT,
                UUID.randomUUID(),
                agent,
                content,
                MemoryTrust.TRUSTED,
                uses,
                ago(ageDays),
                ago(idleDays),
                importance,
                pinned,
                person);
    }

    @Test
    void aStaleNeverUsedUnpinnedTrustedFactReachesTheForgetBandButProtectedOrUsedOnesNeverDo() {
        facts.subjects.add("meera");
        Fact stale = fact("zephyr roadmap question", 400, 400, 0, 0.5, false, false);
        facts.facts.add(stale);
        facts.facts.add(fact("pinned zephyr preference", 400, 400, 0, 0.5, true, false));
        facts.facts.add(fact("typed quokka address", 400, 400, 0, 0.8, false, true));
        facts.facts.add(fact("used heavily nutmeg order", 400, 300, 25, 0.5, false, false));
        facts.facts.add(fact("recently used walnut tip", 400, 2, 1, 0.5, false, false));
        for (int i = 0; i < 10; i++) {
            facts.facts.add(fact("fresh " + UUID.randomUUID(), 1, 1, 0, 0.5, false, false));
        }

        MemoryConsolidationService.Report r = service(true).consolidateTenant();

        assertEquals(
                1, r.forgets(), "only the stale, never-used, unpinned, not person-authored fact");
        assertEquals(1, r.tombstoned());
        verify(tombstones, times(1))
                .markForgotten(eq("meera"), eq(stale), eq("DECAY"), any(), eq(NOW));
    }

    @Test
    void aTombstoneIsNeverWrittenWhileAnotherLiveRowHoldsTheSameText() {
        // A tombstone hides every row with that text. An exact-duplicate "merge drop", and a stale
        // copy of a text that is
        // also in recent use, must not be tombstoned or the survivor would vanish from recall too.
        facts.subjects.add("meera");
        facts.facts.add(fact("meera likes espresso coffee", 30, 30, 0, 0.5, false, false));
        facts.facts.add(fact("meera likes espresso coffee", 31, 31, 0, 0.5, false, false));
        facts.facts.add(fact("walnut tip shared by two", 400, 400, 0, 0.5, false, false));
        facts.facts.add(fact("walnut tip shared by two", 3, 1, 4, 0.5, false, false));
        for (int i = 0; i < 12; i++) {
            facts.facts.add(fact("fresh " + UUID.randomUUID(), 1, 1, 0, 0.5, false, false));
        }

        MemoryConsolidationService.Report r = service(true).consolidateTenant();

        assertEquals(0, r.tombstoned());
        verify(tombstones, never()).markForgotten(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void trustedForgetsStayBehindAutoApply() {
        facts.subjects.add("meera");
        facts.facts.add(fact("zephyr roadmap question", 400, 400, 0, 0.5, false, false));
        for (int i = 0; i < 10; i++) {
            facts.facts.add(fact("fresh " + UUID.randomUUID(), 1, 1, 0, 0.5, false, false));
        }
        MemoryConsolidationService.Report r = service(false).consolidateTenant();
        assertEquals(1, r.forgets(), "planned and reported");
        assertEquals(0, r.tombstoned(), "but not applied without auto-apply");
        verify(tombstones, never()).markForgotten(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void theCircuitBreakerStillCapsABulkTrustedForget() {
        facts.subjects.add("meera");
        for (int i = 0; i < 10; i++) {
            facts.facts.add(
                    fact(
                            "stale fact number " + i + " " + UUID.randomUUID(),
                            400,
                            400,
                            0,
                            0.5,
                            false,
                            false));
        }
        MemoryConsolidationService.Report r = service(true).consolidateTenant();
        assertEquals(3, r.forgets(), "30% of 10 rows");
        assertEquals(0, r.tombstoned(), "a tripped breaker applies nothing");
    }

    // ------------------------------------------------------------------ memory_item rows

    private ConsolidationItemSource.Item item(
            MemoryKind kind, String content, int ageDays, double importance, boolean pinned) {
        return new ConsolidationItemSource.Item(
                UUID.randomUUID(),
                agent,
                kind,
                content,
                0,
                true,
                true,
                false,
                pinned,
                importance,
                0,
                ago(ageDays),
                null,
                ago(ageDays));
    }

    private void fresh(int n) {
        for (int i = 0; i < n; i++) {
            items.items.add(
                    item(MemoryKind.EPISODIC, "fresh episode " + UUID.randomUUID(), 2, 0.5, false));
        }
    }

    @Test
    void duplicateItemsAreFiledAsAMergeProposalNotAppliedEvenWithAutoApply() {
        ConsolidationItemSource.Item keep =
                item(
                        MemoryKind.PROCEDURAL,
                        "alice checks espresso machine settings",
                        40,
                        0.5,
                        false);
        ConsolidationItemSource.Item dup =
                item(
                        MemoryKind.PROCEDURAL,
                        "alice checks espresso machine settings twice daily",
                        41,
                        0.5,
                        false);
        items.items.add(keep);
        items.items.add(dup);
        fresh(8);

        MemoryConsolidationService.Report r = service(true).consolidateTenant();

        assertEquals(1, r.merges());
        assertEquals(1, r.proposals());
        verify(filer, times(1)).fileMerge(any(ConsolidationPlan.Merge.class), anyMap());
        verify(tombstones, never()).markForgotten(anyString(), any(), anyString(), any(), any());
        verify(runs)
                .record(
                        eq("item:" + agent),
                        eq(DAY),
                        eq("apply"),
                        eq("completed"),
                        eq(10),
                        eq(0),
                        eq(1),
                        eq(0),
                        anyInt(),
                        eq(0),
                        eq(1));
    }

    @Test
    void aMergeIsNeverFiledAcrossKinds() {
        items.items.add(
                item(
                        MemoryKind.PROCEDURAL,
                        "alice checks espresso machine settings",
                        40,
                        0.5,
                        false));
        items.items.add(
                item(
                        MemoryKind.EPISODIC,
                        "alice checks espresso machine settings twice daily",
                        41,
                        0.5,
                        false));
        fresh(8);
        MemoryConsolidationService.Report r = service(false).consolidateTenant();
        assertEquals(0, r.merges());
        verify(filer, never()).fileMerge(any(), anyMap());
    }

    @Test
    void staleItemsInTheSummariseBandAreFiledAsASummaryProposal() {
        items.items.add(
                item(
                        MemoryKind.EPISODIC,
                        "episode about the quarterly roadmap meeting",
                        50,
                        0.5,
                        false));
        items.items.add(
                item(
                        MemoryKind.EPISODIC,
                        "episode about the annual budget workshop",
                        52,
                        0.5,
                        false));
        fresh(10);

        MemoryConsolidationService.Report r = service(false).consolidateTenant();

        assertEquals(1, r.summaries());
        assertEquals(1, r.proposals());
        ArgumentCaptor<ConsolidationPlan.Summarise> s =
                ArgumentCaptor.forClass(ConsolidationPlan.Summarise.class);
        verify(filer).fileSummary(s.capture(), anyMap());
        assertEquals(2, s.getValue().sourceIds().size());
        assertEquals(agent, s.getValue().agentId());
        verify(tombstones, never()).markForgotten(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void proposalsAreFiledWhenTheBreakerTripsAndNothingIsApplied() {
        // 20 items, cap 6. Four forgettable (importance 0.01, idle 300 days), then three duplicate
        // pairs: the first two
        // merges fit under the cap, the third trips the breaker. mayAutoApply is false, so no
        // tombstones; the merges
        // that fit are still filed for a person.
        for (int i = 0; i < 4; i++) {
            items.items.add(
                    item(
                            MemoryKind.PROCEDURAL,
                            "forgettable zx" + i + " qv" + i,
                            300,
                            0.01,
                            false));
        }
        String[][] pairs = {
            {"alice likes espresso coffee", "alice likes espresso coffee very much"},
            {"bob prefers hindi movies", "bob prefers hindi movies on weekends"},
            {"carol drives red scooter", "carol drives red scooter daily"}
        };
        for (String[] p : pairs) {
            items.items.add(item(MemoryKind.PROCEDURAL, p[0], 40, 0.5, false));
            items.items.add(item(MemoryKind.PROCEDURAL, p[1], 41, 0.5, false));
        }
        fresh(10);

        MemoryConsolidationService.Report r = service(true).consolidateTenant();

        assertEquals(4, r.forgets());
        assertEquals(0, r.tombstoned(), "the breaker tripped: nothing applied");
        assertEquals(2, r.proposals(), "the two merges that fit are filed");
        verify(filer, times(2)).fileMerge(any(), anyMap());
        verify(tombstones, never()).markForgotten(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void anItemAlreadyFeedingAPendingProposalIsNotFiledAgain() {
        ConsolidationItemSource.Item keep =
                item(
                        MemoryKind.PROCEDURAL,
                        "alice checks espresso machine settings",
                        40,
                        0.5,
                        false);
        ConsolidationItemSource.Item dup =
                item(
                        MemoryKind.PROCEDURAL,
                        "alice checks espresso machine settings twice daily",
                        41,
                        0.5,
                        false);
        items.items.add(keep);
        items.items.add(dup);
        fresh(8);
        items.pending.add(dup.id());

        MemoryConsolidationService.Report r = service(false).consolidateTenant();

        assertEquals(1, r.merges(), "still planned and reported");
        assertEquals(0, r.proposals());
        verify(filer, never()).fileMerge(any(), anyMap());
    }

    @Test
    void pinnedAndPersonAuthoredItemsAreNeverPlanned() {
        items.items.add(
                item(
                        MemoryKind.EPISODIC,
                        "pinned episode about the roadmap meeting",
                        50,
                        0.5,
                        true));
        items.items.add(
                item(
                        MemoryKind.EPISODIC,
                        "pinned episode about the budget workshop",
                        52,
                        0.5,
                        true));
        ConsolidationItemSource.Item typed =
                new ConsolidationItemSource.Item(
                        UUID.randomUUID(),
                        agent,
                        MemoryKind.EPISODIC,
                        "typed episode about the hiring plan",
                        0,
                        false,
                        true,
                        true,
                        false,
                        0.01,
                        0,
                        ago(300),
                        null,
                        ago(300));
        items.items.add(typed);
        fresh(10);
        MemoryConsolidationService.Report r = service(true).consolidateTenant();
        assertEquals(0, r.forgets());
        assertEquals(0, r.summaries());
        assertEquals(0, r.merges());
    }

    @Test
    void anItemDayIsClaimedOnceAndAFailureReleasesIt() {
        items.items.add(item(MemoryKind.EPISODIC, "an episode", 40, 0.5, false));
        when(runs.claim("item:" + agent, DAY)).thenReturn(0);
        assertNull(service(false).consolidateAgentItems(agent, DAY));
        when(runs.claim("item:" + agent, DAY)).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(runs)
                .record(
                        eq("item:" + agent),
                        eq(DAY),
                        anyString(),
                        anyString(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt());
        try {
            service(false).consolidateAgentItems(agent, DAY);
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("boom"));
        }
        verify(runs).release("item:" + agent, DAY);
    }

    @Test
    void withoutAnItemSourceOnlyLegacyFactsAreConsolidated() {
        MemoryConsolidationService s =
                new MemoryConsolidationService(facts, runs, tombstones, noDecayBean(), false, 200);
        s.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        MemoryConsolidationService.Report r = s.consolidateTenant();
        assertEquals(0, r.subjects());
        assertEquals(0, r.proposals());
    }

    private static final class FakeFacts implements ConsolidationFactSource {
        final List<String> subjects = new ArrayList<>();
        final List<Fact> facts = new ArrayList<>();

        @Override
        public List<String> subjects(int limit) {
            return subjects;
        }

        @Override
        public List<Fact> facts(String subjectKey) {
            return facts;
        }
    }

    private final class FakeItems implements ConsolidationItemSource {
        final List<Item> items = new ArrayList<>();
        final Set<UUID> pending = new HashSet<>();

        @Override
        public List<UUID> agents(int limit) {
            return items.isEmpty() ? List.of() : List.of(agent);
        }

        @Override
        public List<Item> items(UUID agentId) {
            return items;
        }

        @Override
        public Set<UUID> sourcesOfPendingProposals(UUID agentId) {
            return pending;
        }
    }
}
