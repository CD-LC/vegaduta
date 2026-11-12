package com.agenticai.core.guardrails.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The conversation-level taint flag and the sender-trust seeding that feeds it. */
class ConversationTaintTest {

    /** In-memory store keyed exactly like the real one: tenant AND conversation. */
    private static final class MemoryStore implements ConversationTaintStore {
        final Set<String> flags = new HashSet<>();

        @Override
        public synchronized boolean isTainted(String tenantId, String conversationId) {
            return flags.contains(tenantId + "|" + conversationId);
        }

        @Override
        public synchronized void markTainted(String tenantId, String conversationId) {
            flags.add(tenantId + "|" + conversationId);
        }
    }

    private static TurnTaint turn(ConversationTaint ct, String tenant, String conversation) {
        TurnTaint taint = new TurnTaint();
        ct.attach(taint, tenant, conversation);
        return taint;
    }

    @Test
    void aTaintedTurnFlagsItsConversationAndTheNextTurnStartsTainted() {
        MemoryStore store = new MemoryStore();
        ConversationTaint ct = new ConversationTaint(store);

        TurnTaint first = turn(ct, "t1", "c1");
        assertFalse(first.isTainted());
        assertTrue(store.flags.isEmpty(), "a clean turn leaves no flag");
        first.mark("fetch_web_page");

        TurnTaint second = turn(ct, "t1", "c1");
        assertTrue(second.isTainted(), "poisoned history is still in the conversation");
        assertEquals(List.of(ConversationTaint.SOURCE), second.sources());
    }

    @Test
    void theFlagDoesNotLeakIntoAnotherConversationOrTenant() {
        MemoryStore store = new MemoryStore();
        ConversationTaint ct = new ConversationTaint(store);
        turn(ct, "t1", "c1").mark("fetch_web_page");

        assertFalse(turn(ct, "t1", "c2").isTainted(), "other conversation");
        assertFalse(turn(ct, "t2", "c1").isTainted(), "same id, other tenant");
    }

    @Test
    void aMarkFromAnotherThreadIsPersistedWithoutAnyThreadLocalTenant() throws Exception {
        MemoryStore store = new MemoryStore();
        ConversationTaint ct = new ConversationTaint(store);
        TurnTaint first = turn(ct, "t1", "c1");

        Thread tool = new Thread(() -> first.mark("fetch_web_page"));
        tool.start();
        tool.join();

        assertTrue(turn(ct, "t1", "c1").isTainted());
    }

    @Test
    void anAlreadyTaintedTurnIsPersistedAtAttach() {
        MemoryStore store = new MemoryStore();
        ConversationTaint ct = new ConversationTaint(store);
        TurnTaint inbound = new TurnTaint();
        inbound.mark("inbound:whatsapp");
        ct.attach(inbound, "t1", "c1");

        assertTrue(turn(ct, "t1", "c1").isTainted());
    }

    @Test
    void anUnreadableFlagTaintsTheTurn() {
        ConversationTaint ct =
                new ConversationTaint(
                        new ConversationTaintStore() {
                            @Override
                            public boolean isTainted(String t, String c) {
                                throw new IllegalStateException("redis down");
                            }

                            @Override
                            public void markTainted(String t, String c) {}
                        });
        TurnTaint taint = turn(ct, "t1", "c1");
        assertTrue(taint.isTainted());
        assertEquals(List.of(ConversationTaint.SOURCE_UNKNOWN), taint.sources());
    }

    @Test
    void anUnwritableFlagNeverBreaksMarking() {
        ConversationTaint ct =
                new ConversationTaint(
                        new ConversationTaintStore() {
                            @Override
                            public boolean isTainted(String t, String c) {
                                return false;
                            }

                            @Override
                            public void markTainted(String t, String c) {
                                throw new IllegalStateException("redis down");
                            }
                        });
        TurnTaint taint = turn(ct, "t1", "c1");
        assertTrue(taint.mark("x"));
        assertTrue(taint.isTainted());
    }

    @Test
    void listenersRunOncePerTurn() {
        TurnTaint taint = new TurnTaint();
        AtomicInteger runs = new AtomicInteger();
        taint.onTainted(runs::incrementAndGet);
        taint.mark("a");
        taint.mark("b");
        assertEquals(1, runs.get());
    }

    @Test
    void onlyOwnersAndMembersAreTrustedAndUnknownFailsClosed() {
        assertEquals(SenderTrust.TRUSTED, SenderTrust.ofOwnerOrMember(true));
        assertEquals(SenderTrust.UNTRUSTED, SenderTrust.ofOwnerOrMember(false));
        assertEquals(SenderTrust.UNTRUSTED, SenderTrust.ofOwnerOrMember(null));

        TurnTaint trusted = new TurnTaint();
        assertFalse(SenderTrust.TRUSTED.seed(trusted, "inbound:x"));
        assertFalse(trusted.isTainted());

        TurnTaint untrusted = new TurnTaint();
        assertTrue(SenderTrust.UNTRUSTED.seed(untrusted, "inbound:x"));
        assertTrue(untrusted.isTainted());
    }
}
