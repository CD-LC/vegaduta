package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * SCAN mechanics of {@link RedisTenantDataPurgeService}: a single pass however many families,
 * multi-batch scans, duplicate keys, an empty keyspace, a cursor that is closed even when the scan
 * fails, what a mid-scan failure still deletes, the Redis Cluster per-master path, and that the
 * blocking {@code KEYS} command is never issued. Which keys belong to the tenant is covered by
 * {@link RedisTenantDataPurgeServiceTest}.
 */
class RedisTenantDataPurgeScanTest {

    private static final String RATE = "agentic:workflow-trigger-rate:";

    private StringRedisTemplate redis;
    private final List<List<String>> deleteCalls = new ArrayList<>();
    private RedisTenantDataPurgeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.delete(ArgumentMatchers.<String>anyCollection()))
                .thenAnswer(
                        inv -> {
                            Collection<String> keys = inv.getArgument(0);
                            deleteCalls.add(new ArrayList<>(keys));
                            return (long) keys.size();
                        });
        service = new RedisTenantDataPurgeService(redis, new ObjectMapper());
    }

    private static List<String> keys(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(RATE + "acme:wf-" + i);
        }
        return out;
    }

    private List<String> scanDeleted() {
        return deleteCalls.stream().flatMap(List::stream).filter(k -> k.startsWith(RATE)).toList();
    }

    @Test
    void scanKeysSpanningSeveralBatchesAreAllDeletedInBoundedBatches() {
        int total = RedisTenantDataPurgeService.SCAN_BATCH * 2 + 37;
        FakeCursor<String> cursor = new FakeCursor<>(keys(total));
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        service.purgeForTenant("acme", List.of(), List.of());

        assertEquals(keys(total), scanDeleted());
        assertTrue(
                deleteCalls.stream()
                        .allMatch(c -> c.size() <= RedisTenantDataPurgeService.SCAN_BATCH));
        assertTrue(cursor.isClosed());
    }

    @Test
    void duplicateKeysFromScanAreDeletedOnlyOncePerBatch() {
        when(redis.scan(any(ScanOptions.class)))
                .thenReturn(
                        new FakeCursor<>(
                                List.of(RATE + "acme:a", RATE + "acme:a", RATE + "acme:b")));

        service.purgeForTenant("acme", List.of(), List.of());

        assertEquals(List.of(RATE + "acme:a", RATE + "acme:b"), scanDeleted());
    }

    @Test
    void theKeyspaceIsScannedExactlyOnceWhateverTheNumberOfFamilies() {
        FakeCursor<String> cursor = new FakeCursor<>(List.of());
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        assertDoesNotThrow(() -> service.purgeForTenant("acme", List.of(), List.of()));

        // One SCAN over agentic:* with a COUNT hint (it was 7 passes, one per family).
        verify(redis, times(1))
                .scan(
                        ArgumentMatchers.argThat(
                                (ScanOptions o) ->
                                        "agentic:*".equals(o.getPattern())
                                                && o.getCount() != null
                                                && o.getCount()
                                                        == RedisTenantDataPurgeService.SCAN_BATCH));
        assertTrue(cursor.isClosed());
        assertEquals(2, deleteCalls.size()); // only the two exact-key deletes ran
        verify(redis, never()).keys(anyString());
    }

    @Test
    void aMidScanFailureStillDeletesWhatWasAlreadyCollectedAndNeverThrows() {
        // 3 matching keys served, then the connection resets: the 3 collected keys must still go.
        FakeCursor<String> cursor = new FakeCursor<>(keys(10), 3);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        assertDoesNotThrow(() -> service.purgeForTenant("acme", List.of(), List.of()));

        assertEquals(keys(3), scanDeleted());
        assertTrue(cursor.isClosed());
    }

    @Test
    void aMidScanFailureAfterFullBatchesKeepsTheFlushedAndThePartialBatch() {
        int served = RedisTenantDataPurgeService.SCAN_BATCH + 20;
        FakeCursor<String> cursor =
                new FakeCursor<>(keys(served + 100), served); // fails after batch + 20
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        assertDoesNotThrow(() -> service.purgeForTenant("acme", List.of(), List.of()));

        assertEquals(keys(served), scanDeleted());
    }

    // ---------------------------------------------------------------- Redis Cluster

    @SuppressWarnings("unchecked")
    private RedisClusterConnection clusterWith(
            List<FakeCursor<byte[]>> masterCursors, RedisClusterNode... nodes) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisClusterConnection connection = mock(RedisClusterConnection.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getClusterConnection()).thenReturn(connection);
        when(connection.clusterGetNodes()).thenReturn(List.of(nodes));
        int i = 0;
        for (RedisClusterNode node : nodes) {
            if (node.isMaster() && !node.isMarkedAsFail()) {
                when(connection.scan(ArgumentMatchers.eq(node), any(ScanOptions.class)))
                        .thenReturn(masterCursors.get(i++));
            }
        }
        return connection;
    }

    private static RedisClusterNode node(boolean master, boolean fail) {
        RedisClusterNode n = mock(RedisClusterNode.class);
        when(n.isMaster()).thenReturn(master);
        when(n.isMarkedAsFail()).thenReturn(fail);
        return n;
    }

    private static List<byte[]> bytes(List<String> keys) {
        return keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toList();
    }

    @Test
    void onRedisClusterEveryMasterIsScannedAndReplicasAndFailedNodesAreNot() {
        FakeCursor<byte[]> a = new FakeCursor<>(bytes(List.of(RATE + "acme:on-node-a")));
        FakeCursor<byte[]> b = new FakeCursor<>(bytes(List.of(RATE + "acme:on-node-b")));
        RedisClusterNode masterA = node(true, false);
        RedisClusterNode masterB = node(true, false);
        RedisClusterNode replica = node(false, false);
        RedisClusterNode failed = node(true, true);
        RedisClusterConnection connection =
                clusterWith(List.of(a, b), masterA, replica, masterB, failed);

        service.purgeForTenant("acme", List.of(), List.of());

        assertEquals(List.of(RATE + "acme:on-node-a", RATE + "acme:on-node-b"), scanDeleted());
        verify(connection).scan(ArgumentMatchers.eq(masterA), any(ScanOptions.class));
        verify(connection).scan(ArgumentMatchers.eq(masterB), any(ScanOptions.class));
        verify(connection, never()).scan(ArgumentMatchers.eq(replica), any(ScanOptions.class));
        verify(connection, never()).scan(ArgumentMatchers.eq(failed), any(ScanOptions.class));
        assertTrue(a.isClosed() && b.isClosed());
        verify(connection).close();
        // The template's single-connection scan (not fanned out on a cluster) is not used.
        verify(redis, never()).scan(any(ScanOptions.class));
    }

    @Test
    void aStandaloneFactoryThatRejectsAClusterConnectionFallsBackToTheTemplateScan() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getClusterConnection())
                .thenThrow(new InvalidDataAccessApiUsageException("Cluster is not configured"));
        when(redis.scan(any(ScanOptions.class)))
                .thenReturn(new FakeCursor<>(List.of(RATE + "acme:x")));

        service.purgeForTenant("acme", List.of(), List.of());

        assertEquals(List.of(RATE + "acme:x"), scanDeleted());
    }
}
