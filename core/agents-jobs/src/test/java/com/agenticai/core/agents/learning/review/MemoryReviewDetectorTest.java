package com.agenticai.core.agents.learning.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.learning.review.MemoryReviewDetector.Finding;
import com.agenticai.core.agents.learning.review.MemoryReviewDetector.Settings;
import com.agenticai.core.agents.learning.review.MemoryReviewDetector.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryReviewDetectorTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final UUID AGENT = UUID.randomUUID();

    private static MemoryFactRef agentFact(String text, int createdDaysAgo, int lastUsedDaysAgo) {
        return fact(MemoryFactRef.AGENT, AGENT, text, createdDaysAgo, lastUsedDaysAgo);
    }

    private static MemoryFactRef fact(String scope, UUID agent, String text, int createdDaysAgo, int lastUsedDaysAgo) {
        Instant created = NOW.minus(Duration.ofDays(createdDaysAgo));
        Instant used = NOW.minus(Duration.ofDays(lastUsedDaysAgo));
        return new MemoryFactRef(scope, UUID.randomUUID(), agent, text, created, used.isAfter(created) ? used : created);
    }

    private static List<Finding> detect(MemoryFactRef... facts) {
        return MemoryReviewDetector.detect(List.of(facts), NOW, Settings.defaults());
    }

    @Test
    void nearDuplicatesKeepTheMoreInformativeWording() {
        MemoryFactRef shorter = agentFact("Prefers dark mode in every app", 5, 1);
        MemoryFactRef longer = agentFact("Prefers dark mode in every app, including the terminal", 3, 1);
        List<Finding> found = detect(shorter, longer);
        assertEquals(1, found.size());
        assertEquals(Type.DUPLICATE, found.get(0).type());
        assertEquals(longer.id(), found.get(0).keep().id());
        assertEquals(List.of(shorter.id()), found.get(0).dropped().stream().map(MemoryFactRef::id).toList());
    }

    @Test
    void sameWordsWithDifferentCaseAndPluralsAreDuplicates() {
        List<Finding> found = detect(agentFact("Likes short answers", 4, 1), agentFact("likes short answer", 2, 1));
        assertEquals(1, found.size());
        assertEquals(Type.DUPLICATE, found.get(0).type());
    }

    @Test
    void unrelatedFactsAreLeftAlone() {
        assertTrue(detect(agentFact("Works in the Bangalore office", 4, 1), agentFact("Prefers dark mode everywhere", 2, 1)).isEmpty());
    }

    @Test
    void aNegatedRestatementIsAContradictionNotADuplicate() {
        MemoryFactRef old = agentFact("Likes spicy food", 20, 1);
        MemoryFactRef newer = agentFact("Does not like spicy food", 2, 1);
        List<Finding> found = detect(old, newer);
        assertEquals(1, found.size());
        assertEquals(Type.CONTRADICTION, found.get(0).type());
        assertEquals(newer.id(), found.get(0).keep().id(), "the newer note wins");
        assertEquals(old.id(), found.get(0).dropped().get(0).id());
    }

    @Test
    void contractedNegationsCount() {
        List<Finding> found = detect(agentFact("Drinks coffee in the morning", 20, 1), agentFact("Doesn't drink coffee in the morning", 1, 1));
        assertEquals(Type.CONTRADICTION, found.get(0).type());
    }

    @Test
    void sameStatementWithADifferentNumberIsAContradiction() {
        MemoryFactRef old = agentFact("The office is on floor 3", 30, 1);
        MemoryFactRef newer = agentFact("The office is on floor 5", 3, 1);
        List<Finding> found = detect(old, newer);
        assertEquals(Type.CONTRADICTION, found.get(0).type());
        assertEquals(newer.id(), found.get(0).keep().id());
    }

    @Test
    void factsDifferingOnlyByANumberAreNeverMergedAsDuplicates() {
        List<Finding> found = detect(agentFact("Standup is at 9", 10, 1), agentFact("Standup is at 10", 2, 1));
        assertTrue(found.stream().noneMatch(f -> f.type() == Type.DUPLICATE));
    }

    @Test
    void aFactNobodyUsedForLongIsOfferedForForgetting() {
        MemoryFactRef old = agentFact("Was learning Rust last spring", 200, 150);
        MemoryFactRef fresh = agentFact("Allergic to peanuts", 200, 3);
        List<Finding> found = detect(old, fresh);
        assertEquals(1, found.size());
        assertEquals(Type.STALE, found.get(0).type());
        assertNull(found.get(0).keep());
        assertEquals(old.id(), found.get(0).dropped().get(0).id());
    }

    @Test
    void aRecentlyUsedOldFactIsNotStale() {
        assertTrue(detect(agentFact("Allergic to peanuts", 400, 2)).isEmpty());
    }

    @Test
    void aBrandNewFactIsNotStaleEvenIfNeverRecalled() {
        assertTrue(detect(agentFact("Moved to a new flat", 3, 3)).isEmpty());
    }

    @Test
    void factsInDifferentTiersAreNeverCompared() {
        UUID other = UUID.randomUUID();
        MemoryFactRef a = fact(MemoryFactRef.AGENT, AGENT, "Prefers dark mode in every app", 5, 1);
        MemoryFactRef b = fact(MemoryFactRef.AGENT, other, "Prefers dark mode in every app", 5, 1);
        MemoryFactRef c = fact(MemoryFactRef.SHARED, AGENT, "Prefers dark mode in every app", 5, 1);
        assertTrue(detect(a, b, c).isEmpty());
        MemoryFactRef d = fact(MemoryFactRef.SHARED, other, "Prefers dark mode in every app", 4, 1);
        List<Finding> shared = detect(a, c, d);
        assertEquals(1, shared.size(), "the two shared notes are one tier even though different agents wrote them");
        assertEquals(MemoryFactRef.SHARED, shared.get(0).keep().scope());
    }

    @Test
    void aFactIsInAtMostOneFinding() {
        MemoryFactRef a = agentFact("Prefers dark mode in every app", 9, 1);
        MemoryFactRef b = agentFact("Prefers dark mode in every app", 8, 1);
        MemoryFactRef c = agentFact("Does not prefer dark mode in every app", 1, 1);
        List<Finding> found = detect(a, b, c);
        List<UUID> ids = new ArrayList<>();
        for (Finding f : found) {
            if (f.keep() != null) {
                ids.add(f.keep().id());
            }
            f.dropped().forEach(d -> ids.add(d.id()));
        }
        assertEquals(ids.size(), ids.stream().distinct().count());
    }

    @Test
    void theNumberOfFindingsIsCapped() {
        List<MemoryFactRef> facts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            facts.add(agentFact("Topic" + i + "alpha topic" + i + "beta stale remark", 300, 250));
        }
        Settings s = new Settings(0.8, 0.5, 60, 2, 3);
        assertEquals(2, MemoryReviewDetector.detect(facts, NOW, s).size());
    }
}
