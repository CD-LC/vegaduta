package com.agenticai.core.agents.turnjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnJournalChainTest {

    private final UUID agent = UUID.randomUUID();
    private final UUID turn = UUID.randomUUID();

    private List<TurnEvent> recorded() {
        InMemoryTurnJournal j = new InMemoryTurnJournal();
        j.append(agent, turn, TurnEventType.INTAKE, TurnHashChain.sha256("hi"), "messageChars=2");
        j.append(agent, turn, TurnEventType.MODEL_CALL, TurnHashChain.sha256("m"), "provider=x");
        j.append(agent, turn, TurnEventType.TOOL_CALL, TurnHashChain.sha256("{}"), "tool=a status=ok");
        j.append(agent, turn, TurnEventType.COMMIT, TurnHashChain.sha256("done"), "status=completed");
        return j.read(agent, turn);
    }

    @Test
    void anUntouchedChainVerifiesAndStartsAtGenesis() {
        List<TurnEvent> events = recorded();
        assertEquals(TurnHashChain.GENESIS, events.get(0).prevHash());
        assertEquals(List.of(0, 1, 2, 3), events.stream().map(TurnEvent::seq).toList());
        assertTrue(TurnHashChain.verify(events).intact());
    }

    @Test
    void anEditedEventIsDetectedAtItsSeq() {
        List<TurnEvent> events = new ArrayList<>(recorded());
        TurnEvent e = events.get(2);
        events.set(
                2,
                new TurnEvent(
                        e.turnId(), e.agentId(), e.seq(), e.type(), e.payloadHash(),
                        "tool=a status=ok edited", e.prevHash(), e.hash(), e.createdAt()));
        TurnHashChain.Verification v = TurnHashChain.verify(events);
        assertFalse(v.intact());
        assertEquals(2, v.brokenAtSeq());
    }

    @Test
    void aRemovedOrReorderedEventIsDetected() {
        List<TurnEvent> removed = new ArrayList<>(recorded());
        removed.remove(1);
        assertFalse(TurnHashChain.verify(removed).intact());

        List<TurnEvent> swapped = new ArrayList<>(recorded());
        Collections.swap(swapped, 1, 2);
        assertFalse(TurnHashChain.verify(swapped).intact());
    }

    @Test
    void aRecomputedHashOnAnEditedEventStillBreaksTheNextLink() {
        List<TurnEvent> events = new ArrayList<>(recorded());
        TurnEvent e = events.get(1);
        String forged = "provider=evil";
        String hash =
                TurnHashChain.eventHash(
                        e.prevHash(), e.turnId(), e.agentId(), e.seq(), e.type(), e.payloadHash(),
                        forged, e.createdAt());
        events.set(
                1,
                new TurnEvent(
                        e.turnId(), e.agentId(), e.seq(), e.type(), e.payloadHash(), forged,
                        e.prevHash(), hash, e.createdAt()));
        TurnHashChain.Verification v = TurnHashChain.verify(events);
        assertFalse(v.intact());
        assertEquals(2, v.brokenAtSeq());
    }

    @Test
    void readIsScopedToTheOwningAgent() {
        InMemoryTurnJournal j = new InMemoryTurnJournal();
        j.append(agent, turn, TurnEventType.INTAKE, "h", "s");
        assertTrue(j.read(UUID.randomUUID(), turn).isEmpty());
        assertEquals(1, j.read(agent, turn).size());
    }

    @Test
    void summariesAreRedactedAndBounded() {
        String s =
                TurnRedaction.summary(
                        "mail jane.doe@example.com Bearer abc.def.ghi sk-ABCDEFGHIJKL1234 card 4111111111111111 "
                                + "x".repeat(400));
        assertFalse(s.contains("jane.doe"));
        assertFalse(s.contains("abc.def"));
        assertFalse(s.contains("ABCDEFGHIJKL"));
        assertFalse(s.contains("4111111111111111"));
        assertTrue(s.length() <= TurnRedaction.MAX_SUMMARY);
    }

    @Test
    void aFailingJournalNeverReachesTheTurn() {
        TurnJournal broken =
                new TurnJournal() {
                    public TurnEvent append(UUID a, UUID t, TurnEventType ty, String p, String s) {
                        throw new IllegalStateException("db down");
                    }

                    public List<TurnEvent> read(UUID a, UUID t) {
                        return List.of();
                    }
                };
        TurnRecorder rec = TurnRecorder.start(broken, agent);
        rec.intake("x", 0, 0);
        rec.commit("y", "completed");
    }
}
