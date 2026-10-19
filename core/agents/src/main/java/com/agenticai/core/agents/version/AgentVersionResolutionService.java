package com.agenticai.core.agents.version;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.eventbus.WorkflowEventSignal;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.FeatureFlagService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;

/**
 * Label -> version resolution at run start (C1) - the read side of versioning for runtime traffic.
 *
 * <h2>The two modes</h2>
 *
 * <ul>
 *   <li><b>Auto mode (default)</b> - {@code versioning_explicit_publish} flag absent/CONTROL.
 *       {@link AgentVersionSnapshotService} assigns PRODUCTION to every new snapshot on save, so
 *       the PRODUCTION holder and the live row can never diverge; resolution returns the latest
 *       version and {@link #effectiveAgent} returns the live entity untouched. This is
 *       byte-compatible with pre-C1 behavior.
 *   <li><b>Explicit mode</b> - the flag evaluates TREATMENT for the tenant. Edits mutate only the
 *       draft (the live {@code agent} row); runtime resolves the PRODUCTION-labeled version and
 *       {@link #effectiveAgent} overlays its bundle onto a detached clone, so nothing a tenant has
 *       not promoted is ever executed. Falls back to the live row when no PRODUCTION label exists
 *       yet (a pre-backfill tenant).
 * </ul>
 *
 * <h2>Caching and invalidation</h2>
 *
 * <p>Resolution is Redis-cached under {@code ver:{tenant}:agent:{id}:{mode}} holding the version id
 * (or {@code NONE}). Promote/rollback/demote all call {@link #evict} directly AND emit {@code
 * version.promoted}/{@code version.rolled_back} outbox events; the in-JVM {@link
 * WorkflowEventSignal} listener below evicts on those too, so a label move is visible to the next
 * run on every replica that shares the Redis - the event doubles as the cross-instance invalidation
 * the C1 spec asks for. TTL is a backstop for missed signals, never the primary mechanism.
 */
@Service
public class AgentVersionResolutionService {

    private static final Logger log = LoggerFactory.getLogger(AgentVersionResolutionService.class);

    /**
     * Per-tenant experiment-flag key evaluated via {@link FeatureFlagService#evaluateActiveFlags}.
     */
    public static final String EXPLICIT_PUBLISH_FLAG = "versioning_explicit_publish";

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String NONE = "NONE";

    private final AgentVersionRepository versionRepository;
    private final AgentConfigSnapshotCodec codec;
    private final ObjectProvider<FeatureFlagService> featureFlags;
    private final ObjectProvider<StringRedisTemplate> redis;

    public AgentVersionResolutionService(
            AgentVersionRepository versionRepository,
            AgentConfigSnapshotCodec codec,
            ObjectProvider<FeatureFlagService> featureFlags,
            ObjectProvider<StringRedisTemplate> redis) {
        this.versionRepository = versionRepository;
        this.codec = codec;
        this.featureFlags = featureFlags;
        this.redis = redis;
    }

