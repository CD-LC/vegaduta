package com.agenticai.core.tenancy;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * One full pass over the keyspace with a cursor-based {@code SCAN}, hiding the difference between a
 * standalone/Sentinel connection and a Redis Cluster.
 *
 * <p>Why the split: {@code StringRedisTemplate.scan(ScanOptions)} runs a single {@code SCAN} on the
 * connection it is given. {@code LettuceClusterKeyCommands} (spring-data-redis 3.5.13) overrides
 * {@code keys(...)} to fan out to every master but does NOT override {@code scan(ScanOptions)}; it
 * only offers {@code scan(RedisClusterNode, ScanOptions)}. A template scan on a cluster is
 * therefore not fanned out by Spring; what Lettuce's cluster support then does with it (scan every
 * node, one node, or fail) was not verified. Visiting every master node with the per-node overload
 * is correct under any of those, so on a cluster this class does that instead. The Spring class
 * layout was read from the bytecode and the logic is unit-tested with mocks; it has not been run
 * against a real Redis Cluster.
 */
final class RedisKeyspaceScanner {

    private final StringRedisTemplate redisTemplate;

    RedisKeyspaceScanner(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Streams every key matching {@code options} to {@code sink}. Keys may be delivered more than
     * once (SCAN guarantees at-least-once). Cursors and the cluster connection are always closed,
     * including when {@code sink} or the scan throws.
     */
    void scan(ScanOptions options, Consumer<String> sink) {
        RedisClusterConnection cluster = clusterConnectionOrNull();
        if (cluster == null) {
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    sink.accept(cursor.next());
                }
            }
            return;
        }
        try (RedisClusterConnection connection = cluster) {
            for (RedisClusterNode node : connection.clusterGetNodes()) {
                // Replicas hold the same keys as their master; scanning them would only duplicate.
                if (!node.isMaster() || node.isMarkedAsFail()) {
                    continue;
                }
                try (Cursor<byte[]> cursor = connection.scan(node, options)) {
                    while (cursor.hasNext()) {
                        sink.accept(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
    }

    /** The cluster connection when the factory is configured for Redis Cluster, else null. */
    private RedisClusterConnection clusterConnectionOrNull() {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            return null;
        }
        try {
            return factory.getClusterConnection();
        } catch (InvalidDataAccessApiUsageException notCluster) {
            // Contract of RedisConnectionFactory#getClusterConnection when no cluster is set up.
            return null;
        }
    }
}
