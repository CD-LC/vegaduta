package com.agenticai.core.omnichannel.relay;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Edge relay (2026-10-14): a signed-in browser with a warm on-device model registers as the
 * <i>model</i> behind one or more of its tenant's channels. While such a relay is online, an
 * inbound SMS or WhatsApp message for those channels is queued here instead of being sent to the
 * hosted pool; the browser claims it, answers with the local model, and hands the text back. One
 * laptop then serves a whole WhatsApp group or SMS number with no API key, no GPU server and no
 * per-token cost. Nobody else ships this.
 *
 * <p><b>Who sends.</b> Never the browser. The server thread that owns the inbound message (the
 * inbound executor task of the webhook controller) waits for the relay's reply and sends it through
 * the same approval gate it would use for a hosted reply. So the relay needs no channel knowledge,
 * the approval policy still applies, and a relay that vanishes mid-answer costs one timeout, after
 * which the same thread answers on the hosted path. There is no scheduler and no new job pool.
 *
 * <p><b>Storage.</b> Valkey, keyed per tenant: a hash of registered relays, a per-relay liveness
 * key refreshed by heartbeats, one queue list per channel (so a relay serving only SMS never claims
 * a WhatsApp message), a JSON job key with a lease, and a per-job reply list the waiting thread
 * blocks on. Every Valkey failure degrades to "no relay online", which is the hosted path, so a
 * Valkey outage can never lose a message.
 *
 * <p><b>Trust.</b> The relay is a tenant user holding {@code PERM_MANAGE_CHANNELS} (the same
 * permission that sets the channel's default agent); it sees the inbound text of its own tenant's
 * end users, which that permission already grants through the conversation inbox. The sender's
 * number is masked in the job. A job can only be completed by the relay that claimed it.
 */
@Service
public class EdgeRelayService {

    private static final Logger log = LoggerFactory.getLogger(EdgeRelayService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    static final String PREFIX = "agentic:edge-relay:";

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration heartbeatTtl;
    private final int claimWaitSecondsMax;
    private final Duration replyWait;
    private final Duration lease;
    private final int queueMax;

    public EdgeRelayService(
            StringRedisTemplate redis,
            @Value("${app.omnichannel.edge-relay.enabled:true}") boolean enabled,
            @Value("${app.omnichannel.edge-relay.heartbeat-ttl-seconds:45}")
                    long heartbeatTtlSeconds,
            @Value("${app.omnichannel.edge-relay.claim-wait-seconds-max:25}")
                    int claimWaitSecondsMax,
            @Value("${app.omnichannel.edge-relay.reply-wait-seconds:60}") long replyWaitSeconds,
            @Value("${app.omnichannel.edge-relay.lease-seconds:90}") long leaseSeconds,
            @Value("${app.omnichannel.edge-relay.queue-max:100}") int queueMax) {
        this.redis = redis;
        this.enabled = enabled;
        this.heartbeatTtl = Duration.ofSeconds(Math.max(10, heartbeatTtlSeconds));
        this.claimWaitSecondsMax = Math.max(1, Math.min(claimWaitSecondsMax, 60));
        this.replyWait = Duration.ofSeconds(Math.max(5, replyWaitSeconds));
        this.lease = Duration.ofSeconds(Math.max(10, leaseSeconds));
        this.queueMax = Math.max(1, queueMax);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Duration heartbeatTtl() {
        return heartbeatTtl;
    }

    public int claimWaitSecondsMax() {
        return claimWaitSecondsMax;
    }

    public Duration replyWait() {
        return replyWait;
    }

    // --- registry ------------------------------------------------------------------------------

    /** A relay as registered by a browser: which channels it serves and how it describes itself. */
    public record RelayInfo(
            String relayId,
            String subject,
            Set<ChannelType> channels,
            String modelLabel,
            String deviceLabel,
            long registeredAtEpochMs) {}

    public RelayInfo register(
            String tenantId,
            String subject,
            Set<ChannelType> channels,
            String modelLabel,
            String deviceLabel) {
        if (!enabled) {
            throw new IllegalStateException(
                    "edge relay is disabled (app.omnichannel.edge-relay.enabled)");
        }
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("a relay must serve at least one channel");
        }
        RelayInfo info =
                new RelayInfo(
                        UUID.randomUUID().toString(),
                        subject,
                        Set.copyOf(channels),
                        trimToNull(modelLabel),
                        trimToNull(deviceLabel),
                        Instant.now().toEpochMilli());
        redis.opsForHash().put(relaysKey(tenantId), info.relayId(), write(info));
        redis.opsForValue().set(aliveKey(tenantId, info.relayId()), "1", heartbeatTtl);
        log.info(
                "Edge relay {} registered for tenant {} ({}, {})",
                info.relayId(),
                tenantId,
                info.channels(),
                info.modelLabel());
        return info;
    }

    /**
     * @return false when the relay is unknown (expired or never registered), so the browser
     *     re-registers.
     */
    public boolean heartbeat(String tenantId, String relayId) {
        if (!Boolean.TRUE.equals(redis.opsForHash().hasKey(relaysKey(tenantId), relayId))) {
            return false;
        }
        redis.opsForValue().set(aliveKey(tenantId, relayId), "1", heartbeatTtl);
        return true;
    }

    public void unregister(String tenantId, String relayId) {
        redis.opsForHash().delete(relaysKey(tenantId), relayId);
        redis.delete(aliveKey(tenantId, relayId));
        log.info("Edge relay {} unregistered for tenant {}", relayId, tenantId);
    }

    /**
     * Registered relays whose heartbeat is still alive; stale registrations are pruned on the way.
     */
    public List<RelayInfo> onlineRelays(String tenantId) {
        if (!enabled) {
            return List.of();
        }
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(relaysKey(tenantId));
            List<RelayInfo> online = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                String relayId = String.valueOf(entry.getKey());
                if (Boolean.TRUE.equals(redis.hasKey(aliveKey(tenantId, relayId)))) {
                    RelayInfo info = read(String.valueOf(entry.getValue()), RelayInfo.class);
                    if (info != null) {
                        online.add(info);
                    }
                } else {
                    redis.opsForHash().delete(relaysKey(tenantId), relayId);
                }
            }
            return online;
        } catch (RuntimeException e) {
            log.warn(
                    "Edge relay registry unavailable for tenant {} - treating as offline: {}",
                    tenantId,
                    e.getMessage());
            return List.of();
        }
    }

    public Optional<RelayInfo> relay(String tenantId, String relayId) {
        return onlineRelays(tenantId).stream().filter(r -> r.relayId().equals(relayId)).findFirst();
    }

    public boolean isOnline(String tenantId, ChannelType channel) {
        return onlineRelays(tenantId).stream().anyMatch(r -> r.channels().contains(channel));
    }

    /** Jobs answered per relay, for the console. */
    public long answered(String tenantId, String relayId) {
        try {
            Object value = redis.opsForHash().get(statsKey(tenantId), relayId);
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    // --- server side: hand a message off and wait -----------------------------------------------

    /**
     * Queues a message for the relays serving {@code channel}. Empty when the relay is disabled, no
     * relay is online for the channel, the queue is full or Valkey is unavailable: in every case
     * the caller answers on the hosted path.
     */
    public Optional<RelayJob> enqueue(
            String tenantId,
            ChannelType channel,
            UUID sessionId,
            UUID agentId,
            String externalIdMasked,
            String message,
            List<RelayJob.RelayTurn> history) {
        if (!isOnline(tenantId, channel)) {
            return Optional.empty();
        }
        try {
            String queueKey = queueKey(tenantId, channel);
            Long size = redis.opsForList().size(queueKey);
            if (size != null && size >= queueMax) {
                log.warn(
                        "Edge relay queue for tenant {} channel {} is full ({}); answering hosted",
                        tenantId,
                        channel,
                        size);
                return Optional.empty();
            }
            RelayJob job =
                    new RelayJob(
                            UUID.randomUUID().toString(),
                            tenantId,
                            channel,
                            sessionId,
                            agentId,
                            externalIdMasked,
                            message,
                            history == null ? List.of() : List.copyOf(history),
                            Instant.now().toEpochMilli(),
                            RelayJob.QUEUED,
                            null);
            redis.opsForValue().set(jobKey(tenantId, job.jobId()), write(job), jobTtl());
            redis.opsForList().rightPush(queueKey, job.jobId());
            redis.expire(queueKey, Duration.ofHours(1));
            return Optional.of(job);
        } catch (RuntimeException e) {
            log.warn(
                    "Edge relay enqueue failed for tenant {} - answering hosted: {}",
                    tenantId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Blocks up to the reply window for the relay's answer. Empty on timeout or failure, after
     * which the job is cancelled so a late reply is dropped rather than sent twice.
     */
    public Optional<String> awaitReply(String tenantId, String jobId) {
        try {
            String raw = redis.opsForList().leftPop(replyKey(tenantId, jobId), replyWait);
            if (raw != null) {
                Reply reply = read(raw, Reply.class);
                cleanup(tenantId, jobId);
                if (reply != null
                        && reply.ok()
                        && reply.text() != null
                        && !reply.text().isBlank()) {
                    return Optional.of(reply.text());
                }
                log.info(
                        "Edge relay declined job {} ({}); answering hosted",
                        jobId,
                        reply == null ? "unreadable" : reply.reason());
                return Optional.empty();
            }
            cancel(tenantId, jobId);
            log.info(
                    "Edge relay did not answer job {} within {}s; answering hosted",
                    jobId,
                    replyWait.toSeconds());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn(
                    "Edge relay wait failed for job {} - answering hosted: {}",
                    jobId,
                    e.getMessage());
            cancel(tenantId, jobId);
            return Optional.empty();
        }
    }

    private void cancel(String tenantId, String jobId) {
        try {
            RelayJob job = loadJob(tenantId, jobId);
            if (job != null && !RelayJob.DONE.equals(job.status())) {
                redis.opsForValue()
                        .set(
                                jobKey(tenantId, jobId),
                                write(job.withStatus(RelayJob.CANCELLED, job.claimedBy())),
                                lease);
            }
            redis.delete(replyKey(tenantId, jobId));
        } catch (RuntimeException e) {
            log.debug("Edge relay cancel of job {} failed: {}", jobId, e.getMessage());
        }
    }

    private void cleanup(String tenantId, String jobId) {
        try {
            redis.delete(List.of(jobKey(tenantId, jobId), replyKey(tenantId, jobId)));
        } catch (RuntimeException e) {
            log.debug("Edge relay cleanup of job {} failed: {}", jobId, e.getMessage());
        }
    }

    // --- relay side: claim and answer -----------------------------------------------------------

    /**
     * Long-polls the queues of the channels this relay serves for up to {@code waitSeconds}
     * (clamped to the configured maximum). Empty when nothing arrived or the relay is unknown.
     */
    public Optional<RelayJob> claim(String tenantId, String relayId, int waitSeconds) {
        Optional<RelayInfo> info = relay(tenantId, relayId);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        int wait = Math.max(0, Math.min(waitSeconds, claimWaitSecondsMax));
        long deadline = System.nanoTime() + Duration.ofSeconds(wait).toNanos();
        List<ChannelType> channels = info.get().channels().stream().sorted().toList();
        byte[][] keys =
                channels.stream()
                        .map(c -> queueKey(tenantId, c).getBytes(StandardCharsets.UTF_8))
                        .toArray(byte[][]::new);
        try {
            while (true) {
                long remaining = Math.max(0, (deadline - System.nanoTime()) / 1_000_000_000L);
                List<byte[]> popped =
                        redis.execute(
                                (RedisCallback<List<byte[]>>)
                                        connection ->
                                                connection
                                                        .listCommands()
                                                        .bLPop((int) remaining, keys));
                if (popped == null || popped.size() < 2) {
                    return Optional.empty();
                }
                String jobId = new String(popped.get(1), StandardCharsets.UTF_8);
                RelayJob job = loadJob(tenantId, jobId);
                if (job == null || !RelayJob.QUEUED.equals(job.status())) {
                    // Cancelled while queued, or expired: skip it and keep waiting for a live one.
                    if (System.nanoTime() >= deadline) {
                        return Optional.empty();
                    }
                    continue;
                }
                RelayJob claimed = job.withStatus(RelayJob.CLAIMED, relayId);
                redis.opsForValue().set(jobKey(tenantId, jobId), write(claimed), lease);
                return Optional.of(claimed);
            }
        } catch (RuntimeException e) {
            log.warn("Edge relay claim failed for relay {}: {}", relayId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * @return false when the job is unknown, cancelled, or claimed by another relay (the reply is
     *     dropped).
     */
    public boolean complete(String tenantId, String relayId, String jobId, String text) {
        return finish(tenantId, relayId, jobId, new Reply(true, text, null));
    }

    /**
     * The relay could not answer (no local tier, model error): the server answers hosted at once.
     */
    public boolean fail(String tenantId, String relayId, String jobId, String reason) {
        return finish(tenantId, relayId, jobId, new Reply(false, null, trimToNull(reason)));
    }

    private boolean finish(String tenantId, String relayId, String jobId, Reply reply) {
        try {
            RelayJob job = loadJob(tenantId, jobId);
            if (job == null
                    || !RelayJob.CLAIMED.equals(job.status())
                    || !relayId.equals(job.claimedBy())) {
                return false;
            }
            redis.opsForValue()
                    .set(
                            jobKey(tenantId, jobId),
                            write(job.withStatus(RelayJob.DONE, relayId)),
                            lease);
            String replyKey = replyKey(tenantId, jobId);
            redis.opsForList().rightPush(replyKey, write(reply));
            redis.expire(replyKey, lease);
            if (reply.ok()) {
                redis.opsForHash().increment(statsKey(tenantId), relayId, 1L);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Edge relay finish failed for job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    record Reply(boolean ok, String text, String reason) {}

    private RelayJob loadJob(String tenantId, String jobId) {
        String raw = redis.opsForValue().get(jobKey(tenantId, jobId));
        return raw == null ? null : read(raw, RelayJob.class);
    }

    private Duration jobTtl() {
        return lease.plus(replyWait).plusSeconds(30);
    }

    static String relaysKey(String tenantId) {
        return PREFIX + tenantId + ":relays";
    }

    static String aliveKey(String tenantId, String relayId) {
        return PREFIX + tenantId + ":relay:" + relayId;
    }

    static String queueKey(String tenantId, ChannelType channel) {
        return PREFIX + tenantId + ":queue:" + channel.name();
    }

    static String jobKey(String tenantId, String jobId) {
        return PREFIX + tenantId + ":job:" + jobId;
    }

    static String replyKey(String tenantId, String jobId) {
        return PREFIX + tenantId + ":reply:" + jobId;
    }

    static String statsKey(String tenantId) {
        return PREFIX + tenantId + ":stats";
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("edge relay JSON write failed", e);
        }
    }

    private static <T> T read(String raw, Class<T> type) {
        try {
            return MAPPER.readValue(raw, type);
        } catch (Exception e) {
            log.warn(
                    "Edge relay could not read a {} record: {}",
                    type.getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty()
                ? null
                : trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }
}
