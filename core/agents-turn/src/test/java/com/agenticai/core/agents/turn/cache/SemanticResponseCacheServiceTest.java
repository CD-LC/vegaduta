package com.agenticai.core.agents.turn.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The quality-critical logic of the gated semantic cache: a stored answer is
 * returned ONLY when the nearest non-expired entry clears the cosine-similarity
 * threshold, a near-miss returns empty (never a wrong answer), and every failure
 * path fails open to a live LLM call. The pgvector SQL itself is exercised by
 * the integration boot; here JdbcTemplate is mocked so the returned row can be
 * controlled precisely to pin the threshold decision.
 */
class SemanticResponseCacheServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String USER_A = "kc-user-a";
    private static final String USER_B = "kc-user-b";

    private SidecarClient sidecarClient;
    private JdbcTemplate jdbcTemplate;
    private SemanticResponseCacheService service;

    @BeforeEach
    void setUp() {
        sidecarClient = mock(SidecarClient.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(sidecarClient.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        // threshold 0.95 -> a hit requires cosine distance <= 0.05.
        service = new SemanticResponseCacheService(sidecarClient, jdbcTemplate, 0.95, 6, 200);
        // The service resolves a schema-qualified table from TenantContext.
        TenantContext.set(TenantContext.of("tenant_test", "user", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private void stubNearest(SemanticResponseCacheService.CacheHit row) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
            .thenReturn(row == null ? List.of() : List.of(row));
    }

    @Test
    void returnsCachedAnswerWhenNearestEntryClearsTheThreshold() {
        // distance 0.02 -> similarity 0.98 >= 0.95: a hit.
        stubNearest(new SemanticResponseCacheService.CacheHit(0.02, "cached answer"));
        Optional<String> result = service.get(AGENT, USER_A, "what are your hours?");
        assertTrue(result.isPresent());
        assertEquals("cached answer", result.get());
    }

    @Test
    void returnsEmptyOnANearMissBelowTheThreshold() {
        // distance 0.20 -> similarity 0.80 < 0.95: must NOT return the answer.
        stubNearest(new SemanticResponseCacheService.CacheHit(0.20, "a different-question answer"));
        assertTrue(service.get(AGENT, USER_A, "unrelated question").isEmpty());
    }

    @Test
    void returnsEmptyWhenNoRows() {
        stubNearest(null);
        assertTrue(service.get(AGENT, USER_A, "anything").isEmpty());
    }

    @Test
    void failsOpenWhenTheSidecarThrows() {
        when(sidecarClient.embedQuery(anyString())).thenThrow(new RuntimeException("sidecar down"));
        assertTrue(service.get(AGENT, USER_A, "q").isEmpty());
    }

    @Test
    void putSkipsBlankReplies() {
        service.put(AGENT, USER_A, "q", "   ");
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void putInsertsQuestionAnswerAndEmbedding() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
        service.put(AGENT, USER_A, "what are your hours?", "We're open 9-5.");
        // id, agentId, subject_hash, question, answer, embedding-literal, created_at, expires_at
        verify(jdbcTemplate).update(contains("INSERT INTO"), any(), eq(AGENT),
            eq(SemanticResponseCacheService.subjectHash(USER_A)), eq("what are your hours?"),
            eq("We're open 9-5."), anyString(), any(), any());
    }

    // ---- SEC-052: the cache is bound to the caller's principal ------------

    @Test
    @SuppressWarnings("unchecked")
    void lookupIsFilteredByTheCallersSubjectHashSoUserBNeverGetsUserAsAnswer() {
        // The DB only holds a row for user A. Simulate that faithfully: the
        // stub answers ONLY when the query is bound to A's subject hash.
        when(jdbcTemplate.query(contains("subject_hash = ?"), any(RowMapper.class), any(), eq(AGENT),
            eq(SemanticResponseCacheService.subjectHash(USER_A))))
            .thenReturn(List.of(new SemanticResponseCacheService.CacheHit(0.0, "A's personal balance is 42")));

        assertEquals(Optional.of("A's personal balance is 42"), service.get(AGENT, USER_A, "what is my balance?"));
        assertTrue(service.get(AGENT, USER_B, "what is my balance?").isEmpty(),
            "user B must not be served user A's cached, personalised answer");
    }

    @Test
    void subjectHashIsStableAndDistinctPerPrincipal() {
        assertEquals(SemanticResponseCacheService.subjectHash(USER_A), SemanticResponseCacheService.subjectHash(USER_A));
        assertTrue(!SemanticResponseCacheService.subjectHash(USER_A).equals(SemanticResponseCacheService.subjectHash(USER_B)));
        assertTrue(!SemanticResponseCacheService.subjectHash(USER_A).contains(USER_A), "raw subject never stored");
    }
}
