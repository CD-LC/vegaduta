package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.demo.DemoTranscriptEmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * SEC-004: chat history is keyed by a server-derived id scoped to tenant + agent + principal +
 * client session id, so the SAME client-chosen session id used by another tenant, another user or
 * against another agent reads and writes a disjoint history.
 *
 * <p>Runs {@link SessionMemoryService} against a tiny in-memory stand-in for the Redis list
 * operations it uses (RPUSH / LRANGE / LTRIM), so "disjoint" is demonstrated on real stored data,
 * not on mock-call bookkeeping.
 */
class SessionMemoryScopeTest {

    private static final UUID AGENT_1 = UUID.randomUUID();
    private static final UUID AGENT_2 = UUID.randomUUID();
    private static final String CLIENT_ID = "11111111-2222-3333-4444-555555555555";

    private final Map<String, List<String>> lists = new HashMap<>();
    private SessionMemoryService memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(listOps.rightPush(anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            List<String> list =
                                    lists.computeIfAbsent(
                                            inv.getArgument(0), k -> new ArrayList<>());
                            list.add(inv.getArgument(1));
                            return (long) list.size();
                        });
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenAnswer(
                        inv -> {
                            List<String> list = lists.getOrDefault(inv.getArgument(0), List.of());
                            int size = list.size();
                            long start = inv.getArgument(1);
                            long end = inv.getArgument(2);
                            int from = (int) Math.max(0, start < 0 ? size + start : start);
                            int to = (int) Math.min(size - 1, end < 0 ? size + end : end);
                            return from > to
                                    ? List.of()
                                    : new ArrayList<>(list.subList(from, to + 1));
                        });
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        ObjectProvider<SessionSummaryService> noSummariser = mock(ObjectProvider.class);
        memory =
                new SessionMemoryService(
                        redis,
                        new ObjectMapper().findAndRegisterModules(),
                        settings,
                        noSummariser,
                        24,
                        40,
                        32000,
                        8000,
                        10000);
    }

    private void say(String memoryId, String text) {
        memory.appendTurn(memoryId, ChatTurn.user(text));
    }

    private List<String> read(String memoryId) {
        return memory.getHistory(memoryId).stream().map(ChatTurn::content).toList();
    }

    @Test
    void twoTenantsWithTheSameClientSessionIdGetDisjointHistories() {
        String tenantA =
                SessionMemoryService.scopedSessionId("tenant_a", AGENT_1, "alice", CLIENT_ID);
        String tenantB =
                SessionMemoryService.scopedSessionId("tenant_b", AGENT_1, "alice", CLIENT_ID);
        say(tenantA, "tenant A secret: acquisition target is Foo Corp");

        assertThat(tenantA).isNotEqualTo(tenantB);
        assertThat(read(tenantB)).isEmpty();
        say(tenantB, "injected by tenant B");
        assertThat(read(tenantA))
                .containsExactly("tenant A secret: acquisition target is Foo Corp");
    }

    @Test
    void sameTenantDifferentUsersGetDisjointHistories() {
        String alice =
                SessionMemoryService.scopedSessionId("tenant_a", AGENT_1, "alice-sub", CLIENT_ID);
        String mallory =
                SessionMemoryService.scopedSessionId("tenant_a", AGENT_1, "mallory-sub", CLIENT_ID);
        say(alice, "alice's salary is 100");

        assertThat(read(mallory)).isEmpty();
        say(mallory, "mallory's turn");
        assertThat(read(alice)).containsExactly("alice's salary is 100");
    }

    @Test
    void sameUserDifferentAgentsGetDisjointHistories() {
        String hr = SessionMemoryService.scopedSessionId("tenant_a", AGENT_1, "alice", CLIENT_ID);
        String sales =
                SessionMemoryService.scopedSessionId("tenant_a", AGENT_2, "alice", CLIENT_ID);
        say(hr, "HR-only context");
        assertThat(read(sales)).isEmpty();
    }

    @Test
    void theRawClientIdIsNeverTheStorageKey() {
        String scoped =
                SessionMemoryService.scopedSessionId("tenant_a", AGENT_1, "alice", CLIENT_ID);
        say(scoped, "hello");
        assertThat(lists).containsOnlyKeys("agentic:session:" + scoped);
        assertThat(lists).doesNotContainKey("agentic:session:" + CLIENT_ID);
        assertThat(scoped)
                .startsWith(SessionMemoryService.SCOPED_ID_PREFIX)
                .doesNotContain(CLIENT_ID);
    }

    @Test
    void derivationIsDeterministicAndDelimiterSafe() {
        assertThat(SessionMemoryService.scopedSessionId("t", AGENT_1, "u", "s"))
                .isEqualTo(SessionMemoryService.scopedSessionId("t", AGENT_1, "u", "s"));
        // Shifting bytes between components must not collide (length-prefixed hashing).
        assertThat(SessionMemoryService.scopedSessionId("t", AGENT_1, "a:b", "c"))
                .isNotEqualTo(SessionMemoryService.scopedSessionId("t", AGENT_1, "a", "b:c"));
        assertThat(SessionMemoryService.scopedSessionId("t", AGENT_1, "", "x"))
                .isNotEqualTo(SessionMemoryService.scopedSessionId("t", AGENT_1, null, "x:"));
    }

    /**
     * core/tenancy's demo transcript mailer cannot import this module and re-implements the
     * derivation; if the two ever diverged, the transcript would silently find nothing.
     */
    @Test
    void demoTranscriptMailerUsesTheIdenticalDerivation() {
        for (String principal : new String[] {"demo:" + UUID.randomUUID(), "", null}) {
            assertThat(
                            DemoTranscriptEmailService.scopedSessionId(
                                    "demo_schema", AGENT_1, principal, CLIENT_ID))
                    .isEqualTo(
                            SessionMemoryService.scopedSessionId(
                                    "demo_schema", AGENT_1, principal, CLIENT_ID));
        }
        assertThat(DemoTranscriptEmailService.scopedSessionId(null, null, null, null))
                .isEqualTo(SessionMemoryService.scopedSessionId(null, null, null, null));
    }
}
