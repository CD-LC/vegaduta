package com.agenticai.core.agents.learning.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.learning.LearningProposalView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyLearningSummaryTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private static LearningProposalView approved(String kind, int daysAgo) {
        return new LearningProposalView(
                UUID.randomUUID(), null, kind, "t", "b", "{}", "{}", "APPROVED", NOW.minus(Duration.ofDays(daysAgo + 1)), NOW.minus(Duration.ofDays(daysAgo)), true);
    }

    @Test
    void countsOnlyTheLastSevenDaysByKind() {
        WeeklyLearningSummary s =
                WeeklyLearningSummary.compose(
                        List.of(approved("LESSON", 1), approved("LESSON", 3), approved("CONSOLIDATION", 6), approved("PREFERENCE", 8)), 2, NOW);
        assertEquals(3, s.approved());
        assertEquals(2, s.approvedByKind().get("LESSON"));
        assertEquals(1, s.tidied());
        assertEquals(2, s.pending());
        assertTrue(s.text().startsWith("This week I learned 3 things"));
        assertTrue(s.text().endsWith("2 suggestions are waiting for you."));
    }

    @Test
    void anEmptyWeekSaysSo() {
        WeeklyLearningSummary s = WeeklyLearningSummary.compose(List.of(), 0, NOW);
        assertEquals(0, s.approved());
        assertEquals("Nothing new was added to what I know this week.", s.text());
    }

    @Test
    void singularWording() {
        WeeklyLearningSummary s = WeeklyLearningSummary.compose(List.of(approved("LESSON", 0)), 1, NOW);
        assertTrue(s.text().contains("1 thing"));
        assertTrue(s.text().contains("1 suggestion is waiting"));
    }
}
