package com.agenticai.core.agents.turnjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A journaled turn can be found by its chat session and by an id the caller was handed before the turn ran. */
class TurnJournalSessionTest {

    private final UUID agent = UUID.randomUUID();

    @AfterEach
    void reset() {
        TurnJournalGateway.install(null, false);
    }

    private UUID turn(InMemoryTurnJournal journal, String session, String commitStatus) {
        TurnRecorder rec = TurnRecorder.start(journal, agent, UUID.randomUUID(), session);
        rec.intake("hello", 0, 0);
        if (commitStatus != null) {
            rec.commit("answer", commitStatus);
        }
        return rec.turnId();
    }

    @Test
    void theRecorderKeepsTheTurnIdItWasGiven() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        UUID given = UUID.randomUUID();

        TurnRecorder rec = TurnRecorder.start(journal, agent, given, "s");

        assertEquals(given, rec.turnId());
    }

    @Test
    void turnsOfASessionAreListedNewestFirstWithTheirFinalStatus() throws InterruptedException {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        UUID first = turn(journal, "sess-A", "completed");
        Thread.sleep(5);
        UUID second = turn(journal, "sess-A", "error:TimeoutException");
        Thread.sleep(5);
        UUID openTurn = turn(journal, "sess-A", null);
        turn(journal, "sess-B", "completed");

        List<TurnSummary> listed = journal.listBySession(agent, "sess-A", 10);

        assertEquals(List.of(openTurn, second, first), listed.stream().map(TurnSummary::turnId).toList());
        assertEquals(
                List.of(TurnSummary.OPEN, "error:TimeoutException", "completed"),
                listed.stream().map(TurnSummary::status).toList());
        assertEquals(2, listed.get(2).events(), "an INTAKE and a COMMIT");
        assertEquals(2, journal.listBySession(agent, "sess-A", 2).size(), "the limit applies");
    }

    @Test
    void anotherAgentDoesNotSeeTheSession() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        turn(journal, "sess-A", "completed");

        assertTrue(journal.listBySession(UUID.randomUUID(), "sess-A", 10).isEmpty());
        assertTrue(journal.listBySession(agent, "no-such-session", 10).isEmpty());
        assertTrue(journal.listBySession(agent, null, 10).isEmpty());
    }

    @Test
    void theIntakeSummaryCarriesADigestOfTheSessionNotTheSessionItself() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        UUID turnId = turn(journal, "client-chosen-session-id", null);

        TurnEvent intake = journal.read(agent, turnId).get(0);

        String digest = TurnHashChain.sha256("client-chosen-session-id").substring(0, 12);
        assertTrue(intake.summary().contains("session=" + digest), intake.summary());
        assertFalse(intake.summary().contains("client-chosen-session-id"));
        assertTrue(TurnHashChain.verify(journal.read(agent, turnId)).intact());
    }

    @Test
    void theSubTurnNamesItsParentAndTheIdIsNotMaskedAsAToken() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        UUID parent = UUID.randomUUID();
        TurnRecorder child = TurnRecorder.start(journal, agent, UUID.randomUUID(), "delegated:x");

        child.intake("question", 0, 2, parent);

        String summary = journal.read(agent, child.turnId()).get(0).summary();
        assertTrue(summary.contains("parent=" + parent), summary);
    }

    @Test
    void theGatewayBeginsTheRecorderOfTheGivenTurnOnlyWhileEnabled() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        UUID turnId = UUID.randomUUID();

        TurnJournalGateway.install(journal, false);
        assertFalse(TurnJournalGateway.begin(agent, turnId, "s").active());

        TurnJournalGateway.install(journal, true);
        TurnRecorder rec = TurnJournalGateway.begin(agent, turnId, "s");
        assertTrue(rec.active());
        assertEquals(turnId, rec.turnId());
        assertTrue(TurnJournalGateway.installed());
    }
}
