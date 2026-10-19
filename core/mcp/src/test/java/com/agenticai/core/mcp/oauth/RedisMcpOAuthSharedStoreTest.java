package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.oauth.McpOAuthSharedStore.CachedToken;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Two {@link RedisMcpOAuthSharedStore} instances share ONE fake Redis (a map) and stand in for two
 * replicas - the scenario ADR-0014 section 5 calls out.
 */
class RedisMcpOAuthSharedStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");
    private static final String RESOURCE = "https://mcp.example.com/mcp";

    private final Map<String, String> data = new HashMap<>();
    private EncryptionService encryption;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private OAuthProviderTestSupport.MutableClock clock;
    private RedisMcpOAuthSharedStore a;
    private RedisMcpOAuthSharedStore b;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        encryption = OAuthProviderTestSupport.encryption((byte) 7);
        clock = new OAuthProviderTestSupport.MutableClock(T0);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> data.get(inv.<String>getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> data.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        doAnswer(inv -> {
                    data.put(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        doAnswer(inv -> data.remove(inv.<String>getArgument(0)) != null)
                .when(redis)
                .delete(anyString());
        // The Lua compare-and-delete release: free the lock only when the caller still owns it.
        doAnswer(inv -> {
                    List<String> keys = inv.getArgument(1);
                    String owner = inv.getArgument(2);
                    if (owner.equals(data.get(keys.get(0)))) {
                        data.remove(keys.get(0));
                        return 1L;
                    }
                    return 0L;
                })
                .when(redis)
                .execute(any(RedisScript.class), anyList(), anyString());
        a = new RedisMcpOAuthSharedStore(redis, encryption, clock);
        b = new RedisMcpOAuthSharedStore(redis, encryption, clock);
    }

    // --------------------------------------------------- single-use state

    @Test
    void aStateNonceIsConsumedAtomicallyAcrossReplicas() {
        Instant expiry = T0.plusSeconds(600);
        assertTrue(a.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, "nonce-1", expiry));
        assertFalse(b.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, "nonce-1", expiry));
        assertFalse(a.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, "nonce-1", expiry));
        // Provider-flow nonces live in their own namespace.
        assertTrue(b.claimNonce(McpOAuthSharedStore.STATE_KIND_PROVIDER, "nonce-1", expiry));
        assertTrue(data.keySet().stream().anyMatch(k -> k.startsWith("mcp:oauth:state:")));
    }

    @Test
    void claimNonceFailsClosedWhenRedisIsDown() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        OAuthStateException e =
                assertThrows(
                        OAuthStateException.class,
                        () -> a.claimNonce(
                                McpOAuthSharedStore.STATE_KIND_REMOTE, "n", T0.plusSeconds(600)));
        assertEquals(OAuthStateException.INVALID, e.reason());
    }

    // ------------------------------------------------------- token cache

    @Test
    void aTokenWrittenOnOneReplicaReadsBackOnTheOtherEncryptedAtRest() {
        a.putToken("tenant-a", RESOURCE, new CachedToken("at-plain", T0.plusSeconds(3600)));

        String key = data.keySet().stream()
                .filter(k -> k.startsWith(RedisMcpOAuthSharedStore.TOKEN_PREFIX + "tenant-a:"))
                .findFirst()
                .orElseThrow();
        String raw = data.get(key);
        assertFalse(raw.contains("at-plain"), "the access token must be encrypted before it reaches Valkey");

        Optional<CachedToken> hit = b.token("tenant-a", RESOURCE);
        assertTrue(hit.isPresent());
        assertEquals("at-plain", hit.get().accessToken());
        assertEquals(T0.plusSeconds(3600).toEpochMilli(), hit.get().validUntil().toEpochMilli());
    }

    @Test
    void aTokenWithoutExpiryGetsACappedTtlAndReadsBack() {
        a.putToken("tenant-a", RESOURCE, new CachedToken("at-forever", null));
        Optional<CachedToken> hit = b.token("tenant-a", RESOURCE);
        assertTrue(hit.isPresent());
        assertEquals("at-forever", hit.get().accessToken());
        assertNull(hit.get().validUntil());
    }

    @Test
    void anAlreadyExpiredTokenIsNotWritten() {
        a.putToken("tenant-a", RESOURCE, new CachedToken("dead", T0.minusSeconds(1)));
        assertTrue(data.isEmpty());
    }

    @Test
    void removeTokenOnOneReplicaForgetsItEverywhere() {
        a.putToken("tenant-a", RESOURCE, new CachedToken("at-1", T0.plusSeconds(3600)));
        b.removeToken("tenant-a", RESOURCE);
        // Redis-side miss also clears the local shadow: no stale token survives a remote disconnect.
        assertEquals(Optional.empty(), a.token("tenant-a", RESOURCE));
        assertEquals(Optional.empty(), b.token("tenant-a", RESOURCE));
    }

    @Test
    void aRedisOutageFallsBackToTheLocalShadow() {
        a.putToken("tenant-a", RESOURCE, new CachedToken("at-1", T0.plusSeconds(3600)));
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertEquals("at-1", a.token("tenant-a", RESOURCE).orElseThrow().accessToken());
    }

    // -------------------------------------------------- refresh lock

    @Test
    void theRefreshLockRunsTheActionAndReleasesTheLease() {
        String out = a.withRefreshLock("tenant-a", RESOURCE, () -> "done");
        assertEquals("done", out);
        assertTrue(data.keySet().stream().noneMatch(k -> k.startsWith(RedisMcpOAuthSharedStore.LOCK_PREFIX)),
                "the compare-and-delete release must free the lease");
    }

    @Test
    void theRefreshLockIsReentrantOnTheSameThread() {
        // disconnect -> quiet access-token lookup -> currentAccessToken re-enters the same key.
        String out = a.withRefreshLock("tenant-a", RESOURCE,
            () -> a.withRefreshLock("tenant-a", RESOURCE, () -> "inner"));
        assertEquals("inner", out);
    }

    @Test
    void aLockHeldByAnotherOwnerTimesOutAndProceedsFailOpen() {
        String lockKey = RedisMcpOAuthSharedStore.LOCK_PREFIX + "tenant-a:"
                + RedisMcpOAuthSharedStore.fingerprint(RESOURCE);
        data.put(lockKey, "another-replica");
        RedisMcpOAuthSharedStore impatient =
                new RedisMcpOAuthSharedStore(
                        redis, encryption, clock, new InMemoryMcpOAuthSharedStore(clock),
                        Duration.ofMillis(120), 10);
        String out = impatient.withRefreshLock("tenant-a", RESOURCE, () -> "proceeded");
        assertEquals("proceeded", out);
    }

    @Test
    void aRedisOutageDuringTheLockStillRunsTheAction() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        String out = a.withRefreshLock("tenant-a", RESOURCE, () -> "proceeded");
        assertEquals("proceeded", out);
    }
}
