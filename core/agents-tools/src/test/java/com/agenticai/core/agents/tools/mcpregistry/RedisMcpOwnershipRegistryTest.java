package com.agenticai.core.agents.tools.mcpregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Two registries share ONE fake Redis (a map with explicit expiry) and stand in for two replicas.
 */
class RedisMcpOwnershipRegistryTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final Map<String, String> data = new HashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisMcpOwnershipRegistry a;
    private RedisMcpOwnershipRegistry b;
    private final UUID reg = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> data.get(inv.<String>getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(
                        inv -> data.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        doAnswer(inv -> data.remove(inv.<String>getArgument(0)) != null)
                .when(redis)
                .delete(anyString());
        a = new RedisMcpOwnershipRegistry(redis, "replica-a", TTL);
        b = new RedisMcpOwnershipRegistry(redis, "replica-b", TTL);
    }

    private void expire(String tenant) {
        data.remove(RedisMcpOwnershipRegistry.key(tenant, reg)); // simulates TTL lapse
    }

    @Test
    void firstClaimWinsAndTheOtherReplicaCannotTakeAValidLease() {
        assertTrue(a.claim("t1", reg));
        assertFalse(b.claim("t1", reg));
        assertEquals("replica-a", b.ownerOf("t1", reg).orElseThrow());
    }

    @Test
    void claimByCurrentOwnerRenewsTheTtl() {
        assertTrue(a.claim("t1", reg));
        assertTrue(a.claim("t1", reg));
        verify(redis).expire(RedisMcpOwnershipRegistry.key("t1", reg), TTL);
    }

    @Test
    void takeoverIsPossibleOnlyAfterTheLeaseExpires() {
        assertTrue(a.claim("t1", reg));
        assertFalse(b.claim("t1", reg));
        expire("t1");
        assertTrue(b.claim("t1", reg));
        assertEquals("replica-b", a.ownerOf("t1", reg).orElseThrow());
    }

    @Test
    void keysAreTenantScoped() {
        assertTrue(a.claim("t1", reg));
        assertTrue(
                b.claim("t2", reg),
                "same registration id under another tenant is a different lease");
        assertTrue(data.containsKey("mcp:owner:t1:" + reg));
        assertTrue(data.containsKey("mcp:owner:t2:" + reg));
    }

    @Test
    void heartbeatRenewsNeededLeasesAndReleasesUnneededOnesAfterGrace() {
        a.claim("t1", reg);
        a.renewOwned((t, id) -> true, Duration.ZERO);
        verify(redis).expire(RedisMcpOwnershipRegistry.key("t1", reg), TTL);
        assertEquals("replica-a", data.get(RedisMcpOwnershipRegistry.key("t1", reg)));

        a.renewOwned((t, id) -> false, Duration.ZERO); // no live client, grace over
        assertFalse(data.containsKey(RedisMcpOwnershipRegistry.key("t1", reg)));
        assertTrue(b.claim("t1", reg));
    }

    @Test
    void inFlightConnectIsRenewedWithinGraceEvenWithoutALiveClient() {
        a.claim("t1", reg);
        a.renewOwned((t, id) -> false, Duration.ofMinutes(5));
        assertEquals("replica-a", data.get(RedisMcpOwnershipRegistry.key("t1", reg)));
    }

    @Test
    void releaseOnlyDropsOwnLease() {
        a.claim("t1", reg);
        b.release("t1", reg);
        assertEquals("replica-a", data.get(RedisMcpOwnershipRegistry.key("t1", reg)));
        a.release("t1", reg);
        assertFalse(data.containsKey(RedisMcpOwnershipRegistry.key("t1", reg)));
    }

    @Test
    void redisFailureFailsOpenToLocalOwnership() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertTrue(a.claim("t1", reg), "claim must answer true so callers run locally");
        assertTrue(a.ownerOf("t1", reg).isEmpty());
        a.release("t1", reg);
        a.renewOwned((t, id) -> true, Duration.ZERO);
        assertTrue(a.liveReplicas().isEmpty());
    }
}
