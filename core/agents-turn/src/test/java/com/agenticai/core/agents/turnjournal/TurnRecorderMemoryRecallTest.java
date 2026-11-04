package com.agenticai.core.agents.turnjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The recall trace is journaled as ids and counts, across as many events as the summary column needs. */
class TurnRecorderMemoryRecallTest {

    private final UUID agent = UUID.randomUUID();

    @Test
    void countsTokensAndIdsAreWrittenAndNothingElse() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnRecorder rec = TurnRecorder.start(journal, agent, UUID.randomUUID(), "s");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        rec.memoryRecall(true, 1, 1, 2, 123, List.of(a, b));

        List<TurnEvent> events = journal.read(agent, rec.turnId());
        assertEquals(1, events.size());
        assertEquals(TurnEventType.GUARD, events.get(0).type());
        String summary = events.get(0).summary();
        assertTrue(summary.startsWith("guard=memory_recall enabled=true private=1 shared=1 blocks=2 approxTokens=123"), summary);
        assertTrue(summary.contains(a + "," + b), summary);
    }

    @Test
    void aSwitchedOffRecallIsRecordedAsDisabled() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnRecorder rec = TurnRecorder.start(journal, agent, UUID.randomUUID(), "s");

        rec.memoryRecall(false, 0, 0, 0, 0, List.of());

        String summary = journal.read(agent, rec.turnId()).get(0).summary();
        assertTrue(summary.contains("enabled=false"), summary);
        assertTrue(!summary.contains("ids="), summary);
    }

    @Test
    void manyIdsContinueInFollowUpEventsAndEveryIdSurvives() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnRecorder rec = TurnRecorder.start(journal, agent, UUID.randomUUID(), "s");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            ids.add(UUID.randomUUID());
        }

        rec.memoryRecall(true, 7, 7, 0, 900, ids);

        List<TurnEvent> events = journal.read(agent, rec.turnId());
        assertEquals(3, events.size());
        String all = String.join(" ", events.stream().map(TurnEvent::summary).toList());
        for (UUID id : ids) {
            assertTrue(all.contains(id.toString()), id.toString());
        }
        events.forEach(e -> assertTrue(e.summary().length() < 512));
    }

    @Test
    void theNoopRecorderWritesNothing() {
        TurnRecorder.NOOP.memoryRecall(true, 1, 0, 0, 1, List.of(UUID.randomUUID()));
    }
}
