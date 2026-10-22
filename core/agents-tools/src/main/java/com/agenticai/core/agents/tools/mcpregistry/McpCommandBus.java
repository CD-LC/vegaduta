package com.agenticai.core.agents.tools.mcpregistry;

import com.agenticai.core.json.JsonMappers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-replica command queues on Redis lists ({@code mcp:cmdq:{replicaId}}): LPUSH by the sender,
 * RPOP by the target's poller, so per-target order is FIFO. Replies to request/response commands
 * (TEST) go to a random one-shot key with a short TTL. Only ever used for control calls, never the
 * chat path. Throws on Redis errors; {@link RedisMcpOwnershipGate} decides how to fail.
 */
public final class McpCommandBus {

    static final String QUEUE_PREFIX = "mcp:cmdq:";
    static final String REPLY_PREFIX = "mcp:reply:";
    private static final Duration QUEUE_TTL = Duration.ofMinutes(5);
    private static final Duration REPLY_TTL = Duration.ofMinutes(2);
    private static final long REPLY_POLL_MS = 100;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = JsonMappers.plain();

    public McpCommandBus(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void send(String targetReplica, McpCommand command) {
        try {
            String queue = QUEUE_PREFIX + targetReplica;
            redis.opsForList().leftPush(queue, mapper.writeValueAsString(command));
            redis.expire(queue, QUEUE_TTL);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends and waits up to {@code timeout} for the target to reply; empty on timeout. */
    public Optional<String> sendAndAwait(
            String targetReplica, McpCommand.Op op, String tenant, UUID id, Duration timeout)
            throws InterruptedException {
        String replyKey = REPLY_PREFIX + UUID.randomUUID();
        send(targetReplica, new McpCommand(op, tenant, id, replyKey));
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String reply = redis.opsForValue().get(replyKey);
            if (reply != null) {
                redis.delete(replyKey);
                return Optional.of(reply);
            }
            Thread.sleep(REPLY_POLL_MS);
        }
        return Optional.empty();
    }

    /** As above but carrying a payload (routed tool call). */
    public Optional<String> sendAndAwait(
            String targetReplica,
            McpCommand.Op op,
            String tenant,
            UUID id,
            String payload,
            Duration timeout)
            throws InterruptedException {
        String replyKey = REPLY_PREFIX + UUID.randomUUID();
        send(targetReplica, new McpCommand(op, tenant, id, replyKey, payload));
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String reply = redis.opsForValue().get(replyKey);
            if (reply != null) {
                redis.delete(replyKey);
                return Optional.of(reply);
            }
            Thread.sleep(REPLY_POLL_MS);
        }
        return Optional.empty();
    }

    public void reply(McpCommand command, String payload) {
        if (command.replyKey() != null) {
            redis.opsForValue().set(command.replyKey(), payload, REPLY_TTL);
        }
    }

    /** Pops up to {@code max} pending commands for {@code replicaId}, oldest first. */
    public List<McpCommand> drain(String replicaId, int max) {
        List<McpCommand> out = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            String json = redis.opsForList().rightPop(QUEUE_PREFIX + replicaId);
            if (json == null) {
                break;
            }
            try {
                out.add(mapper.readValue(json, McpCommand.class));
            } catch (JacksonException e) {
                // malformed entry: drop it rather than wedge the queue
            }
        }
        return out;
    }
}
