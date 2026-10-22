package com.agenticai.core.agents.memory;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixture for the {@link SessionMemoryService} tests: manual {@link StringRedisTemplate}
 * mocks, settings and summariser stubs. See {@link SessionMemoryServiceTest} for the contract
 * pinned.
 */
abstract class SessionMemoryServiceTestBase {

    static final String SESSION_ID = "sess-1";
    static final String REDIS_KEY = "agentic:session:" + SESSION_ID;
    // Distinct PREFIX namespace, not a suffix on the history key — a caller-
    // supplied sessionId ending in ":summary" could otherwise alias another
    // session's summary key (see SUMMARY_KEY_PREFIX's javadoc in the service).
    static final String SUMMARY_KEY = "agentic:session-summary:" + SESSION_ID;
    static final String MARKER_KEY = "agentic:session-folded:" + SESSION_ID;
    static final java.time.Duration TTL = java.time.Duration.ofHours(24);
    static final String TTL_SECONDS = "86400";

    // Same ObjectMapper shape the service gets from Spring (jsr310 present on
    // the Boot classpath) - ChatTurn carries an Instant, so the plain
    // new ObjectMapper() would fail to serialize it.
    final ObjectMapper objectMapper = com.agenticai.core.json.JsonMappers.withJavaTime();

    StringRedisTemplate redisTemplate;
    ListOperations<String, String> listOps;
    ValueOperations<String, String> valueOps;
    PlatformSettingsService platformSettings;
    ObjectProvider<SessionSummaryService> summaryProvider;
    SessionSummaryService summariser;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.clear();
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // The summary lives at a sibling STRING key, so opsForValue is now on
        // the read path too - unstubbed it would hand back null and every
        // window test would trip the service's own fail-open catch.
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        platformSettings = mock(PlatformSettingsService.class);
        // Default behavior: no platform_setting row exists, so getInt returns
        // the caller-supplied default - matches a fresh database.
        when(platformSettings.getInt(anyString(), anyInt()))
                .thenAnswer(SessionMemoryServiceTestBase::echoDefault);
        // Default: NO summariser bean. Every pre-existing test below therefore
        // exercises exactly the behavior this class had before summarisation
        // existed, which is the point - that path must stay untouched.
        summariser = mock(SessionSummaryService.class);
        summaryProvider = mock(ObjectProvider.class);
        when(summaryProvider.getIfAvailable()).thenReturn(null);
    }

    @AfterEach
    void clearTenant() {
        // The fold tests below set a real TenantContext (it is a ThreadLocal
        // and JUnit reuses threads across tests) - leaking one would silently
        // enable folding in an unrelated test.
        TenantContext.clear();
    }

    static Object echoDefault(InvocationOnMock invocation) {
        return invocation.getArgument(1);
    }

    /** Makes the summariser bean present and enabled, as a live context would. */
    void withSummariserAvailable() {
        when(summaryProvider.getIfAvailable()).thenReturn(summariser);
        when(summariser.isEnabled()).thenReturn(true);
    }

    /**
     * @param configCacheMs 0 disables the config cache (every getHistory call re-reads the settings
     *     mock) so per-test setting changes take effect immediately; the cache test passes a large
     *     value instead.
     */
    SessionMemoryService newService(int maxTurns, int maxChars, long configCacheMs) {
        return newService(maxTurns, maxChars, 8000, configCacheMs);
    }

    SessionMemoryService newService(int maxTurns, int maxChars, int maxTokens, long configCacheMs) {
        return new SessionMemoryService(
                redisTemplate,
                objectMapper,
                platformSettings,
                summaryProvider,
                24,
                maxTurns,
                maxChars,
                maxTokens,
                configCacheMs);
    }

    ChatTurn user(String content) {
        return new ChatTurn(MessageRole.USER, content, Instant.parse("2026-08-11T00:00:00Z"));
    }

    ChatTurn assistant(String content) {
        return new ChatTurn(MessageRole.ASSISTANT, content, Instant.parse("2026-08-11T00:00:01Z"));
    }

    String json(ChatTurn turn) {
        try {
            return objectMapper.writeValueAsString(turn);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stubs what Redis would return for {@code LRANGE key -maxTurns -1}: the newest maxTurns
     * entries, oldest-first.
     */
    void stubTailRange(int maxTurns, List<ChatTurn> stored) {
        List<String> tail =
                stored.subList(Math.max(0, stored.size() - maxTurns), stored.size()).stream()
                        .map(this::json)
                        .toList();
        when(listOps.range(REDIS_KEY, -maxTurns, -1)).thenReturn(tail);
    }

    // ---- summarisation when turns leave the replay window ------------------

    /** Sets a real tenant on the calling thread, as the user-turn append path has. */
    void withTenantContext() {
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of("USER")));
    }

    /** {@code n} alternating user/assistant turns as their stored JSON. */
    List<String> storedJson(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(json(i % 2 == 0 ? user("u" + i) : assistant("a" + i)));
        }
        return out;
    }

    /** Stubs the fold-claim script's answer for a given fold window. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stubClaim(String foldWindow, List<String> claimed) {
        when(redisTemplate.execute(
                        eq(SessionAppendScript.FOLD_CLAIM_SCRIPT),
                        eq(List.of(REDIS_KEY, MARKER_KEY)),
                        eq(foldWindow),
                        eq(Integer.toString(SessionMemoryService.SUMMARY_FOLD_BATCH)),
                        eq(TTL_SECONDS)))
                .thenReturn((List) claimed);
    }

    void verifyMarkerAwareTrim() {
        verify(redisTemplate)
                .execute(
                        eq(SessionAppendScript.TRIM_SCRIPT),
                        eq(List.of(REDIS_KEY, MARKER_KEY)),
                        eq(Integer.toString(SessionMemoryService.MAX_STORED_ENTRIES)),
                        eq(TTL_SECONDS));
    }

    /**
     * Which Lua scripts the service ran - robust against varargs matching quirks for "never ran"
     * checks.
     */
    List<Object> executedScripts() {
        return org.mockito.Mockito.mockingDetails(redisTemplate).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("execute"))
                .map(inv -> inv.getArgument(0))
                .toList();
    }
}
