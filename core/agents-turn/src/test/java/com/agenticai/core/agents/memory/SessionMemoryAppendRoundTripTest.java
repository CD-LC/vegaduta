package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * The single-round-trip append (mocked Redis): one script call and nothing else on the happy path,
 * the same mode selection the legacy branches had, and the two failure behaviours - a refused
 * script falls back to the multi-call sequence, a dead connection costs the history entry and never
 * the turn. What the Lua itself does is pinned against a real server by {@link
 * SessionAppendScriptRedisTest}.
 */
class SessionMemoryAppendRoundTripTest {

    private static final String SID = "sess-1";
    private static final String LIST = "agentic:session:" + SID;
    private static final String MARKER = "agentic:session-folded:" + SID;
    private static final String OWNER = "agentic:session-owner:" + SID;

    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.withJavaTime();
    private StringRedisTemplate redis;
    private ListOperations<String, String> listOps;
    private ValueOperations<String, String> valueOps;
    private SessionSummaryService summariser;
    private ObjectProvider<SessionSummaryService> provider;
    private SessionMemoryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.clear();
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        summariser = mock(SessionSummaryService.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        service =
                new SessionMemoryService(redis, mapper, settings, provider, 24, 40, 32000, 8000, 0);
        service.setSingleRoundTripAppend(true);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static ChatTurn turn() {
        return new ChatTurn(MessageRole.USER, "hello", Instant.parse("2026-08-11T00:00:00Z"));
    }

    private void live() {
        when(provider.getIfAvailable()).thenReturn(summariser);
        when(summariser.isEnabled()).thenReturn(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubScript(Object result) {
        when(redis.execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class)))
                .thenReturn((List) result);
    }

    private void verifyScript(SessionAppendScript.Mode mode, String fold, String owner) {
        verify(redis)
                .execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        eq(List.of(LIST, MARKER, OWNER)),
                        Mockito.anyString(),
                        eq("400"),
                        eq("86400"),
                        eq(mode.arg),
                        eq(fold),
                        eq("40"),
                        eq(owner));
    }

    @Test
    void oneScriptCallAndNothingElseWithoutASummariser() {
        stubScript(List.of());
        service.appendTurn(SID, turn());

        verifyScript(SessionAppendScript.Mode.PLAIN_TRIM, "0", "");
        Mockito.verifyNoInteractions(listOps, valueOps);
        verify(redis, never()).expire(anyString(), any(java.time.Duration.class));
    }

    @Test
    void ownerAndFoldWindowTravelInTheSameCall() {
        live();
        TenantContext.set(TenantContext.of("t1", "alice", Set.of("USER")));
        stubScript(List.of());

        service.appendTurn(SID, turn());

        verifyScript(SessionAppendScript.Mode.CLAIM_AND_TRIM, "40", "t1\nalice");
        Mockito.verifyNoInteractions(listOps);
    }

    @Test
    void aSummariserWithoutATenantTrimsButNeverClaims() {
        live();
        stubScript(List.of());

        service.appendTurn(SID, turn());

        verifyScript(SessionAppendScript.Mode.MARKER_AWARE_TRIM, "0", "");
        verify(summariser, never()).foldAsync(anyString(), any(), any(), any(), any());
    }

    @Test
    void aClaimedBatchIsHandedToTheSummariser() throws Exception {
        live();
        TenantContext.set(TenantContext.of("t1", "alice", Set.of("USER")));
        stubScript(List.of(mapper.writeValueAsString(turn())));

        service.appendTurn(SID, turn());

        verify(summariser).foldAsync(eq(SID), any(), any(), any(), any());
    }

    @Test
    void aRefusedScriptFallsBackToTheMultiCallAppend() {
        when(redis.execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class)))
                .thenThrow(
                        new RedisSystemException(
                                "NOPERM this user has no permissions to run the 'evalsha' command",
                                new RuntimeException()));

        assertDoesNotThrow(() -> service.appendTurn(SID, turn()));

        verify(listOps).rightPush(eq(LIST), anyString());
        verify(listOps).trim(LIST, -SessionMemoryService.MAX_STORED_ENTRIES, -1);
        verify(redis).expire(eq(LIST), any(java.time.Duration.class));
    }

    @Test
    void aRefusalIsRememberedSoLaterAppendsSkipTheFailedScript() {
        when(redis.execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class)))
                .thenThrow(
                        new RedisSystemException("NOPERM no permissions", new RuntimeException()));

        service.appendTurn(SID, turn());
        service.appendTurn(SID, turn());

        verify(redis, Mockito.times(1))
                .execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class));
        verify(listOps, Mockito.times(2)).rightPush(eq(LIST), anyString());
    }

    @Test
    void aTimeoutIsNotARefusalAndTheWriteIsNeverReplayed() {
        when(redis.execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timed out"));

        assertDoesNotThrow(() -> service.appendTurn(SID, turn()));

        Mockito.verifyNoInteractions(listOps);
        verify(redis, Mockito.times(1))
                .execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class));
    }

    @Test
    void aLuaRuntimeErrorIsNotARefusalEither() {
        when(redis.execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class)))
                .thenThrow(
                        new RedisSystemException(
                                "ERR Error running script: WRONGTYPE", new RuntimeException()));

        assertDoesNotThrow(() -> service.appendTurn(SID, turn()));

        Mockito.verifyNoInteractions(listOps);
    }

    @Test
    void aDeadConnectionIsNotRetriedAndNeverThrows() {
        when(redis.execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> service.appendTurn(SID, turn()));

        Mockito.verifyNoInteractions(listOps);
        verify(redis, Mockito.times(1))
                .execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class));
    }

    @Test
    void theFlagOffKeepsTheMultiCallSequence() {
        service.setSingleRoundTripAppend(false);

        service.appendTurn(SID, turn());

        verify(listOps).rightPush(eq(LIST), anyString());
        verify(redis, never())
                .execute(
                        eq(SessionAppendScript.APPEND_SCRIPT),
                        any(List.class),
                        any(Object[].class));
        verify(listOps, never()).range(anyString(), anyLong(), anyLong());
    }
}