    /**
     * True when this tenant opted into explicit publish/promote semantics. Reads through the
     * existing experiment-flag machinery (a tenant bucketed TREATMENT on {@value
     * #EXPLICIT_PUBLISH_FLAG} gets draft / published separation); any flag-service failure resolves
     * to the safe default - auto mode, identical to pre-C1 behavior.
     */
    public boolean explicitPublishEnabled() {
        FeatureFlagService flags = featureFlags.getIfAvailable();
        if (flags == null) {
            return false;
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        try {
            return explicitPublish(flags.evaluateActiveFlags(tenantId));
        } catch (RuntimeException e) {
            log.warn(
                    "Could not evaluate {} - assuming auto-publish mode", EXPLICIT_PUBLISH_FLAG, e);
            return false;
        }
    }

    /**
     * The same answer read off a flag map the caller already evaluated for this turn, so a turn
     * evaluates the tenant's flags once instead of once per question asked of them.
     */
    public static boolean explicitPublish(java.util.Map<String, String> activeFlags) {
        return activeFlags != null
                && FeatureFlagService.TREATMENT.equals(activeFlags.get(EXPLICIT_PUBLISH_FLAG));
    }

    /**
     * The version a NEW run of this agent should pin. Auto mode resolves the newest snapshot (which
     * is the live row's own record); explicit mode resolves the PRODUCTION holder and only falls
     * back to newest when no label exists (pre-backfill rows, or a tenant that cleared it).
     */
    @Transactional(readOnly = true)
    public Optional<AgentVersion> resolveForRun(UUID agentId) {
        return resolveForRun(agentId, explicitPublishEnabled());
    }

    /**
     * Pins the version this run resolves and returns the agent to execute (the PRODUCTION bundle
     * under explicit publish, else {@code live}). {@code activeFlags} are the tenant's flags
     * already evaluated for this turn; {@code null} reads them. Either way the flags are evaluated
     * at most once for the pin and the overlay together.
     */
    public Agent pinAndApply(UUID agentId, Agent live, java.util.Map<String, String> activeFlags) {
        boolean explicit =
                activeFlags == null ? explicitPublishEnabled() : explicitPublish(activeFlags);
        resolveForRun(agentId, explicit)
                .ifPresent(v -> com.agenticai.core.security.AgentVersionPin.set(v.getId()));
        return effectiveAgent(live, explicit);
    }

    /**
     * {@link #resolveForRun(UUID)} for a caller that already knows the tenant's explicit-publish
     * answer.
     */
    @Transactional(readOnly = true)
    public Optional<AgentVersion> resolveForRun(UUID agentId, boolean explicitPublish) {
        return explicitPublish
                ? resolve(agentId, AgentVersionLabel.PRODUCTION)
                : resolveLatest(agentId);
    }

    /** Label-targeted resolution - Redis-cached version-id lookup, then a PK fetch. */
    @Transactional(readOnly = true)
    public Optional<AgentVersion> resolve(UUID agentId, AgentVersionLabel label) {
        String key = cacheKey(agentId, label.name());
        String cached = cacheGet(key);
        if (cached != null) {
            if (NONE.equals(cached)) {
                return Optional.empty();
            }
            try {
                return versionRepository.findByIdAndAgentId(UUID.fromString(cached), agentId);
            } catch (IllegalArgumentException e) {
                // Corrupt cache value - fall through to the authoritative read.
            }
        }
        Optional<AgentVersion> holder = versionRepository.findByAgentIdAndLabel(agentId, label);
        if (holder.isEmpty() && label == AgentVersionLabel.PRODUCTION) {
            // No label yet (pre-backfill tenant): the newest snapshot is the
            // live row's record, which is what production was running.
            holder = versionRepository.findFirstByAgentIdOrderByVersionNumberDesc(agentId);
        }
        cachePut(key, holder.map(v -> v.getId().toString()).orElse(NONE));
        return holder;
    }

    private Optional<AgentVersion> resolveLatest(UUID agentId) {
        String key = cacheKey(agentId, "LATEST");
        String cached = cacheGet(key);
        if (cached != null && !NONE.equals(cached)) {
            try {
                Optional<AgentVersion> v =
                        versionRepository.findByIdAndAgentId(UUID.fromString(cached), agentId);
                // A cached id is only valid while it is still the newest row.
                if (v.isPresent()) {
                    Optional<AgentVersion> latest =
                            versionRepository.findFirstByAgentIdOrderByVersionNumberDesc(agentId);
                    if (latest.isPresent() && latest.get().getId().equals(v.get().getId())) {
                        return v;
                    }
                }
            } catch (IllegalArgumentException e) {
                // Corrupt cache value - authoritative read below.
            }
        }
        Optional<AgentVersion> latest =
                versionRepository.findFirstByAgentIdOrderByVersionNumberDesc(agentId);
        cachePut(key, latest.map(v -> v.getId().toString()).orElse(NONE));
        return latest;
    }

    /**
     * The configuration a runtime caller should actually execute. Auto mode returns {@code live}
     * unchanged (PRODUCTION tracks the live row by invariant, so overlaying would be a no-op
     * anyway). Explicit mode applies the PRODUCTION bundle onto a DETACHED clone so the draft row
     * is never mutated - a clone can never be flushed back by Hibernate - while carrying over
     * identity ({@code id}) and the platform-generated schemas, which are derived state rather than
     * bundle content.
     *
     * <p>The overlay is skipped (live row used, logged) when the pinned bundle lacks a
     * provider/model pair - executing a half-decoded config is exactly the failure this feature
     * exists to prevent.
     */
    public Agent effectiveAgent(Agent live) {
        return effectiveAgent(live, explicitPublishEnabled());
    }

    /**
     * {@link #effectiveAgent(Agent)} for a caller that already knows the tenant's explicit-publish
     * answer.
     */
    public Agent effectiveAgent(Agent live, boolean explicitPublish) {
        if (!explicitPublish) {
            return live;
        }
        Optional<AgentVersion> pinned = resolve(live.getId(), AgentVersionLabel.PRODUCTION);
        if (pinned.isEmpty()) {
            return live;
        }
        AgentConfigSnapshot snapshot;
        try {
            snapshot = codec.fromJson(pinned.get().getConfigJson());
        } catch (RuntimeException e) {
            log.warn(
                    "Agent {}: pinned version {} has an undecodable bundle - running the live row",
                    live.getId(),
                    pinned.get().getId(),
                    e);
            return live;
        }
        if (snapshot.providerName() == null || snapshot.model() == null) {
            return live;
        }
        // Agent's no-arg constructor is protected (JPA-only); the detached
        // clone is built reflectively rather than widening that access just
        // for the version-overlay path.
        Agent detached;
        try {
            detached = ReflectionUtils.accessibleConstructor(Agent.class).newInstance();
        } catch (ReflectiveOperationException e) {
            log.warn(
                    "Agent {}: cannot build the detached overlay clone - running the live row",
                    live.getId(),
                    e);
            return live;
        }
        copyField(live, detached, "id");
        codec.apply(snapshot, detached);
        detached.setGeneratedSchema(live.getInputSchemaJson(), live.getOutputSchemaJson());
        return detached;
    }

    /**
     * Drop every cached resolution for this agent - called on every label move and by the event
     * listener below.
     */
    public void evict(UUID agentId) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            for (String suffix : new String[] {"PRODUCTION", "STAGING", "LATEST"}) {
                template.delete(cacheKey(agentId, suffix));
            }
        } catch (RuntimeException e) {
            // Cache eviction is best-effort; the TTL bounds staleness.
            log.warn("Could not evict version-resolution cache for agent {}", agentId, e);
        }
    }

    /**
     * Cross-instance invalidation: {@code version.promoted}/{@code version.rolled_back} are
     * published on every label move; the in-JVM signal reaches this listener on the same node, and
     * the Redis delete the publisher performs covers the rest - see class javadoc.
     */
    @EventListener
    public void onVersionEvent(WorkflowEventSignal signal) {
        if (signal == null || signal.event() == null) {
            return;
        }
        String type = signal.event().eventType();
        if (!"version.promoted".equals(type) && !"version.rolled_back".equals(type)) {
            return;
        }
        Object entityType = signal.event().payload().get("entityType");
        Object entityId = signal.event().payload().get("entityId");
        if ("AGENT".equals(entityType) && entityId != null) {
            try {
                evict(UUID.fromString(entityId.toString()));
            } catch (IllegalArgumentException ignored) {
                // Malformed payload - nothing to evict.
            }
        }
    }

    private String cacheKey(UUID agentId, String mode) {
        String tenant =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElse(TenantSchemaResolver.DEFAULT_SCHEMA);
        return "ver:" + tenant + ":agent:" + agentId + ":" + mode;
    }

    private String cacheGet(String key) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return null;
        }
        try {
            return template.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.debug("Version-resolution cache read failed for {}", key, e);
            return null;
        }
    }

    private void cachePut(String key, String value) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.opsForValue().set(key, value, CACHE_TTL);
        } catch (RuntimeException e) {
            log.debug("Version-resolution cache write failed for {}", key, e);
        }
    }

    private static void copyField(Agent from, Agent to, String fieldName) {
        Field field = ReflectionUtils.findField(Agent.class, fieldName);
        if (field == null) {
            return;
        }
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, to, ReflectionUtils.getField(field, from));
    }
}
