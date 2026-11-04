package com.agenticai.core.agents.learning.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.consolidation.ConsolidationFactSource.Fact;
import com.agenticai.core.agents.memory.MemoryTrust;
import com.agenticai.core.agents.memorystore.decay.DecayProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The nightly consolidation plans and reports; it only ever marks tombstones, and only once earned. */
class MemoryConsolidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate DAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    private FakeFacts facts;
    private MemoryConsolidationRunRepository runs;
    private MemoryTombstoneWriter tombstones;

    @BeforeEach
    void setUp() {
        facts = new FakeFacts();
        runs = mock(MemoryConsolidationRunRepository.class);
        tombstones = mock(MemoryTombstoneWriter.class);
        when(runs.claim(anyString(), any(LocalDate.class))).thenReturn(1);
        when(runs.firstRunDate()).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DecayProperties> noDecayBean() {
        ObjectProvider<DecayProperties> p = mock(ObjectProvider.class);
        when(p.getIfAvailable(any(Supplier.class))).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        return p;
    }

    private MemoryConsolidationService service(boolean apply) {
        MemoryConsolidationService s =
                new MemoryConsolidationService(facts, runs, tombstones, noDecayBean(), apply, 200);
        s.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return s;
    }

    private static Fact fact(MemoryTrust trust, int ageDays, String content) {
        return fact(trust, ageDays, content, UUID.randomUUID());
    }

    private static Fact fact(MemoryTrust trust, int ageDays, String content, UUID agentId) {
        return new Fact(
                Fact.AGENT,
                UUID.randomUUID(),
                agentId,
                content,
                trust,
                0,
                NOW.minus(java.time.Duration.ofDays(ageDays)),
                null);
    }

    @Test
    void claimsEachSubjectOncePerDay() {
        facts.subjects.add("meera");
        facts.facts.add(fact(MemoryTrust.TRUSTED, 5, "likes tea"));
        MemoryConsolidationService s = service(false);
        s.consolidateTenant();
        // Second tick same day: claim returns 0, nothing is read or recorded again.
        when(runs.claim("meera", DAY)).thenReturn(0);
        MemoryConsolidationService.Report r = s.consolidateTenant();
        assertEquals(0, r.subjects());
    }

    @Test
    void dryRunReportsButNeverTombstones() {
        facts.subjects.add("meera");
        // Old QUARANTINED row past the 30-day quarantine TTL: a certain forget candidate.
        facts.facts.add(fact(MemoryTrust.QUARANTINED, 60, "attacker text"));
        // Enough live rows that the 30% breaker cap allows the removal.
        for (int i = 0; i < 4; i++) {
            facts.facts.add(fact(MemoryTrust.TRUSTED, 2, "fresh fact " + i));
        }
        MemoryConsolidationService.Report r = service(false).consolidateTenant();
        assertEquals(1, r.subjects());
        assertEquals(5, r.rows());
        assertEquals(1, r.forgets());
        verify(tombstones, never()).markForgotten(anyString(), any(), anyString(), any(), any());
        verify(runs).record(eq("meera"), eq(DAY), eq("dry_run"), eq("completed"), eq(5), eq(1), eq(0), eq(0), anyInt(), eq(0));
    }

    @Test
    void applyStaysDryRunInsideTheDryRunWindow() {
        facts.subjects.add("meera");
        facts.facts.add(fact(MemoryTrust.QUARANTINED, 60, "attacker text"));
        for (int i = 0; i < 4; i++) {
            facts.facts.add(fact(MemoryTrust.TRUSTED, 2, "fresh fact " + i));
        }
        // Consolidation was first enabled 2 days ago; the 7-day dry-run window still holds.
        when(runs.firstRunDate()).thenReturn(Optional.of(DAY.minusDays(2)));
        service(true).consolidateTenant();
        verify(tombstones, never()).markForgotten(anyString(), any(), anyString(), any(), any());
        verify(runs).record(eq("meera"), eq(DAY), eq("dry_run"), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), eq(0));
    }

    @Test
    void applyMarksForgetsAndMergesWithTombstonesOnly() {
        facts.subjects.add("meera");
        Fact old = fact(MemoryTrust.QUARANTINED, 60, "attacker text");
        facts.facts.add(old);
        // A near-duplicate pair in one agent's tier old enough to merge (containment detector).
        UUID agent = UUID.randomUUID();
        facts.facts.add(fact(MemoryTrust.TRUSTED, 30, "meera likes espresso coffee", agent));
        facts.facts.add(fact(MemoryTrust.TRUSTED, 30, "meera likes espresso coffee very much", agent));
        // Fresh rows keep the subject under the breaker cap for exactly two removals.
        facts.facts.add(fact(MemoryTrust.TRUSTED, 2, "works night shifts"));
        facts.facts.add(fact(MemoryTrust.TRUSTED, 2, "prefers hindi"));
        facts.facts.add(fact(MemoryTrust.TRUSTED, 2, "lives in pune"));
        facts.facts.add(fact(MemoryTrust.TRUSTED, 2, "drives a scooter"));
        when(runs.firstRunDate()).thenReturn(Optional.of(DAY.minusDays(30)));

        MemoryConsolidationService.Report r = service(true).consolidateTenant();
        assertEquals(1, r.forgets());
        assertTrue(r.merges() <= 1);
        assertEquals(2, r.tombstoned());
        verify(tombstones).markForgotten(eq("meera"), eq(old), eq("QUARANTINE_TTL"), any(), eq(NOW));
        verify(tombstones).markForgotten(eq("meera"), any(Fact.class),
                org.mockito.ArgumentMatchers.startsWith("MERGED_INTO:"), any(), eq(NOW));
        verify(runs).record(eq("meera"), eq(DAY), eq("apply"), eq("completed"), eq(7), eq(1), eq(1), anyInt(), anyInt(), eq(2));
    }

    @Test
    void aTrippedBreakerFilesNoTombstones() {
        facts.subjects.add("meera");
        // Everything forgettable: 4 TTL-expired rows but the cap allows only one -> breaker trips.
        for (int i = 0; i < 4; i++) {
            facts.facts.add(fact(MemoryTrust.QUARANTINED, 60, "stale " + i));
        }
        when(runs.firstRunDate()).thenReturn(Optional.of(DAY.minusDays(30)));
        service(true).consolidateTenant();
        verify(tombstones, never()).markForgotten(anyString(), any(), anyString(), any(), any());
        verify(runs).record(eq("meera"), eq(DAY), eq("dry_run"), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), eq(0));
    }

    @Test
    void aSubjectFailureReleasesTheClaimAndStopsNothing() {
        facts.subjects.addAll(List.of("bad", "meera"));
        when(runs.claim("bad", DAY)).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(runs)
                .record(eq("bad"), eq(DAY), anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        MemoryConsolidationService.Report r = service(false).consolidateTenant();
        verify(runs).release("bad", DAY);
        assertEquals(1, r.subjects());
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
}
