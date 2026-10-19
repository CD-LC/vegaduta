package com.agenticai.core.workflow.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link WorkflowOutputDedupGuard} backs the F-17 idempotency guarantee for
 * the Output node's external sends - at-least-once execution (Temporal
 * Activity retries, node retries) must not double-send. Mocks {@link
 * StringRedisTemplate}/{@link ValueOperations} the same manual {@code
 * mock(X.class)} + constructor-injection way {@code
 * InboundMessageDedupServiceTest} (core/omnichannel) established for
 * Redis-backed guards - no embedded/Testcontainers Redis.
 */
class WorkflowOutputDedupGuardTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private GovernanceDegradationSignal degradationSignal;
    private WorkflowOutputDedupGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        degradationSignal = mock(GovernanceDegradationSignal.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        guard = new WorkflowOutputDedupGuard(redisTemplate, 10L, degradationSignal);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void identityKeyIsStableForTheSameRunAndNode() {
        assertEquals(
            guard.identityKey("email", "run-1", "node-1"),
            guard.identityKey("email", "run-1", "node-1")
        );
    }

    @Test
    void identityKeyDiffersAcrossRunsNodesAndDestinations() {
        String base = guard.identityKey("email", "run-1", "node-1");

        assertNotEquals(base, guard.identityKey("email", "run-2", "node-1"));
        assertNotEquals(base, guard.identityKey("email", "run-1", "node-2"));
        assertNotEquals(base, guard.identityKey("telegram", "run-1", "node-1"));
    }

    @Test
    void identityKeyIsTenantScoped() {
        TenantContext.set(TenantContext.of("tenant-42", "user-1", Set.of()));

        String key = guard.identityKey("whatsapp", "run-1", "node-1");

        assertTrue(key.startsWith("agentic:workflow-output-dedup:tenant-42:whatsapp:run-1:node-1"));
    }

    @Test
    void identityKeyDiffersAcrossTenantsForTheSameRunAndNode() {
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        String tenantAKey = guard.identityKey("email", "run-1", "node-1");
        TenantContext.clear();
        TenantContext.set(TenantContext.of("tenant-b", "user-1", Set.of()));
        String tenantBKey = guard.identityKey("email", "run-1", "node-1");

        assertFalse(tenantAKey.equals(tenantBKey));
    }

    @Test
    void tryClaimClaimsAFirstTimeKeyWithTheConfiguredTtl() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofMinutes(10)))).thenReturn(true);

        assertTrue(guard.tryClaim("some-key"));

        verify(valueOperations).setIfAbsent("some-key", "1", Duration.ofMinutes(10));
    }

    @Test
    void tryClaimReturnsFalseForAnAlreadyClaimedKey() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        assertFalse(guard.tryClaim("some-key"));

        verify(degradationSignal, never()).failOpen(anyString(), any());
    }

    @Test
    void tryClaimFailsOpenAndReportsDegradationWhenRedisIsUnreachable() {
        RedisConnectionFailureException redisDown = new RedisConnectionFailureException("connection refused");
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenThrow(redisDown);

        assertTrue(guard.tryClaim("some-key"));

        verify(degradationSignal).failOpen("workflow-output-dedup", redisDown);
    }

    @Test
    void tryClaimTreatsANullSetIfAbsentResultAsAClaimAndReportsIt() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(null);

        assertTrue(guard.tryClaim("some-key"));

        verify(degradationSignal).failOpen("workflow-output-dedup", null);
    }

    @Test
    void releaseDeletesTheKeyAndSwallowsRedisFailures() {
        guard.release("some-key");
        verify(redisTemplate).delete("some-key");

        when(redisTemplate.delete(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        guard.release("some-key");
        // No exception: a release failure must never mask the send failure
        // that triggered it (see the method's javadoc).
    }
}
