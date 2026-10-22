package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Coverage for the bounded-replay window (2026-08-11 token audit — see {@link
 * SessionMemoryService}'s class javadoc): {@code getHistory} used to return every turn ever stored,
 * which AgentChatService replayed into the prompt on every turn — the single largest per-turn token
 * driver. These tests pin the new contract so a refactor can't quietly reopen the leak:
 *
 * <ul>
 *   <li>the window respects the turn cap (and pushes it down to the Redis {@code LRANGE} so
 *       untouched entries are never even deserialized),
 *   <li>the window respects the cumulative content-character budget AND the cumulative
 *       estimated-token budget (which is what actually bounds a Devanagari/CJK session - see {@link
 *       SessionMemoryService#estimateTokens}), keeping the NEWEST turns and always at least one,
 *   <li>a cut landing mid-exchange prefers a USER-turn boundary (the orphaned assistant reply at
 *       the old edge is dropped),
 *   <li>{@code getFullHistory} keeps the old unbounded contract for genuine full-transcript
 *       consumers,
 *   <li>{@code appendTurn} LTRIMs storage to the newest {@link
 *       SessionMemoryService#MAX_STORED_ENTRIES}, and
 *   <li>the platform-setting reads are honored but time-box cached (same shape as {@code
 *       FreeTierUsageLimiterService#effectiveQuotaConfig}) so this hot path never adds a
 *       per-message Postgres round trip.
 * </ul>
 *
 * <p>The later block covers summarisation when turns leave the REPLAY window (2026-09-28: it used
 * to wait for the 400-entry storage trim, leaving turns 41-400-from-the-end neither replayed nor
 * summarised): that the batch is claimed through the atomic fold-claim script with the effective
 * window, that the fold window is capped below the storage ceiling, that the storage trim goes
 * through the marker-aware script, that the rolling summary is replayed ahead of the window, and —
 * the property that actually matters for a feature bolted onto the chat write path — that every
 * failure mode degrades to the plain behaviour rather than throwing into a user's turn. The Lua
 * itself is not executed here (no embedded Redis in this module's tests); its contract is pinned by
 * what the service passes it.
 *
 * <p>Same manual {@link StringRedisTemplate}/{@link ListOperations} mock pattern as {@code
 * ToolCircuitBreakerServiceTest} — no embedded Redis. The tests simulate Redis's negative-index
 * {@code LRANGE} semantics themselves when stubbing {@code range(key, -maxTurns, -1)}, because the
 * service deliberately asks Redis for only the newest {@code maxTurns} entries.
 */
class SessionMemoryServiceTest extends SessionMemoryServiceTestBase {

    // ---- turn cap ----------------------------------------------------------

    @Test
    void windowRespectsTurnCapAndKeepsNewestInChronologicalOrder() {
        SessionMemoryService service = newService(3, 32000, 0);
        // 5 stored turns; cap of 3 must keep exactly the newest 3, still oldest-first.
        stubTailRange(
                3, List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"), user("u3")));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(List.of("u2", "a2", "u3"), window.stream().map(ChatTurn::content).toList());
        // The cap must be pushed down into the Redis read itself - fetching
        // the whole list and discarding most of it would still deserialize
        // (and transfer) unbounded data.
        verify(listOps).range(REDIS_KEY, -3, -1);
    }

    // ---- failed replies (prod signed-in pass 2026-10-05, bug 13) ------------

    /**
     * A failed reply is kept in the transcript so a reload shows what happened, but it is an error
     * message, not something the model said: it must never be replayed into a prompt.
     */
    @Test
    void aFailedReplyIsInTheFullTranscriptButNeverInTheModelFacingWindow() {
        SessionMemoryService service = newService(10, 32000, 0);
        ChatTurn failed = ChatTurn.failedAssistant("The AI provider rejected our credentials.");
        List<ChatTurn> stored = List.of(user("u1"), failed, user("u2"), assistant("a2"));
        stubTailRange(10, stored);
        when(listOps.range(REDIS_KEY, 0, -1)).thenReturn(stored.stream().map(this::json).toList());

        assertEquals(
                List.of("u1", "u2", "a2"),
                service.getHistory(SESSION_ID).stream().map(ChatTurn::content).toList());

        List<ChatTurn> transcript = service.getFullHistory(SESSION_ID);
        assertEquals(4, transcript.size());
        assertEquals(MessageRole.ASSISTANT, transcript.get(1).role());
        assertEquals(
                "[reply failed] The AI provider rejected our credentials.",
                transcript.get(1).content());
        assertTrue(ChatTurn.isFailedReply(transcript.get(1)));
        // An ordinary assistant reply that merely mentions the words is not a failed reply.
        assertFalse(ChatTurn.isFailedReply(assistant("The log said [reply failed] twice.")));
        assertFalse(ChatTurn.isFailedReply(user("[reply failed] is what I saw")));
    }

    // ---- char cap ----------------------------------------------------------

    @Test
    void windowRespectsCharBudgetKeepingNewestTurns() {
        SessionMemoryService service = newService(40, 10, 0);
        // Walking from the tail: "ddd"(3, newest, always in) + "cccc"(4) = 7 <= 10;
        // adding "bbbbbb"(6) would hit 13 > 10, so the window stops there.
        stubTailRange(
                40, List.of(user("aaaaaa"), assistant("bbbbbb"), user("cccc"), assistant("ddd")));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(List.of("cccc", "ddd"), window.stream().map(ChatTurn::content).toList());
    }

    @Test
    void newestTurnIsAlwaysIncludedEvenWhenItAloneExceedsCharBudget() {
        SessionMemoryService service = newService(40, 5, 0);
        // The newest turn is 20 chars against a 5-char budget. An empty window
        // would silently drop the context of the reply the user just got, so
        // the newest turn is exempt from the budget check.
        stubTailRange(40, List.of(user("earlier question"), assistant("a".repeat(20))));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(1, window.size());
        assertEquals(MessageRole.ASSISTANT, window.get(0).role());
        assertEquals("a".repeat(20), window.get(0).content());
    }

    // ---- token budget ------------------------------------------------------

    @Test
    void tokenEstimateCountsLatinAtFourCharsPerTokenAndDenseScriptsPerCharacter() {
        assertEquals(0, SessionMemoryService.estimateTokens(null));
        assertEquals(0, SessionMemoryService.estimateTokens(""));
        assertEquals(3, SessionMemoryService.estimateTokens("hello world!")); // 12 chars / 4
        assertEquals(
                1, SessionMemoryService.estimateTokens("hi")); // rounds up, never 0 for non-empty
        // Devanagari (including combining vowel signs/virama) - one per code point.
        assertEquals(
                6, SessionMemoryService.estimateTokens("\u0928\u092e\u0938\u094d\u0924\u0947"));
        // Japanese kana + kanji.
        assertEquals(5, SessionMemoryService.estimateTokens("\u3053\u3093\u306b\u3061\u306f"));
        assertEquals(2, SessionMemoryService.estimateTokens("\u4e2d\u6587"));
        // Mixed: 2 Han + "ok" (2 Latin -> 1).
        assertEquals(3, SessionMemoryService.estimateTokens("\u4e2d\u6587ok"));
        // A surrogate-pair emoji is ONE code point, pooled with the Latin bucket.
        assertEquals(1, SessionMemoryService.estimateTokens("\uD83D\uDE00"));
    }

    @Test
    void tokenBudgetBoundsADevanagariWindowTheCharBudgetWouldHaveLetThrough() {
        // Generous char budget, 10-token budget. Newest turn: 6 Devanagari
        // chars = 6 tokens; the older one would add 8 more (14 > 10), so it is
        // cut - even though 14 chars is nowhere near the 32k char cap. This is
        // the case the char cap alone got ~4x wrong.
        SessionMemoryService service = newService(40, 32000, 10, 0);
        stubTailRange(40, List.of(user("\u0915".repeat(8)), assistant("\u0916".repeat(6))));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(1, window.size());
        assertEquals("\u0916".repeat(6), window.get(0).content());
    }

    @Test
    void theSameCharacterCountInLatinScriptFitsTheSameTokenBudget() {
        // Same shape as above in English: 8 + 6 chars = 2 + 2 tokens <= 10, so
        // both turns replay - the token cap only bites where tokens are dense.
        SessionMemoryService service = newService(40, 32000, 10, 0);
        stubTailRange(40, List.of(user("a".repeat(8)), assistant("b".repeat(6))));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(2, window.size());
    }

    @Test
    void tokenBudgetIsAdminTunableViaPlatformSetting() {
        SessionMemoryService service = newService(40, 32000, 8000, 0);
        when(platformSettings.getInt(eq(SessionMemoryService.KEY_HISTORY_MAX_TOKENS), anyInt()))
                .thenReturn(2);
        // 8 Latin chars = 2 tokens each: newest always in, the next would make 4 > 2.
        stubTailRange(40, List.of(user("a".repeat(8)), assistant("b".repeat(8))));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(1, window.size());
        assertEquals("b".repeat(8), window.get(0).content());
    }

    // ---- user-turn boundary preference -------------------------------------

    @Test
    void orphanedAssistantTurnAtWindowEdgeIsDropped() {
        SessionMemoryService service = newService(4, 32000, 0);
        // Cap of 4 over [u1,a1,u2,a2,u3] cuts mid-exchange: the raw window is
        // [a1,u2,a2,u3], opening with an assistant reply to a question the
        // model can't see. That orphan must be dropped.
        stubTailRange(
                4, List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"), user("u3")));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(List.of("u2", "a2", "u3"), window.stream().map(ChatTurn::content).toList());
        assertEquals(MessageRole.USER, window.get(0).role());
    }

    @Test
    void soleAssistantTurnIsNotDroppedByBoundaryPreference() {
        SessionMemoryService service = newService(1, 32000, 0);
        // The boundary preference must never empty a non-empty session: a
        // window of exactly one assistant turn stays as-is.
        stubTailRange(1, List.of(user("u1"), assistant("a1")));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(1, window.size());
        assertEquals(MessageRole.ASSISTANT, window.get(0).role());
    }

    @Test
    void windowStartingOnUserTurnIsUntouchedByBoundaryPreference() {
        SessionMemoryService service = newService(2, 32000, 0);
        stubTailRange(2, List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2")));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(List.of("u2", "a2"), window.stream().map(ChatTurn::content).toList());
    }

    // ---- getFullHistory ----------------------------------------------------

    @Test
    void getFullHistoryReturnsEverythingUnwindowed() {
        // Tiny window limits that would clip getHistory to almost nothing...
        SessionMemoryService service = newService(1, 1, 0);
        List<ChatTurn> stored =
                List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"), user("u3"));
        when(listOps.range(REDIS_KEY, 0, -1)).thenReturn(stored.stream().map(this::json).toList());

        // ...must not affect the full-transcript read at all.
        List<ChatTurn> full = service.getFullHistory(SESSION_ID);

        assertEquals(
                List.of("u1", "a1", "u2", "a2", "u3"),
                full.stream().map(ChatTurn::content).toList());
        verify(listOps).range(REDIS_KEY, 0, -1);
    }

    // ---- storage bound -----------------------------------------------------

    @Test
    void appendTurnTrimsStoredListToNewestMaxEntries() {
        SessionMemoryService service = newService(40, 32000, 0);

        service.appendTurn(SESSION_ID, user("hello"));

        // rightPush then LTRIM to the newest MAX_STORED_ENTRIES (negative
        // start = keep-from-the-tail), then the usual TTL refresh - the trim
        // is what stops a pathological session growing one Redis value
        // without limit for its whole TTL.
        verify(listOps).rightPush(eq(REDIS_KEY), anyString());
        verify(listOps).trim(REDIS_KEY, -SessionMemoryService.MAX_STORED_ENTRIES, -1);
        verify(redisTemplate).expire(eq(REDIS_KEY), eq(java.time.Duration.ofHours(24)));
    }

    // ---- session owner record (read by chat export) ----

    private static final String OWNER_KEY = "agentic:session-owner:" + SESSION_ID;

    @Test
    void firstAppendWithATenantContextRecordsTheOwnerOnTheSessionTtl() {
        SessionMemoryService service = newService(40, 32000, 0);
        TenantContext.set(TenantContext.of("t1", "alice", Set.of()));

        service.appendTurn(SESSION_ID, user("hello"));

        verify(valueOps).setIfAbsent(OWNER_KEY, "t1\nalice", TTL);
        verify(redisTemplate).expire(eq(OWNER_KEY), eq(TTL));
    }

    @Test
    void appendWithoutATenantContextRecordsNoOwner() {
        SessionMemoryService service = newService(40, 32000, 0);

        service.appendTurn(SESSION_ID, user("hello"));

        verify(valueOps, never())
                .setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
        verify(listOps).rightPush(eq(REDIS_KEY), anyString());
    }

    @Test
    void anOwnerRecordFailureNeverCostsTheAppend() {
        SessionMemoryService service = newService(40, 32000, 0);
        TenantContext.set(TenantContext.of("t1", "alice", Set.of()));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        service.appendTurn(SESSION_ID, user("hello"));

        verify(listOps).rightPush(eq(REDIS_KEY), anyString());
        verify(redisTemplate).expire(eq(REDIS_KEY), eq(TTL));
    }

    @Test
    void isOwnedByMatchesOnlyTheExactTenantAndSubject() {
        SessionMemoryService service = newService(40, 32000, 0);
        when(valueOps.get(OWNER_KEY)).thenReturn("t1\nalice");

        assertTrue(service.isOwnedBy(SESSION_ID, "t1", "alice"));
        assertFalse(service.isOwnedBy(SESSION_ID, "t2", "alice"));
        assertFalse(service.isOwnedBy(SESSION_ID, "t1", "mallory"));
        assertFalse(service.isOwnedBy(SESSION_ID, "t1", null));
        assertFalse(service.isOwnedBy(SESSION_ID, null, "alice"));
        assertFalse(service.isOwnedBy("other-session", "t1", "alice"));
    }

    @Test
    void isOwnedByFailsClosedWhenRedisIsDown() {
        SessionMemoryService service = newService(40, 32000, 0);
        when(valueOps.get(OWNER_KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertFalse(service.isOwnedBy(SESSION_ID, "t1", "alice"));
    }

    // ---- platform-setting config: honored, clamped, and time-box cached ----

    @Test
    void platformSettingOverridesValueDefaults() {
        SessionMemoryService service = newService(40, 32000, 0);
        // Admin set platform.chat.history-max-turns=2 in the platform_setting
        // table; the @Value default of 40 must lose to it.
        when(platformSettings.getInt(eq(SessionMemoryService.KEY_HISTORY_MAX_TURNS), anyInt()))
                .thenReturn(2);
        stubTailRange(2, List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2")));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(2, window.size());
        verify(listOps).range(REDIS_KEY, -2, -1);
    }

    @Test
    void nonsenseAdminTurnValueIsClampedToAtLeastOne() {
        SessionMemoryService service = newService(40, 32000, 0);
        // A mistyped 0 (or negative) admin value must never blank the window
        // entirely - it clamps to 1 so the newest turn always survives.
        when(platformSettings.getInt(eq(SessionMemoryService.KEY_HISTORY_MAX_TURNS), anyInt()))
                .thenReturn(0);
        stubTailRange(1, List.of(user("u1"), assistant("a1"), user("u2")));

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals(1, window.size());
        assertEquals("u2", window.get(0).content());
        verify(listOps).range(REDIS_KEY, -1, -1);
    }

    @Test
    void settingsReadIsTimeBoxCachedNotPerCall() {
        // Large cache window: the second getHistory within it must NOT hit
        // PlatformSettingsService again - getHistory runs on every chat turn,
        // and an uncached read would add a Postgres round trip per message.
        SessionMemoryService service = newService(3, 32000, 60_000);
        stubTailRange(3, List.of(user("u1"), assistant("a1"), user("u2")));

        service.getHistory(SESSION_ID);
        service.getHistory(SESSION_ID);

        verify(platformSettings, times(1))
                .getInt(eq(SessionMemoryService.KEY_HISTORY_MAX_TURNS), anyInt());
        verify(platformSettings, times(1))
                .getInt(eq(SessionMemoryService.KEY_HISTORY_MAX_CHARS), anyInt());
        verify(platformSettings, times(1))
                .getInt(eq(SessionMemoryService.KEY_HISTORY_MAX_TOKENS), anyInt());
    }

    @Test
    void emptySessionReturnsEmptyWindow() {
        SessionMemoryService service = newService(40, 32000, 0);
        when(listOps.range(REDIS_KEY, -40, -1)).thenReturn(List.of());

        assertTrue(service.getHistory(SESSION_ID).isEmpty());
    }
}
