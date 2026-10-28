package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.taint.TurnTaint;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Conversation-level taint: a tainted turn leaves a marker that seeds the next turn. */
class SessionTaintStoreTest {

    private static final String SESSION = "c1:abc";

    private final Map<String, String> redisData = new HashMap<>();
    private StringRedisTemplate redis;
    private AuditLogService auditLog;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> redisData.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            redisData.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(inv -> redisData.remove(inv.<String>getArgument(0)) != null);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        auditLog = mock(AuditLogService.class);
    }

    @SuppressWarnings("unchecked")
    private SessionTaintStore store(String mode, boolean persistence) {
        ObjectProvider<AuditLogService> audit = mock(ObjectProvider.class);
        when(audit.getIfAvailable()).thenReturn(auditLog);
        return new SessionTaintStore(redis, mode, persistence, 24, audit);
    }

    @Test
    void taintFromOneTurnSeedsTheNextTurnOfTheSameSession() {
        SessionTaintStore store = store("observe", true);

        TurnTaint turn1 = new TurnTaint();
        store.seedAndTrack(turn1, SESSION);
        assertFalse(turn1.isTainted(), "a fresh session starts clean");
        turn1.mark("fetch_web_page"); // e.g. a tool result, marked mid-turn

        TurnTaint turn2 = new TurnTaint();
        store.seedAndTrack(turn2, SESSION);

        assertTrue(turn2.isTainted(), "the poisoned reply is in the history, so the next turn is tainted");
        assertTrue(turn2.sources().contains("fetch_web_page"));
        assertTrue(turn2.sources().contains(SessionTaintStore.SESSION_SOURCE));
    }

    @Test
    void anotherSessionIsNotAffected() {
        SessionTaintStore store = store("observe", true);
        TurnTaint turn1 = new TurnTaint();
        store.seedAndTrack(turn1, SESSION);
        turn1.mark("fetch_web_page");

        TurnTaint other = new TurnTaint();
        store.seedAndTrack(other, "c1:other");

        assertFalse(other.isTainted());
    }

    @Test
    void marksMadeBeforeTrackingStartsArePersistedToo() {
        SessionTaintStore store = store("observe", true);
        TurnTaint turn = new TurnTaint();
        turn.mark("inbound:telegram"); // an adapter marks before the pipeline runs

        store.seedAndTrack(turn, SESSION);

        TurnTaint next = new TurnTaint();
        store.seedAndTrack(next, SESSION);
        assertTrue(next.sources().contains("inbound:telegram"));
    }

    @Test
    void clearedMarkerStopsSeedingAndIsAudited() {
        SessionTaintStore store = store("observe", true);
        TurnTaint turn1 = new TurnTaint();
        store.seedAndTrack(turn1, SESSION);
        turn1.mark("fetch_web_page");

        assertTrue(store.clear(SESSION, "alice", "agent/sess"));

        TurnTaint turn2 = new TurnTaint();
        store.seedAndTrack(turn2, SESSION);
        assertFalse(turn2.isTainted(), "an explicitly cleared session starts clean");
        verify(auditLog)
                .record(eq("alice"), eq("session_taint_cleared"), eq("chat_session"), eq("agent/sess"),
                        eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    void markerKeepsFirstTaintedAtAndBoundsItsSources() {
        SessionTaintStore store = store("observe", true);
        store.record(SESSION, List.of("a"));
        SessionTaintStore.Marker first = store.load(SESSION).orElseThrow();
        for (int i = 0; i < 20; i++) {
            store.record(SESSION, List.of("src" + i));
        }
        SessionTaintStore.Marker later = store.load(SESSION).orElseThrow();

        assertEquals(first.firstTaintedAt(), later.firstTaintedAt());
        assertEquals(SessionTaintStore.MAX_SOURCES, later.sources().size());
        assertEquals("a", later.sources().get(0));
    }

    @Test
    void seededTurnDoesNotRewriteAnUnchangedMarker() {
        SessionTaintStore store = store("observe", true);
        store.record(SESSION, List.of("fetch_web_page"));
        String before = redisData.get(SessionTaintStore.key(SESSION));

        TurnTaint turn = new TurnTaint();
        store.seedAndTrack(turn, SESSION);

        assertEquals(before, redisData.get(SessionTaintStore.key(SESSION)));
    }

    @Test
    void inertWhenTheTaintLayerIsOff() {
        SessionTaintStore store = store("off", true);
        TurnTaint turn1 = new TurnTaint();
        store.seedAndTrack(turn1, SESSION);
        turn1.mark("fetch_web_page");

        assertTrue(redisData.isEmpty());
        assertFalse(store.enabled());
    }

    @Test
    void inertWhenPersistenceIsSwitchedOff() {
        SessionTaintStore store = store("enforce", false);
        TurnTaint turn1 = new TurnTaint();
        store.seedAndTrack(turn1, SESSION);
        turn1.mark("fetch_web_page");

        TurnTaint turn2 = new TurnTaint();
        store.seedAndTrack(turn2, SESSION);
        assertFalse(turn2.isTainted());
        assertTrue(redisData.isEmpty());
    }

    @Test
    void redisFailureNeverBreaksTheTurn() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        SessionTaintStore store = store("observe", true);

        TurnTaint turn = new TurnTaint();
        store.seedAndTrack(turn, SESSION);
        turn.mark("fetch_web_page");

        assertTrue(turn.isTainted(), "the turn keeps its own taint");
    }
}
