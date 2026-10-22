package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.function.Consumer;

/**
 * {@link SessionMemoryService}: summarisation when turns leave the replay window, rewind, and the
 * Redis-down paths. Split out of {@link SessionMemoryServiceTest}; see its javadoc for the
 * contract.
 */
class SessionMemoryFoldAndRewindTest extends SessionMemoryServiceTestBase {

    @Test
    void appendInsideTheReplayWindowClaimsNothingAndNeverCallsTheSummariser() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        withTenantContext();
        stubClaim("40", List.of());

        service.appendTurn(SESSION_ID, user("hello"));

        // Nothing has left the window by a full batch yet: the claim script
        // answers "nothing" and the summariser is never touched.
        verify(summariser, never()).foldAsync(anyString(), any(), any(), any(), any());
        // The storage trim still runs - through the marker-aware script so the
        // "summarised-up-to" marker stays pointed at the same turn.
        verifyMarkerAwareTrim();
        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
        verify(redisTemplate).expire(eq(REDIS_KEY), eq(TTL));
    }

    @Test
    void turnsThatLeftTheReplayWindowAreFoldedLongBeforeTheStorageCeiling() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        withTenantContext();
        when(valueOps.getAndExpire(SUMMARY_KEY, TTL)).thenReturn("summary so far");
        // The regression this pins: in an 80-turn session (nowhere near the
        // 400-entry storage trim), the oldest 40 turns are outside the 40-turn
        // replay window. They used to be neither replayed nor summarised; now
        // the claim script hands them over as one batch.
        stubClaim("40", storedJson(40));

        service.appendTurn(SESSION_ID, user("the 80th entry"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> leaving =
                ArgumentCaptor.forClass((Class<List<ChatTurn>>) (Class<?>) List.class);
        // The fold gets the PREVIOUS summary too - it is a rolling fold, not a
        // fresh summary of the last batch, or every earlier batch would be lost.
        verify(summariser)
                .foldAsync(eq(SESSION_ID), any(), eq("summary so far"), leaving.capture(), any());
        assertEquals(40, leaving.getValue().size());
        assertEquals("u0", leaving.getValue().get(0).content());
        assertEquals("a39", leaving.getValue().get(39).content());
        // Folding no longer shrinks storage: the trim is the ordinary
        // MAX_STORED_ENTRIES ceiling, the folded turns stay as transcript.
        verifyMarkerAwareTrim();
    }

    @Test
    void foldWindowFollowsTheAdminReplayWindow() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        withTenantContext();
        when(platformSettings.getInt(eq(SessionMemoryService.KEY_HISTORY_MAX_TURNS), anyInt()))
                .thenReturn(100);
        stubClaim("100", List.of());

        service.appendTurn(SESSION_ID, user("hello"));

        // Folding is keyed to what the model actually stops seeing - an admin
        // who replays 100 turns must not have turns 41-100 summarised.
        assertTrue(executedScripts().contains(SessionAppendScript.FOLD_CLAIM_SCRIPT));
        verify(redisTemplate)
                .execute(
                        eq(SessionAppendScript.FOLD_CLAIM_SCRIPT),
                        eq(List.of(REDIS_KEY, MARKER_KEY)),
                        eq("100"),
                        eq("40"),
                        eq(TTL_SECONDS));
    }

    @Test
    void foldWindowIsCappedSoTheStorageTrimNeverReachesAnUnfoldedTurn() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        withTenantContext();
        // Window == storage ceiling: uncapped, a batch would only be claimable
        // after the trim had already discarded it.
        when(platformSettings.getInt(eq(SessionMemoryService.KEY_HISTORY_MAX_TURNS), anyInt()))
                .thenReturn(SessionMemoryService.MAX_STORED_ENTRIES);
        String capped =
                Integer.toString(
                        SessionMemoryService.MAX_STORED_ENTRIES
                                - SessionMemoryService.SUMMARY_FOLD_BATCH);
        stubClaim(capped, List.of());

        service.appendTurn(SESSION_ID, user("hello"));

        verify(redisTemplate)
                .execute(
                        eq(SessionAppendScript.FOLD_CLAIM_SCRIPT),
                        eq(List.of(REDIS_KEY, MARKER_KEY)),
                        eq(capped),
                        eq("40"),
                        eq(TTL_SECONDS));
    }

    @Test
    void foldIsNotClaimedWhenThereIsNoTenantContext() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        // No TenantContext - exactly the assistant-turn append path, which runs
        // on the stream-completion thread with the ThreadLocal already gone.
        // Resolving credentials or a plan there would hit the `public` schema
        // (the 2026-08-10 document-generation outage), so we must not try -
        // and must not CLAIM either, or the batch would be marked folded
        // without ever reaching the summariser.

        service.appendTurn(SESSION_ID, assistant("a reply"));

        assertFalse(executedScripts().contains(SessionAppendScript.FOLD_CLAIM_SCRIPT));
        verify(summariser, never()).foldAsync(anyString(), any(), any(), any(), any());
        verifyMarkerAwareTrim();
    }

    @Test
    void disabledSummariserKeepsThePreFeatureAppendPathExactly() {
        SessionMemoryService service = newService(40, 32000, 0);
        when(summaryProvider.getIfAvailable()).thenReturn(summariser);
        when(summariser.isEnabled()).thenReturn(false);
        withTenantContext();

        service.appendTurn(SESSION_ID, user("hello"));

        // No scripts, no marker, no summary read: push, plain LTRIM, EXPIRE.
        assertTrue(executedScripts().isEmpty());
        verify(listOps).trim(REDIS_KEY, -SessionMemoryService.MAX_STORED_ENTRIES, -1);
        verify(redisTemplate).expire(eq(REDIS_KEY), eq(TTL));
        verify(valueOps, never()).getAndExpire(anyString(), any(java.time.Duration.class));
    }

    @Test
    void redisFailureWhileClaimingTheFoldStillTrimsAndExpires() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        withTenantContext();
        when(redisTemplate.execute(
                        eq(SessionAppendScript.FOLD_CLAIM_SCRIPT),
                        eq(List.of(REDIS_KEY, MARKER_KEY)),
                        eq("40"),
                        eq("40"),
                        eq(TTL_SECONDS)))
                .thenThrow(new IllegalStateException("NOSCRIPT / redis down"));

        // appendTurn sits on the chat write path with no try/catch above it, so
        // the extra work this feature added must never be able to cost a user
        // their turn - the append still completes.
        service.appendTurn(SESSION_ID, user("hello"));

        verify(summariser, never()).foldAsync(anyString(), any(), any(), any(), any());
        verifyMarkerAwareTrim();
        verify(redisTemplate).expire(eq(REDIS_KEY), eq(TTL));
    }

    @Test
    void trimScriptFailureFallsBackToThePlainTrim() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        withTenantContext();
        stubClaim("40", List.of());
        when(redisTemplate.execute(
                        eq(SessionAppendScript.TRIM_SCRIPT), eq(List.of(REDIS_KEY, MARKER_KEY)),
                        eq(Integer.toString(SessionMemoryService.MAX_STORED_ENTRIES)),
                                eq(TTL_SECONDS)))
                .thenThrow(new IllegalStateException("scripting disabled"));

        service.appendTurn(SESSION_ID, user("hello"));

        // The storage ceiling holds regardless of whether scripting works.
        verify(listOps).trim(REDIS_KEY, -SessionMemoryService.MAX_STORED_ENTRIES, -1);
        verify(redisTemplate).expire(eq(REDIS_KEY), eq(TTL));
    }

    @Test
    void persistCallbackStoresTheSummaryOnTheSameTtlAsTheHistoryList() {
        SessionMemoryService service = newService(40, 32000, 0);
        withSummariserAvailable();
        withTenantContext();
        stubClaim("40", storedJson(40));

        service.appendTurn(SESSION_ID, user("the 80th entry"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<String>> persist =
                ArgumentCaptor.forClass((Class<Consumer<String>>) (Class<?>) Consumer.class);
        verify(summariser).foldAsync(anyString(), any(), any(), any(), persist.capture());
        // The callback runs later, on the summariser's background thread.
        persist.getValue().accept("the folded summary");

        // Same TTL as the list: a summary that outlived its conversation would
        // be replayed into a session it no longer describes, and one that died
        // first would silently lose the only record of the folded turns.
        verify(valueOps).set(SUMMARY_KEY, "the folded summary", TTL);
    }

    @Test
    void rollingSummaryIsReplayedAheadOfTheWindow() {
        SessionMemoryService service = newService(3, 32000, 0);
        stubTailRange(3, List.of(user("u2"), assistant("a2"), user("u3")));
        when(valueOps.getAndExpire(SUMMARY_KEY, TTL))
                .thenReturn("The user is migrating a Postgres cluster.");

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        // Merged into the oldest turn's CONTENT rather than added as a turn of
        // its own: a ChatTurn can only be USER or ASSISTANT, so a synthetic
        // head turn would either put two user messages back to back or open
        // the conversation with the assistant - both provider-level 400s on
        // the chat path, which an optional enhancement may never risk.
        assertEquals(3, window.size());
        assertEquals(MessageRole.USER, window.get(0).role());
        assertTrue(window.get(0).content().startsWith(SessionMemoryService.SUMMARY_PREAMBLE));
        assertTrue(window.get(0).content().contains("migrating a Postgres cluster"));
        assertTrue(window.get(0).content().endsWith("u2"));
        assertEquals("a2", window.get(1).content());
        assertEquals("u3", window.get(2).content());
    }

    @Test
    void windowIsUnchangedWhenTheSessionHasNoSummary() {
        SessionMemoryService service = newService(3, 32000, 0);
        stubTailRange(3, List.of(user("u2"), assistant("a2"), user("u3")));
        when(valueOps.getAndExpire(SUMMARY_KEY, TTL)).thenReturn(null);

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(List.of("u2", "a2", "u3"), window.stream().map(ChatTurn::content).toList());
        assertFalse(window.get(0).content().startsWith(SessionMemoryService.SUMMARY_PREAMBLE));
    }

    @Test
    void summaryReadFailureStillReturnsTheWindow() {
        SessionMemoryService service = newService(3, 32000, 0);
        stubTailRange(3, List.of(user("u2"), assistant("a2"), user("u3")));
        when(valueOps.getAndExpire(SUMMARY_KEY, TTL))
                .thenThrow(new IllegalStateException("GETEX unsupported"));

        // The window is perfectly good context on its own; refusing to serve it
        // because an optional enhancement failed would turn a nice-to-have into
        // an outage.
        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(List.of("u2", "a2", "u3"), window.stream().map(ChatTurn::content).toList());
    }

    // ---- rewind (time-travel) -----------------------------------------------

    @Test
    void rewindTrimsTheTailAndDropsTheSummaryAndFoldMarker() {
        SessionMemoryService service = newService(3, 32000, 0);
        when(listOps.size(REDIS_KEY)).thenReturn(6L);

        int kept = service.rewind(SESSION_ID, 2);

        assertEquals(2, kept);
        verify(listOps).trim(REDIS_KEY, 0, 1);
        verify(redisTemplate).delete(SUMMARY_KEY);
        // The marker counts folded entries at the head of the OLD list; left
        // behind it could exceed the kept length and skip future folds.
        verify(redisTemplate).delete(MARKER_KEY);
        verify(redisTemplate, never()).delete(REDIS_KEY);
    }

    @Test
    void rewindToZeroDeletesTheTranscriptKey() {
        SessionMemoryService service = newService(3, 32000, 0);
        when(listOps.size(REDIS_KEY)).thenReturn(4L);

        int kept = service.rewind(SESSION_ID, 0);

        // LTRIM 0 -1 would keep EVERYTHING (negative index = last element), so
        // keep=0 must delete the key outright rather than go through trim.
        assertEquals(0, kept);
        verify(redisTemplate).delete(REDIS_KEY);
        verify(redisTemplate).delete(SUMMARY_KEY);
        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
    }

    @Test
    void rewindPastTheEndIsANoOp() {
        SessionMemoryService service = newService(3, 32000, 0);
        when(listOps.size(REDIS_KEY)).thenReturn(3L);

        int kept = service.rewind(SESSION_ID, 10);

        // Nothing to drop: no trim, and the summary stays - it only folds
        // turns that still exist.
        assertEquals(3, kept);
        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void rewindOnAnEmptySessionIsANoOp() {
        SessionMemoryService service = newService(3, 32000, 0);
        when(listOps.size(REDIS_KEY)).thenReturn(0L);

        int kept = service.rewind(SESSION_ID, 0);

        assertEquals(0, kept);
        verify(redisTemplate, never()).delete(anyString());
    }

    // ---- Redis DOWN: a real connection failure, not the null-return case ----
    // Redis is the only store of session history; an outage must cost the
    // conversation its memory, never the user's turn (AgentChatService calls
    // these with no try/catch above them).

    @Test
    void getHistoryReturnsNoHistoryWhenRedisIsDown() {
        SessionMemoryService service = newService(40, 32000, 0);
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertTrue(service.getHistory(SESSION_ID).isEmpty());
    }

    @Test
    void getFullHistoryReturnsNoHistoryWhenRedisIsDown() {
        SessionMemoryService service = newService(40, 32000, 0);
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertTrue(service.getFullHistory(SESSION_ID).isEmpty());
    }

    @Test
    void appendTurnSkipsTheWriteInsteadOfThrowingWhenRedisIsDown() {
        SessionMemoryService service = newService(40, 32000, 0);
        when(listOps.rightPush(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.appendTurn(SESSION_ID, user("hello")));

        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
        verify(redisTemplate, never()).expire(anyString(), any(java.time.Duration.class));
    }

    @Test
    void appendTurnSurvivesALateRedisFailureToo() {
        SessionMemoryService service = newService(40, 32000, 0);
        when(redisTemplate.expire(anyString(), any(java.time.Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.appendTurn(SESSION_ID, user("hello")));
    }
}
