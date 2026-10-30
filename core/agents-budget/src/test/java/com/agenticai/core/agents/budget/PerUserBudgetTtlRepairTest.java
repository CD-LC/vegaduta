package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Covers {@link PerUserBudgetTtlRepair}'s self-heal: only TTL {@code -1} (exists, no expiry) is
 * repaired, every other answer is left alone, and the whole path fails open on a Redis error. The
 * sweep ({@code repairPrefix}) is covered with a stubbed scan cursor - no Redis needed.
 */
class PerUserBudgetTtlRepairTest {

    private StringRedisTemplate redisTemplate;
    private PerUserBudgetTtlRepair repair;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        repair = new PerUserBudgetTtlRepair(redisTemplate);
    }

    @Test
    void aKeyWithNoExpiryGetsTheWindowTtl() {
        when(redisTemplate.getExpire("k")).thenReturn(-1L);

        assertThat(repair.repairMissingTtl("k", 86_400)).isTrue();
        verify(redisTemplate).expire("k", Duration.ofSeconds(86_400));
    }

    @Test
    void aHealthyTtlIsLeftAlone() {
        when(redisTemplate.getExpire("k")).thenReturn(43_200L);

        assertThat(repair.repairMissingTtl("k", 86_400)).isFalse();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void anUnknownTtlAnswerIsLeftAlone() {
        when(redisTemplate.getExpire("k")).thenReturn(null);

        assertThat(repair.repairMissingTtl("k", 86_400)).isFalse();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void aRedisErrorFailsOpenSoTheNextReadCanRetry() {
        when(redisTemplate.getExpire("k")).thenThrow(new RuntimeException("redis down"));

        assertThat(repair.repairMissingTtl("k", 86_400)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void repairPrefixRepairsExactlyTheKeysMissingTtls() {
        Cursor<String> cursor = mock(Cursor.class);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, true, false);
        when(cursor.next()).thenReturn("stuck-1", "healthy", "stuck-2");
        when(redisTemplate.getExpire("stuck-1")).thenReturn(-1L);
        when(redisTemplate.getExpire("healthy")).thenReturn(3_000L);
        when(redisTemplate.getExpire("stuck-2")).thenReturn(-1L);

        assertThat(repair.repairPrefix("agentic:per-user-budget:", 86_400)).isEqualTo(2);

        verify(redisTemplate).expire("stuck-1", Duration.ofSeconds(86_400));
        verify(redisTemplate).expire("stuck-2", Duration.ofSeconds(86_400));
        verify(redisTemplate, never()).expire(eq("healthy"), any(Duration.class));
        // The scan glob the counters sweep is prefix + "*".
        org.mockito.ArgumentCaptor<ScanOptions> options =
                org.mockito.ArgumentCaptor.forClass(ScanOptions.class);
        verify(redisTemplate).scan(options.capture());
        assertThat(options.getValue().getPattern()).isEqualTo("agentic:per-user-budget:*");
    }

    @Test
    void aScanFailureReportsZeroRepairs() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("down"));

        assertThat(repair.repairPrefix("agentic:per-user-budget:", 86_400)).isZero();
    }

    @Test
    void theCountersSweepCoversBothSpendAndCreditNamespaces() {
        PerUserBudgetCounters counters =
                new PerUserBudgetCounters(
                        redisTemplate,
                        mock(org.springframework.beans.factory.ObjectProvider.class));
        Cursor<String> c1 = emptyCursor();
        Cursor<String> c2 = emptyCursor();
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(c1, c2);

        counters.repairAllPrefixes(86_400);

        // Two scans: one per keyspace prefix.
        org.mockito.ArgumentCaptor<ScanOptions> options =
                org.mockito.ArgumentCaptor.forClass(ScanOptions.class);
        verify(redisTemplate, org.mockito.Mockito.times(2)).scan(options.capture());
        assertThat(options.getAllValues().stream().map(ScanOptions::getPattern))
                .containsExactlyInAnyOrder(
                        "agentic:per-user-budget:*", "agentic:per-user-budget-credit:*");
    }

    @SuppressWarnings("unchecked")
    private static Cursor<String> emptyCursor() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        return cursor;
    }
}
