package com.agenticai.core.agents.tools;

import com.agenticai.core.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Bounded effect journal for write/send (DURABLE) tools: remembers the result of a successful call
 * under a key derived from run + step + tool + canonical args, so a retry or replay of the same
 * call within the TTL returns the recorded result instead of reaching the provider a second time (a
 * duplicate email, payment link...).
 *
 * <p>Always keeps a bounded in-memory LRU (per node). When {@code
 * app.tools.effect-journal.store=redis} and a {@link StringRedisTemplate} exists, the record is
 * also written to Redis with the TTL so it survives a restart and is shared across replicas; Redis
 * failures degrade to memory-only, never break the tool call. Enabled by default since 2026-10-02
 * ({@code app.tools.effect-journal.enabled=true}; memory store unless redis is chosen).
 *
 * <p><b>What it covers.</b> Only tools the tool classification marks {@code DURABLE} (see {@code
 * AgentToolGovernance#isDurableTool}); {@code LoggingToolCallback} is the only caller, so only calls that
 * pass through the platform's tool wrapper are protected. The caller supplies the "run" and "step" parts
 * of the key: the session id plus a per-chat-turn id (or, in an agent-job session with the durable step
 * journal on, a fixed step so a resumed attempt recognises the call). So an IDENTICAL call (same tool,
 * same arguments) repeated within one turn - a model retry or loop, or the replay of the turn on another
 * provider - is de-duplicated within {@code ttl-minutes}.
 *
 * <p><b>What it does not cover.</b> A call that threw (a failure after the provider acted cannot be told
 * from one before it, so nothing is recorded); the same tool with different arguments; tools classified
 * {@code SIDE_EFFECT} or not classified; a repeat in a LATER turn of an ordinary chat session (a model that
 * legitimately repeats an identical write inside one turn gets the recorded result without the effect
 * running again, and one that repeats it next turn runs it again); and a restart or another replica unless
 * {@code store=redis} (or the durable job store). The turn's failover gate ({@code FailoverReplayGate})
 * relies on exactly this: a turn that ran write/send tools is replayed on another provider only when the
 * journal recorded each of them. Enabled by default; see docs/configuration.md.
 */
@Component
public class EffectJournal {

    private static final Logger log = LoggerFactory.getLogger(EffectJournal.class);
    private static final String REDIS_PREFIX = "tool:effect:";

    private record JournalEntry(String result, long expiresAtMillis) {}

    private final boolean enabled;
    private final Duration ttl;
    private final int maxEntries;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Map<String, JournalEntry> memory;

    /**
     * Optional Postgres backing (the step journal); null = memory/redis only, i.e. today's
     * behaviour.
     */
    private volatile DurableStore durableStore;

    /**
     * Durable (survives restart, per tenant schema) backing for effects. Implemented by {@link
     * StepJournal}; inert unless {@code step-journal-enabled} AND {@code resume-from-journal} are
     * both true.
     */
    public interface DurableStore {
        boolean isActive();

        Optional<String> findEffect(String key);

        void saveEffect(String key, String result);

        /**
         * Marks a DURABLE call of a job session as in flight (before the provider is reached).
         * Default no-op.
         */
        default void beginDurableCall(String sessionId, String effectKey) {}

        /** Marks that in-flight call as no longer in flight (the tool returned). Default no-op. */
        default void completeDurableCall(String sessionId, String effectKey) {}
    }

    /**
     * Records that a DURABLE call of a job is about to reach its provider. Only in durable job
     * scope. A worker that dies between this and {@link #markDurableCallDone} leaves a STARTED row
     * that the recovery policy reads as "an effect may have happened: never rerun this turn".
     * Best-effort; never throws.
     */
    public void markDurableCallStarted(String sessionId, String key) {
        DurableStore d = durableStore;
        if (d != null && durableJobScope(sessionId)) {
            try {
                d.beginDurableCall(sessionId, key);
            } catch (RuntimeException ex) {
                log.debug("Effect journal in-flight begin failed", ex);
            }
        }
    }

    /**
     * Clears the in-flight marker once the tool returned (executed or not-executed). Best-effort.
     */
    public void markDurableCallDone(String sessionId, String key) {
        DurableStore d = durableStore;
        if (d != null && durableJobScope(sessionId)) {
            try {
                d.completeDurableCall(sessionId, key);
            } catch (RuntimeException ex) {
                log.debug("Effect journal in-flight complete failed", ex);
            }
        }
    }

    @Autowired(required = false)
    public void setDurableStore(DurableStore durableStore) {
        this.durableStore = durableStore;
    }

    /**
     * True when durable-store backing is live AND the session belongs to an agent job ({@code
     * job:<uuid>}). For such sessions the effect key ignores the per-turn id (random per process),
     * so an identical DURABLE call replayed by a resumed attempt maps to the same key. Scope limit:
     * within one job an identical write/send (same tool + args) is performed once, even across
     * turns.
     */
    public boolean durableJobScope(String sessionId) {
        DurableStore d = durableStore;
        return enabled
                && d != null
                && d.isActive()
                && sessionId != null
                && sessionId.startsWith("job:");
    }

    @Autowired
    public EffectJournal(
            @Value("${app.tools.effect-journal.enabled:true}") boolean enabled,
            @Value("${app.tools.effect-journal.ttl-minutes:30}") long ttlMinutes,
            @Value("${app.tools.effect-journal.max-entries:5000}") int maxEntries,
            @Value("${app.tools.effect-journal.store:memory}") String store,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this(
                enabled,
                Duration.ofMinutes(Math.max(1, ttlMinutes)),
                maxEntries,
                "redis".equalsIgnoreCase(store) ? redisProvider.getIfAvailable() : null,
                Clock.systemUTC());
    }

    /** Test seam. */
    EffectJournal(
            boolean enabled, Duration ttl, int maxEntries, StringRedisTemplate redis, Clock clock) {
        this.enabled = enabled;
        this.ttl = ttl;
        this.maxEntries = Math.max(1, maxEntries);
        this.redis = redis;
        this.clock = clock;
        final int cap = this.maxEntries;
        this.memory =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, JournalEntry> eldest) {
                        return size() > cap;
                    }
                };
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Key = sha256(tenant | runId | stepId | tool | sha256(args)). Tenant is included so two
     * tenants sharing a session id string can never see each other's results.
     */
    public static String keyFor(String runId, String stepId, String toolName, String args) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        String argsHash = sha256(args == null ? "" : args);
        return sha256(tenant + "|" + runId + "|" + stepId + "|" + toolName + "|" + argsHash);
    }

    /** The recorded result for this key if one was recorded within the TTL. */
    public Optional<String> lookup(String key) {
        if (!enabled || key == null) {
            return Optional.empty();
        }
        long now = clock.millis();
        synchronized (memory) {
            JournalEntry e = memory.get(key);
            if (e != null) {
                if (e.expiresAtMillis() > now) {
                    return Optional.of(e.result());
                }
                memory.remove(key);
            }
        }
        if (redis != null) {
            try {
                String v = redis.opsForValue().get(REDIS_PREFIX + key);
                if (v != null) {
                    return Optional.of(v);
                }
            } catch (RuntimeException ex) {
                log.debug("Effect journal redis read failed; treating as miss", ex);
            }
        }
        DurableStore d = durableStore;
        if (d != null && d.isActive()) {
            try {
                return d.findEffect(key);
            } catch (RuntimeException ex) {
                log.debug("Effect journal durable read failed; treating as miss", ex);
            }
        }
        return Optional.empty();
    }

    /** Records a successful result. Best-effort; never throws. */
    public void record(String key, String result) {
        if (!enabled || key == null || result == null) {
            return;
        }
        synchronized (memory) {
            memory.put(key, new JournalEntry(result, clock.millis() + ttl.toMillis()));
        }
        if (redis != null) {
            try {
                redis.opsForValue().set(REDIS_PREFIX + key, result, ttl);
            } catch (RuntimeException ex) {
                log.debug("Effect journal redis write failed; memory-only", ex);
            }
        }
        DurableStore d = durableStore;
        if (d != null && d.isActive()) {
            try {
                d.saveEffect(key, result);
            } catch (RuntimeException ex) {
                log.debug("Effect journal durable write failed; memory/redis only", ex);
            }
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
