package com.agenticai.core.agents.tools.e2b;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sdlc.dto.SandboxFile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@code app.sandbox.data-analysis.store=redis}: the registry's write-through copy, with two
 * registries (two "replicas") sharing one in-memory fake of the Redis hash and counter commands it
 * uses. Not run against a real Redis.
 */
class SandboxFileRedisMirrorTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "session-1";
    private static final byte[] CSV = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_CSV = "x,y\n9,9\n".getBytes(StandardCharsets.UTF_8);

    // Written by the two writer threads, read by the test thread: thread-safe maps.
    private final Map<String, Map<Object, Object>> hashes = new ConcurrentHashMap<>();
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hash;
    private ValueOperations<String, String> values;
    private SandboxFileRedisMirror mirrorA;
    private SandboxFileRedisMirror mirrorB;
    private SandboxFileRegistry replicaA;
    private SandboxFileRegistry replicaB;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void twoReplicasOneRedis() {
        redis = mock(StringRedisTemplate.class);
        hash = mock(HashOperations.class);
        values = mock(ValueOperations.class);
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(redis.opsForValue()).thenReturn(values);
        when(hash.putIfAbsent(anyString(), any(), any()))
                .thenAnswer(
                        call ->
                                hashes.computeIfAbsent(
                                                        call.<String>getArgument(0),
                                                        k -> new ConcurrentHashMap<>())
                                                .putIfAbsent(
                                                        call.getArgument(1), call.getArgument(2))
                                        == null);
        when(hash.size(anyString()))
                .thenAnswer(
                        call ->
                                (long)
                                        hashes.getOrDefault(call.<String>getArgument(0), Map.of())
                                                .size());
        when(hash.keys(anyString()))
                .thenAnswer(
                        call ->
                                new LinkedHashSet<>(
                                        hashes.getOrDefault(call.<String>getArgument(0), Map.of())
                                                .keySet()));
        when(hash.get(anyString(), any()))
                .thenAnswer(
                        call ->
                                hashes.getOrDefault(call.<String>getArgument(0), Map.of())
                                        .get(call.getArgument(1)));
        when(redis.delete(anyString()))
                .thenAnswer(call -> hashes.remove(call.<String>getArgument(0)) != null);
        when(values.increment(anyString(), anyLong()))
                .thenAnswer(
                        call ->
                                counters.merge(
                                        call.<String>getArgument(0),
                                        call.<Long>getArgument(1),
                                        Long::sum));
        when(values.decrement(anyString(), anyLong()))
                .thenAnswer(
                        call ->
                                counters.merge(
                                        call.<String>getArgument(0),
                                        -call.<Long>getArgument(1),
                                        Long::sum));

        mirrorA = new SandboxFileRedisMirror(redis, Duration.ofMinutes(60));
        mirrorB = new SandboxFileRedisMirror(redis, Duration.ofMinutes(60));
        replicaA = new SandboxFileRegistry(true, 6, 1024 * 1024, 4 * 1024 * 1024, 60);
        replicaB = new SandboxFileRegistry(true, 6, 1024 * 1024, 4 * 1024 * 1024, 60);
        replicaA.useMirror(mirrorA);
        replicaB.useMirror(mirrorB);
    }

    @AfterEach
    void stopWriters() {
        mirrorA.shutdown();
        mirrorB.shutdown();
    }

    @Test
    void aFileRegisteredOnOneReplicaIsMountedByAFollowUpTurnOnTheOther() throws Exception {
        assertThat(replicaA.register(AGENT, SESSION, "sales.csv", "text/csv", CSV)).isTrue();
        mirrorA.awaitQueuedWrites();

        List<SandboxFile> onB = replicaB.filesFor(AGENT, SESSION);

        assertThat(onB).hasSize(1);
        assertThat(onB.get(0).name()).isEqualTo("sales.csv");
        assertThat(onB.get(0).content()).isEqualTo(CSV);
        // Adopted locally: the second read needs no Redis data (the file is already held).
        assertThat(replicaB.filesFor(AGENT, SESSION)).hasSize(1);
    }

    @Test
    void aSecondFileOnTheOtherReplicaIsAddedToTheOneAlreadyThere() throws Exception {
        replicaA.register(AGENT, SESSION, "first.csv", "text/csv", CSV);
        mirrorA.awaitQueuedWrites();
        replicaB.register(AGENT, SESSION, "second.csv", "text/csv", CSV);
        mirrorB.awaitQueuedWrites();

        List<String> names =
                replicaB.filesFor(AGENT, SESSION).stream().map(SandboxFile::name).sorted().toList();

        assertThat(names).containsExactly("first.csv", "second.csv");
    }

    @Test
    void theSameNameUploadedOnTwoReplicasIsRenamedNotOverwritten() throws Exception {
        replicaA.register(AGENT, SESSION, "sales.csv", "text/csv", CSV);
        mirrorA.awaitQueuedWrites();
        // Replica B has no local entry for the session, so its own list gives the upload the same
        // name.
        replicaB.register(AGENT, SESSION, "sales.csv", "text/csv", OTHER_CSV);
        mirrorB.awaitQueuedWrites();

        List<SandboxFile> onA = replicaA.filesFor(AGENT, SESSION);
        List<SandboxFile> onB = replicaB.filesFor(AGENT, SESSION);

        for (List<SandboxFile> files : List.of(onA, onB)) {
            assertThat(files)
                    .extracting(SandboxFile::name)
                    .containsExactlyInAnyOrder("sales.csv", "sales_2.csv");
            assertThat(
                            files.stream()
                                    .filter(f -> f.name().equals("sales.csv"))
                                    .findFirst()
                                    .get()
                                    .content())
                    .isEqualTo(CSV);
            assertThat(
                            files.stream()
                                    .filter(f -> f.name().equals("sales_2.csv"))
                                    .findFirst()
                                    .get()
                                    .content())
                    .isEqualTo(OTHER_CSV);
        }
    }

    @Test
    void withoutAMirrorTheRegistryBehavesAsBefore() {
        SandboxFileRegistry plain =
                new SandboxFileRegistry(true, 6, 1024 * 1024, 4 * 1024 * 1024, 60);

        assertThat(plain.register(AGENT, SESSION, "sales.csv", "text/csv", CSV)).isTrue();
        assertThat(plain.filesFor(AGENT, SESSION)).hasSize(1);
        assertThat(plain.filesFor(AGENT, "other-session")).isEmpty();
    }

    @Test
    void aRedisFailureOnReadFallsBackToTheLocalFilesWithoutThrowing() {
        when(hash.keys(anyString())).thenThrow(new IllegalStateException("redis down"));
        replicaB.register(AGENT, SESSION, "local.csv", "text/csv", CSV);

        List<SandboxFile> files = replicaB.filesFor(AGENT, SESSION);

        assertThat(files).extracting(SandboxFile::name).containsExactly("local.csv");
        assertThat(replicaB.filesFor(AGENT, "never-registered")).isEmpty();
    }

    @Test
    void aHangingRedisOnReadIsBoundedAndFallsBackToTheLocalFiles() {
        CountDownLatch neverReleased = new CountDownLatch(1);
        when(hash.keys(anyString()))
                .thenAnswer(
                        call -> {
                            neverReleased
                                    .await(); // black-holed connection: the real Lettuce call would
                            // wait 60 s
                            return Set.of();
                        });
        replicaB.register(AGENT, SESSION, "local.csv", "text/csv", CSV);

        long started = System.nanoTime();
        List<SandboxFile> files = replicaB.filesFor(AGENT, SESSION);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(files).extracting(SandboxFile::name).containsExactly("local.csv");
        assertThat(elapsedMillis).isLessThan(10_000L);
    }

    @Test
    void aRedisFailureOnWriteNeverFailsTheRegistration() throws Exception {
        doAnswer(
                        call -> {
                            throw new IllegalStateException("redis down");
                        })
                .when(hash)
                .putIfAbsent(anyString(), any(), any());

        assertThat(replicaA.register(AGENT, SESSION, "sales.csv", "text/csv", CSV)).isTrue();
        mirrorA.awaitQueuedWrites();

        assertThat(replicaA.filesFor(AGENT, SESSION)).hasSize(1);
        // The byte reservation made before the failed write is given back.
        assertThat(counters.get(SandboxFileRedisMirror.tenantBudgetKey("-"))).isZero();
    }

    @Test
    void loadMissingSkipsNamesTheCallerAlreadyHoldsAndDoesNotExtendTheTtl() {
        mirrorA.putNow("-", AGENT + ":" + SESSION, "have.csv", CSV, null);
        mirrorA.putNow("-", AGENT + ":" + SESSION, "new.csv", CSV, null);
        Set<String> have = Set.of("have.csv");
        clearInvocations(redis);

        List<SandboxFile> missing = mirrorB.loadMissing("-", AGENT + ":" + SESSION, have);

        assertThat(missing).extracting(SandboxFile::name).containsExactly("new.csv");
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void aTenantOverItsRedisByteBudgetKeepsTheFileLocalOnly() {
        SandboxFileRedisMirror small =
                new SandboxFileRedisMirror(
                        redis, Duration.ofMinutes(60), 6, CSV.length + 2L, 1L << 30);
        try {
            small.putNow("t1", "a:1", "first.csv", CSV, null);
            small.putNow(
                    "t1",
                    "a:1",
                    "second.csv",
                    CSV,
                    null); // over the tenant budget: skipped, reservation undone
            small.putNow("t2", "a:2", "other.csv", CSV, null); // another tenant has its own budget

            assertThat(hashes.get(SandboxFileRedisMirror.redisKey("t1", "a:1")).keySet())
                    .containsExactly("first.csv");
            assertThat(hashes.get(SandboxFileRedisMirror.redisKey("t2", "a:2")).keySet())
                    .containsExactly("other.csv");
            assertThat(counters.get(SandboxFileRedisMirror.tenantBudgetKey("t1")))
                    .isEqualTo((long) CSV.length);
            assertThat(counters.get(SandboxFileRedisMirror.PLATFORM_BUDGET_KEY))
                    .isEqualTo(2L * CSV.length);
        } finally {
            small.shutdown();
        }
    }

    @Test
    void theRedisCopyHoldsAtMostTheSessionFileCap() {
        SandboxFileRedisMirror capped =
                new SandboxFileRedisMirror(redis, Duration.ofMinutes(60), 2, 1L << 30, 1L << 30);
        try {
            capped.putNow("-", "a:1", "one.csv", CSV, null);
            capped.putNow("-", "a:1", "two.csv", CSV, null);
            capped.putNow("-", "a:1", "three.csv", CSV, null);

            assertThat(hashes.get(SandboxFileRedisMirror.redisKey("-", "a:1")).keySet())
                    .containsExactlyInAnyOrder("one.csv", "two.csv");
        } finally {
            capped.shutdown();
        }
    }

    @Test
    void evictingASessionForCapacityDeletesItsRedisCopyAndGivesTheBytesBack() throws Exception {
        long now = 1_000L;
        SandboxFileRegistry tight =
                new SandboxFileRegistry(
                        true,
                        6,
                        1024 * 1024,
                        4 * 1024 * 1024,
                        60 * 60_000L,
                        () -> now,
                        50,
                        1L << 30,
                        1,
                        1L << 30);
        tight.useMirror(mirrorA);
        tight.register(AGENT, "s1", "a.csv", "text/csv", CSV);
        mirrorA.awaitQueuedWrites();
        String firstKey = SandboxFileRedisMirror.redisKey("-", AGENT + ":s1");
        assertThat(hashes).containsKey(firstKey);

        tight.register(
                AGENT,
                "s2",
                "b.csv",
                "text/csv",
                CSV); // the tenant allows ONE session: s1 is evicted
        mirrorA.awaitQueuedWrites();

        assertThat(hashes).doesNotContainKey(firstKey);
        assertThat(hashes).containsKey(SandboxFileRedisMirror.redisKey("-", AGENT + ":s2"));
        assertThat(counters.get(SandboxFileRedisMirror.tenantBudgetKey("-")))
                .isEqualTo((long) CSV.length);
    }

    @Test
    void aSessionAdoptedFromRedisIsHeldToTheTenantSessionCap() throws Exception {
        long now = 1_000L;
        SandboxFileRegistry tight =
                new SandboxFileRegistry(
                        true,
                        6,
                        1024 * 1024,
                        4 * 1024 * 1024,
                        60 * 60_000L,
                        () -> now,
                        50,
                        1L << 30,
                        1,
                        1L << 30);
        tight.useMirror(mirrorB);
        tight.register(AGENT, "local", "a.csv", "text/csv", CSV);
        replicaA.register(AGENT, "remote", "b.csv", "text/csv", OTHER_CSV);
        mirrorA.awaitQueuedWrites();

        List<SandboxFile> adopted = tight.filesFor(AGENT, "remote");

        assertThat(adopted).extracting(SandboxFile::name).containsExactly("b.csv");
        // The cap of ONE session per tenant evicted the older local session to make room (and
        // queued its Redis delete).
        mirrorB.awaitQueuedWrites();
        assertThat(tight.filesFor(AGENT, "local")).isEmpty();
    }
}
