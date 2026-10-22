package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs {@link SessionMemoryService#appendTurn} twice against a REAL Redis-protocol server - once
 * with the legacy multi-call sequence, once with the single-script path - and requires identical
 * stored state and identical fold batches, then counts Redis round trips for each.
 *
 * <p>Skipped unless {@code VEGADUTA_TEST_REDIS} is set to {@code host:port} of a throwaway Redis or
 * Valkey (the Lua is the part a mock cannot check). Example: {@code docker run --rm -p 16379:6379
 * valkey/valkey:8-alpine} then {@code VEGADUTA_TEST_REDIS=<host>:16379 mvn -pl agents test
 * -Dtest=SessionAppendScriptRedisTest}.
 */
class SessionAppendScriptRedisTest {

    private static final String TARGET = System.getenv("VEGADUTA_TEST_REDIS");
    private static final long TTL_SECONDS = 24 * 3600L;

    private static LettuceConnectionFactory factory;
    private static CountingTemplate redis;
    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.withJavaTime();

    /**
     * Counts template calls that reach the connection (one script call is one command; this does
     * not see sockets).
     */
    static final class CountingTemplate extends StringRedisTemplate {
        final AtomicInteger roundTrips = new AtomicInteger();

        CountingTemplate(LettuceConnectionFactory f) {
            super(f);
        }

        @Override
        public <T> T execute(RedisCallback<T> action, boolean exposeConnection, boolean pipeline) {
            roundTrips.incrementAndGet();
            return super.execute(action, exposeConnection, pipeline);
        }
    }

    @BeforeAll
    static void connect() {
        assumeTrue(TARGET != null && TARGET.contains(":"), "VEGADUTA_TEST_REDIS=host:port not set");
        String[] hp = TARGET.split(":");
        factory =
                new LettuceConnectionFactory(
                        new RedisStandaloneConfiguration(hp[0], Integer.parseInt(hp[1])));
        factory.afterPropertiesSet();
        redis = new CountingTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private SessionMemoryService service(boolean single, SessionSummaryService summariser) {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), Mockito.anyInt())).thenAnswer(i -> i.getArgument(1));
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionSummaryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(summariser);
        SessionMemoryService s =
                new SessionMemoryService(redis, mapper, settings, provider, 24, 40, 32000, 8000, 0);
        s.setSingleRoundTripAppend(single);
        return s;
    }

    private SessionSummaryService summariser() {
        SessionSummaryService s = mock(SessionSummaryService.class);
        when(s.isEnabled()).thenReturn(true);
        return s;
    }

    private ChatTurn turn(int i) {
        return new ChatTurn(
                i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                "turn-" + i,
                Instant.parse("2026-08-11T00:00:00Z"));
    }

    private static void tenant(boolean present) {
        if (present) {
            TenantContext.set(TenantContext.of("tenant-1", "alice", Set.of("USER")));
        } else {
            TenantContext.clear();
        }
    }

    private record Snapshot(
            List<String> list,
            String marker,
            String owner,
            long listTtl,
            long markerTtl,
            long ownerTtl,
            List<List<String>> foldBatches) {}

    /**
     * Appends {@code n} turns under {@code sessionId} and captures the stored state plus every
     * batch handed to the fold.
     */
    private Snapshot run(boolean single, boolean withSummariser, boolean withTenant, int n) {
        String sid = "c1:test-" + UUID.randomUUID();
        SessionSummaryService sum = withSummariser ? summariser() : null;
        SessionMemoryService svc = service(single, sum);
        tenant(withTenant);
        for (int i = 0; i < n; i++) {
            svc.appendTurn(sid, turn(i));
        }
        List<List<String>> batches = new ArrayList<>();
        if (sum != null) {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ChatTurn>> cap =
                    ArgumentCaptor.forClass((Class<List<ChatTurn>>) (Class<?>) List.class);
            Mockito.verify(sum, Mockito.atLeast(0))
                    .foldAsync(anyString(), any(), any(), cap.capture(), any());
            cap.getAllValues()
                    .forEach(b -> batches.add(b.stream().map(ChatTurn::content).toList()));
        }
        String key = "agentic:session:" + sid;
        String markerKey = "agentic:session-folded:" + sid;
        String ownerKey = "agentic:session-owner:" + sid;
        Snapshot snap =
                new Snapshot(
                        redis.opsForList().range(key, 0, -1),
                        redis.opsForValue().get(markerKey),
                        redis.opsForValue().get(ownerKey),
                        ttl(key),
                        ttl(markerKey),
                        ttl(ownerKey),
                        batches);
        redis.delete(List.of(key, markerKey, ownerKey));
        return snap;
    }

    /** Runs {@code ACL <args>} on the test server over a throwaway Lettuce connection. */
    private static void acl(String... args) {
        String[] hp = TARGET.split(":");
        io.lettuce.core.RedisClient client =
                io.lettuce.core.RedisClient.create(
                        io.lettuce.core.RedisURI.create(hp[0], Integer.parseInt(hp[1])));
        try (io.lettuce.core.api.StatefulRedisConnection<String, String> conn = client.connect()) {
            io.lettuce.core.protocol.CommandArgs<String, String> ca =
                    new io.lettuce.core.protocol.CommandArgs<>(
                            io.lettuce.core.codec.StringCodec.UTF8);
            for (String a : args) {
                ca.add(a);
            }
            conn.sync()
                    .dispatch(
                            new io.lettuce.core.protocol.ProtocolKeyword() {
                                @Override
                                public byte[] getBytes() {
                                    return "ACL".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                }

                                public String name() {
                                    return "ACL";
                                }
                            },
                            new io.lettuce.core.output.NestedMultiOutput<>(
                                    io.lettuce.core.codec.StringCodec.UTF8),
                            ca);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void aServerThatDeniesScriptsFallsBackWithoutDuplicatingAnyTurn() {
        String[] hp = TARGET.split(":");
        acl("SETUSER", "noscript", "on", "nopass", "~*", "+@all", "-eval", "-evalsha");
        RedisStandaloneConfiguration cfg =
                new RedisStandaloneConfiguration(hp[0], Integer.parseInt(hp[1]));
        cfg.setUsername("noscript");
        LettuceConnectionFactory restricted = new LettuceConnectionFactory(cfg);
        restricted.afterPropertiesSet();
        StringRedisTemplate restrictedRedis = new StringRedisTemplate(restricted);
        restrictedRedis.afterPropertiesSet();
        String sid = "c1:acl-" + UUID.randomUUID();
        String key = "agentic:session:" + sid;
        try {
            PlatformSettingsService settings = mock(PlatformSettingsService.class);
            when(settings.getInt(anyString(), Mockito.anyInt())).thenAnswer(i -> i.getArgument(1));
            @SuppressWarnings("unchecked")
            ObjectProvider<SessionSummaryService> provider = mock(ObjectProvider.class);
            SessionMemoryService svc =
                    new SessionMemoryService(
                            restrictedRedis, mapper, settings, provider, 24, 40, 32000, 8000, 0);
            svc.setSingleRoundTripAppend(true);

            for (int i = 0; i < 3; i++) {
                svc.appendTurn(sid, turn(i));
            }

            assertEquals(
                    List.of("turn-0", "turn-1", "turn-2"),
                    redis.opsForList().range(key, 0, -1).stream()
                            .map(j -> j.replaceAll(".*\"content\":\"(turn-\\d+)\".*", "$1"))
                            .toList(),
                    "each turn stored exactly once");
        } finally {
            redis.delete(key);
            restricted.destroy();
            acl("DELUSER", "noscript");
        }
    }

    @Test
    void concurrentAppendsToOneSessionNeverLoseOrDuplicateATurn() throws Exception {
        String sid = "c1:conc-" + UUID.randomUUID();
        String key = "agentic:session:" + sid;
        SessionMemoryService svc = service(true, null);
        int threads = 8;
        int each = 20;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * each;
                fs.add(
                        pool.submit(
                                () -> {
                                    for (int i = 0; i < each; i++) {
                                        svc.appendTurn(sid, turn(base + i));
                                    }
                                }));
            }
            for (java.util.concurrent.Future<?> f : fs) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
            List<String> stored = redis.opsForList().range(key, 0, -1);
            assertEquals(threads * each, stored.size(), "all 160 appends stored (below the cap)");
            assertEquals(threads * each, stored.stream().distinct().count(), "no duplicates");
        } finally {
            pool.shutdownNow();
            redis.delete(key);
        }
    }

    private long ttl(String key) {
        Long t = redis.getExpire(key);
        return t == null ? -2 : t;
    }

    private static void assertTtl(long actual, String what) {
        assertTrue(actual > TTL_SECONDS - 30 && actual <= TTL_SECONDS, what + " TTL was " + actual);
    }

    private void assertEquivalent(boolean withSummariser, boolean withTenant, int n) {
        Snapshot legacy = run(false, withSummariser, withTenant, n);
        Snapshot script = run(true, withSummariser, withTenant, n);
        assertEquals(legacy.list(), script.list(), "stored list");
        assertEquals(legacy.marker(), script.marker(), "fold marker");
        assertEquals(legacy.owner(), script.owner(), "owner record");
        assertEquals(
                legacy.foldBatches(), script.foldBatches(), "batches handed to the summariser");
        assertEquals(legacy.listTtl() > 0, script.listTtl() > 0);
        assertEquals(legacy.markerTtl() > 0, script.markerTtl() > 0);
        assertEquals(legacy.ownerTtl() > 0, script.ownerTtl() > 0);
        assertTtl(script.listTtl(), "list");
        if (script.marker() != null) {
            assertTtl(script.markerTtl(), "marker");
        }
        if (script.owner() != null) {
            assertTtl(script.ownerTtl(), "owner");
        }
    }

    @Test
    void noSummariserNoTenantMatchesLegacy() {
        assertEquivalent(false, false, 450);
    }

    @Test
    void noSummariserWithTenantMatchesLegacyAndRecordsTheOwner() {
        assertEquivalent(false, true, 450);
        Snapshot s = run(true, false, true, 3);
        assertEquals("tenant-1\nalice", s.owner());
        assertEquals(3, s.list().size());
    }

    @Test
    void summariserWithTenantClaimsTheSameBatchesAndShiftsTheMarkerTheSame() {
        assertEquivalent(true, true, 450);
        Snapshot s = run(true, true, true, 450);
        assertEquals(400, s.list().size(), "storage ceiling");
        assertNotNull(s.marker());
        assertTrue(s.foldBatches().size() >= 5, "several 40-turn batches were claimed");
        assertEquals(List.of("turn-0"), s.foldBatches().get(0).subList(0, 1));
    }

    @Test
    void summariserWithoutTenantTrimsMarkerAwareButClaimsNothing() {
        assertEquivalent(true, false, 450);
        Snapshot s = run(true, true, false, 450);
        assertTrue(s.foldBatches().isEmpty());
        assertNull(s.marker());
    }

    @Test
    void roundTripsPerAppend() {
        int[][] rows = new int[4][2];
        boolean[][] cases = {{false, false}, {false, true}, {true, true}, {true, false}};
        for (int c = 0; c < cases.length; c++) {
            for (int mode = 0; mode < 2; mode++) {
                boolean single = mode == 1;
                String sid = "c1:count-" + UUID.randomUUID();
                SessionMemoryService svc = service(single, cases[c][0] ? summariser() : null);
                tenant(cases[c][1]);
                svc.appendTurn(sid, turn(0)); // warm: the first EVALSHA of a script may load it
                redis.roundTrips.set(0);
                svc.appendTurn(sid, turn(1));
                rows[c][mode] = redis.roundTrips.get();
                redis.delete(
                        List.of(
                                "agentic:session:" + sid,
                                "agentic:session-folded:" + sid,
                                "agentic:session-owner:" + sid));
            }
        }
        System.out.printf(
                "ROUND-TRIPS per appendTurn (legacy -> script): no summariser/no tenant %d -> %d;"
                        + " no summariser/tenant %d -> %d; summariser/tenant %d -> %d; summariser/no"
                        + " tenant %d -> %d%n",
                rows[0][0],
                rows[0][1],
                rows[1][0],
                rows[1][1],
                rows[2][0],
                rows[2][1],
                rows[3][0],
                rows[3][1]);
        for (int[] row : rows) {
            assertEquals(1, row[1], "the script path is one round trip");
            assertTrue(row[0] >= 3, "the legacy path is 3 or more");
        }
    }
}
