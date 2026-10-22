package com.agenticai.core.workflow.version;

import com.agenticai.core.agents.version.LabelEventAction;
import com.agenticai.core.agents.version.VersionEntityType;
import com.agenticai.core.agents.version.VersionLabel;
import com.agenticai.core.agents.version.VersionLabelEvent;
import com.agenticai.core.agents.version.VersionLabelEventRepository;
import com.agenticai.core.agents.version.VersionRollbackException;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventSignal;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.FeatureFlagService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowRun;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Workflow version history + diffs (Tier-3, V273) - the workflow sibling of {@code
 * AgentVersionService}, deliberately smaller: no labels (a workflow's own DRAFT/ACTIVE status is
 * already the live pointer), no promotion gates (a workflow version is history and a restore
 * target, not a deployment artifact with its own lifecycle).
 *
 * <p>Two read shapes live here:
 *
 * <ul>
 *   <li><b>Version diff</b> ({@link #diffVersions}) - structural graph diff between two snapshots:
 *       nodes added/removed/config-changed, edges added/removed, name/description/status changes.
 *       Computed in Java over the two graph_json documents - no JSONB queries, same "TEXT blob read
 *       whole" convention as {@code WorkflowRun#traceJson}.
 *   <li><b>Run diff</b> ({@link #diffRuns}) - the audit's "run-to-run diff": compares two {@code
 *       workflow_run} rows - status, which version each executed ({@code
 *       workflow_run.workflow_version}, stamped at run start), and a per-node execution diff
 *       aligned off each run's trace (nodeId -> last status, execution count, total wall time) so
 *       "run A retried node X 3 times, run B once" and "node Y failed in A, succeeded in B" are
 *       both first-class answers.
 * </ul>
 */
@Service
public class WorkflowVersionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowVersionService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String NONE = "NONE";

    private final WorkflowVersionRepository repository;
    private final ObjectMapper objectMapper;
    private final WorkflowVersionDiffer differ;

    /**
     * C1 collaborators - all optional so the 2-arg constructor (hand-built tests) keeps working.
     */
    private final ObjectProvider<VersionLabelEventRepository> labelEvents;

    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectProvider<WorkflowEventPublisher> eventPublisher;
    private final ObjectProvider<FeatureFlagService> featureFlags;

    public WorkflowVersionService(WorkflowVersionRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, null, null, null, null);
    }

    @Autowired // two public ctors: without this Spring can't pick one and dies with "no default
    // constructor"
    public WorkflowVersionService(
            WorkflowVersionRepository repository,
            ObjectMapper objectMapper,
            ObjectProvider<VersionLabelEventRepository> labelEvents,
            ObjectProvider<StringRedisTemplate> redis,
            ObjectProvider<WorkflowEventPublisher> eventPublisher,
            ObjectProvider<FeatureFlagService> featureFlags) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.differ = new WorkflowVersionDiffer(objectMapper);
        this.labelEvents = labelEvents;
        this.redis = redis;
        this.eventPublisher = eventPublisher;
        this.featureFlags = featureFlags;
    }

    /**
     * Appends the "this is the state now" row after a {@code WorkflowService} mutation - called
     * AFTER the definition row is updated, never inside its own transaction boundary: a version row
     * is part of the same atomic change it records.
     *
     * <p>C1 dedupe: a save that changed neither graph, name, description nor status appends nothing
     * and returns the existing newest row - the same "no-op save must not pad history" rule {@code
     * AgentVersionSnapshotService} documents.
     *
     * <p>C1 auto-promote (compatibility mode): while the {@code versioning_explicit_publish} tenant
     * flag is off, the new snapshot also takes the label matching the definition's status (ACTIVE
     * -> PRODUCTION, DRAFT -> STAGING, ARCHIVED -> none), keeping "the labeled version is what
     * runs" true by construction. With the flag on, labels are never touched by a save - a draft
     * edit waits for an explicit promote.
     */
    @Transactional
    public WorkflowVersion snapshot(WorkflowDefinition definition, String changeSummary) {
        String detailsJson = detailsJsonOf(definition);
        WorkflowVersion latest =
                repository
                        .findFirstByWorkflowIdOrderByVersionNumberDesc(definition.getId())
                        .orElse(null);
        if (latest != null
                && safe(latest.getGraphJson()).equals(safe(definition.getGraphJson()))
                && safe(latest.getDetailsJson()).equals(safe(detailsJson))
                && safe(latest.getWorkflowName()).equals(safe(definition.getName()))
                && safe(latest.getDescription()).equals(safe(definition.getDescription()))
                && latest.getStatus() == definition.getStatus()) {
            log.debug(
                    "Workflow {} saved with no change ({}) - no new version row",
                    definition.getId(),
                    changeSummary);
            return latest;
        }
        int next =
                latestVersionNumber(definition.getId()) == null
                        ? 1
                        : latestVersionNumber(definition.getId()) + 1;
        WorkflowVersion saved =
                repository.save(
                        WorkflowVersion.snapshot(
                                definition.getId(),
                                next,
                                definition.getGraphJson(),
                                detailsJson,
                                definition.getName(),
                                definition.getDescription(),
                                definition.getStatus(),
                                changeSummary,
                                TenantContext.current()
                                        .map(TenantContext::getSubject)
                                        .orElse(null)));
        autoAssignLabel(definition.getId(), saved);
        return saved;
    }

    /**
     * The {@code details_json} document - the definition state that is NOT inside graph_json
     * (schedule, execution limits, economy/tuning knobs). Key names match V342's SQL {@code
     * jsonb_build_object} exactly so a backfilled row and a Java-written row diff cleanly against
     * each other. Never throws - a serialization failure stores NULL (the column tolerates it, the
     * same way V273-era rows have none) rather than breaking a workflow save.
     */
    private String detailsJsonOf(WorkflowDefinition definition) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("scheduleCron", definition.getScheduleCron());
            details.put("scheduleEnabled", definition.isScheduleEnabled());
            details.put(
                    "reasoningDepth",
                    definition.getReasoningDepth() == null
                            ? null
                            : definition.getReasoningDepth().name());
            details.put(
                    "tuningMode",
                    definition.getTuningMode() == null ? null : definition.getTuningMode().name());
            details.put("stepCachingEnabled", definition.isStepCachingEnabled());
            details.put("costRoutingEnabled", definition.isCostRoutingEnabled());
            details.put(
                    "economyProviderName",
                    definition.getEconomyProviderName() == null
                            ? null
                            : definition.getEconomyProviderName().name());
            details.put("economyModel", definition.getEconomyModel());
            details.put("maxStepsPerRun", definition.getMaxStepsPerRun());
            details.put("stepTimeoutSeconds", definition.getStepTimeoutSeconds());
            details.put("maxRetryAttempts", definition.getMaxRetryAttempts());
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.warn(
                    "Could not serialize details_json for workflow {} - snapshot stores NULL details",
                    definition.getId(),
                    e);
            return null;
        }
    }

    /**
     * C1 {@code publish(entityId, changeNote)} - snapshot the current draft explicitly. Returns the
     * appended row, or the existing newest row when the bundle was byte-identical (the dedupe
     * {@link #snapshot} already performs).
     */
    @Transactional
    public WorkflowVersion publish(WorkflowDefinition definition, String changeNote) {
        return snapshot(
                definition,
                changeNote == null || changeNote.isBlank() ? "Published" : changeNote.trim());
    }

    /**
     * C1 label move for workflows - the {@code AgentVersionService#setLabel} sibling. The previous
     * holder is cleared and flushed first so V342's partial unique index never sees two holders.
     */
    @Transactional
    public WorkflowVersion promote(UUID workflowId, int versionNumber, VersionLabel label) {
        WorkflowVersion target = get(workflowId, versionNumber);
        Optional<WorkflowVersion> holder = repository.findByWorkflowIdAndLabel(workflowId, label);
        if (holder.isPresent() && holder.get().getId().equals(target.getId())) {
            return target;
        }
        holder.ifPresent(
                previous -> {
                    previous.clearLabel();
                    repository.saveAndFlush(previous);
                });
        target.assignLabel(label);
        WorkflowVersion saved = repository.save(target);
        afterLabelMove(workflowId, saved, label, LabelEventAction.PROMOTE, null);
        log.info("Workflow {} label {} now points at v{}", workflowId, label, versionNumber);
        return saved;
    }

    /**
     * C1 {@code rollback(entityId)} - moves PRODUCTION back to the previous version. A label move,
     * never a new snapshot, never a write to the draft definition. 409 ({@link
     * VersionRollbackException}) when there is no earlier version to point at.
     */
    @Transactional
    public WorkflowVersion rollback(UUID workflowId) {
        WorkflowVersion current =
                repository
                        .findByWorkflowIdAndLabel(workflowId, VersionLabel.PRODUCTION)
                        .orElseThrow(() -> new VersionRollbackException("workflow", workflowId));
        WorkflowVersion previous = null;
        for (WorkflowVersion candidate :
                repository.findByWorkflowIdOrderByVersionNumberDesc(workflowId)) {
            if (candidate.getVersionNumber() < current.getVersionNumber()) {
                previous = candidate;
                break;
            }
        }
        if (previous == null) {
            throw new VersionRollbackException("workflow", workflowId);
        }
        current.clearLabel();
        repository.saveAndFlush(current);
        previous.assignLabel(VersionLabel.PRODUCTION);
        WorkflowVersion saved = repository.save(previous);
        afterLabelMove(
                workflowId,
                saved,
                VersionLabel.PRODUCTION,
                LabelEventAction.ROLLBACK,
                "rolled back from v" + current.getVersionNumber());
        log.info(
                "Workflow {} rolled back: PRODUCTION v{} -> v{}",
                workflowId,
                current.getVersionNumber(),
                saved.getVersionNumber());
        return saved;
    }

    /**
     * The version a NEW run executes: the PRODUCTION holder when one exists, else the newest
     * snapshot (pre-backfill rows / a tenant that never promoted - the resolution stays identical
     * to "run the latest" until a label exists). Redis-cached; promote/rollback evict.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowVersion> resolveRunVersion(UUID workflowId) {
        String key = cacheKey(workflowId);
        String cached = cacheGet(key);
        if (cached != null && !NONE.equals(cached)) {
            try {
                Optional<WorkflowVersion> v =
                        repository.findByWorkflowIdAndVersionNumber(
                                workflowId, Integer.parseInt(cached));
                if (v.isPresent()) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // Corrupt cache value - authoritative read below.
            }
        }
        Optional<WorkflowVersion> resolved =
                repository
                        .findByWorkflowIdAndLabel(workflowId, VersionLabel.PRODUCTION)
                        .or(
                                () ->
                                        repository.findFirstByWorkflowIdOrderByVersionNumberDesc(
                                                workflowId));
        cachePut(key, resolved.map(v -> Integer.toString(v.getVersionNumber())).orElse(NONE));
        return resolved;
    }

    /**
     * The graph a run should execute - the PRODUCTION snapshot's document when one exists, else the
     * live definition's. This is "runtime reads only labeled versions" for workflows, with the
     * compat fallback the spec's auto mode requires (no label = latest = live row).
     */
    @Transactional(readOnly = true)
    public String resolveRunGraphJson(WorkflowDefinition definition) {
        return resolveRunVersion(definition.getId())
                .map(WorkflowVersion::getGraphJson)
                .orElse(definition.getGraphJson());
    }

    /**
     * The version ordinal a run about to start is executing - stamped onto {@code
     * workflow_run.workflow_version} at run start so {@link #diffRuns} can say "these two runs
     * executed different graphs" with data, not inference. Null when the workflow predates
     * versioning (no snapshot has ever been taken) - an honest "unknown", never a fabricated 1.
     *
     * <p>C1: label-aware - resolves the PRODUCTION holder first, so the pin names the version
     * actually executed, not merely the newest row.
     */
    @Transactional(readOnly = true)
    public Integer currentVersionNumber(UUID workflowId) {
        return resolveRunVersion(workflowId).map(WorkflowVersion::getVersionNumber).orElse(null);
    }

    /**
     * Drop the cached run-resolution for this workflow - called on every label move and by the
     * event listener.
     */
    public void evict(UUID workflowId) {
        StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.delete(cacheKey(workflowId));
        } catch (RuntimeException e) {
            log.warn("Could not evict run-resolution cache for workflow {}", workflowId, e);
        }
    }

    /**
     * Cross-instance invalidation via the in-JVM fan-out of the outbox events this service
     * publishes.
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
        if ("WORKFLOW".equals(entityType) && entityId != null) {
            try {
                evict(UUID.fromString(entityId.toString()));
            } catch (IllegalArgumentException ignored) {
                // Malformed payload - nothing to evict.
            }
        }
    }

    private void autoAssignLabel(UUID workflowId, WorkflowVersion version) {
        FeatureFlagService flags = featureFlags == null ? null : featureFlags.getIfAvailable();
        if (flags != null) {
            try {
                String tenantId =
                        TenantContext.current().map(TenantContext::getTenantId).orElse(null);
                if (FeatureFlagService.TREATMENT.equals(
                        flags.evaluateActiveFlags(tenantId)
                                .get(
                                        com.agenticai.core.agents.version
                                                .AgentVersionResolutionService
                                                .EXPLICIT_PUBLISH_FLAG))) {
                    return; // explicit mode: saves never touch labels
                }
            } catch (RuntimeException e) {
                log.debug(
                        "Explicit-publish flag evaluation failed for workflow {} - auto mode",
                        workflowId,
                        e);
            }
        }
        VersionLabel target =
                switch (version.getStatus()) {
                    case ACTIVE -> VersionLabel.PRODUCTION;
                    case DRAFT -> VersionLabel.STAGING;
                    default -> null;
                };
        if (target == null) {
            return;
        }
        try {
            Optional<WorkflowVersion> holder =
                    repository.findByWorkflowIdAndLabel(workflowId, target);
            if (holder.isPresent()) {
                if (holder.get().getId().equals(version.getId())) {
                    return;
                }
                WorkflowVersion previous = holder.get();
                previous.clearLabel();
                repository.saveAndFlush(previous);
            }
            version.assignLabel(target);
            repository.save(version);
            recordLabelEvent(
                    workflowId, version, target, LabelEventAction.ASSIGN, "auto-publish on save");
            evict(workflowId);
            publishEvent("version.promoted", workflowId, version, target, true);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not auto-assign {} for workflow {} v{}",
                    target,
                    workflowId,
                    version.getVersionNumber(),
                    e);
        }
    }

    /**
     * The bookkeeping every label move shares - identical in spirit to {@code
     * AgentVersionService}'s: append the {@code version_label_event} audit row, evict the
     * resolution cache (shared Redis = every replica), emit the outbox event.
     */
    private void afterLabelMove(
            UUID workflowId,
            WorkflowVersion target,
            VersionLabel label,
            LabelEventAction action,
            String detail) {
        recordLabelEvent(workflowId, target, label, action, detail);
        evict(workflowId);
        publishEvent(
                action == LabelEventAction.ROLLBACK ? "version.rolled_back" : "version.promoted",
                workflowId,
                target,
                label,
                false);
    }

    private void recordLabelEvent(
            UUID workflowId,
            WorkflowVersion version,
            VersionLabel label,
            LabelEventAction action,
            String detail) {
        VersionLabelEventRepository events =
                labelEvents == null ? null : labelEvents.getIfAvailable();
        if (events == null) {
            return;
        }
        try {
            events.save(
                    VersionLabelEvent.of(
                            VersionEntityType.WORKFLOW,
                            workflowId,
                            label.name(),
                            version.getId(),
                            action,
                            TenantContext.current().map(TenantContext::getSubject).orElse(null),
                            detail));
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record label event for workflow {} v{}",
                    workflowId,
                    version.getVersionNumber(),
                    e);
        }
    }

    private void publishEvent(
            String type,
            UUID workflowId,
            WorkflowVersion version,
            VersionLabel label,
            boolean automatic) {
        WorkflowEventPublisher publisher =
                eventPublisher == null ? null : eventPublisher.getIfAvailable();
        if (publisher == null) {
            return;
        }
        try {
            publisher.publish(
                    type,
                    workflowId.toString(),
                    Map.of(
                            "entityType",
                            "WORKFLOW",
                            "entityId",
                            workflowId.toString(),
                            "versionId",
                            version.getId().toString(),
                            "versionNumber",
                            version.getVersionNumber(),
                            "label",
                            label.name(),
                            "automatic",
                            automatic));
        } catch (RuntimeException e) {
            log.warn(
                    "Could not publish {} for workflow {} v{}",
                    type,
                    workflowId,
                    version.getVersionNumber(),
                    e);
        }
    }

    private String cacheKey(UUID workflowId) {
        String tenant =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElse(TenantSchemaResolver.DEFAULT_SCHEMA);
        return "ver:" + tenant + ":workflow:" + workflowId + ":RUN";
    }

    private String cacheGet(String key) {
        StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
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
        StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.opsForValue().set(key, value, CACHE_TTL);
        } catch (RuntimeException e) {
            log.debug("Version-resolution cache write failed for {}", key, e);
        }
    }

    private Integer latestVersionNumber(UUID workflowId) {
        return repository.latestVersionNumber(workflowId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowVersion> list(UUID workflowId) {
        return repository.findByWorkflowIdOrderByVersionNumberDesc(workflowId);
    }

    @Transactional(readOnly = true)
    public WorkflowVersion get(UUID workflowId, int versionNumber) {
        return repository
                .findByWorkflowIdAndVersionNumber(workflowId, versionNumber)
                .orElseThrow(() -> new WorkflowVersionNotFoundException(workflowId, versionNumber));
    }

    /**
     * Non-throwing lookup for the run-diff path - a stamped version whose snapshot is gone resolves
     * to null, not a 404 on the whole diff.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowVersion> getOptional(UUID workflowId, int versionNumber) {
        return repository.findByWorkflowIdAndVersionNumber(workflowId, versionNumber);
    }

    /**
     * Structural diff between two version snapshots. Node identity is the graph's own {@code id};
     * "changed" compares type, label and config.
     *
     * <p>Phase 4 (canvas version-diff view) added, without removing anything: {@code meta} ({@code
     * fromVersion, toVersion, createdAt, author} of the TO version, plus {@code fromCreatedAt,
     * fromAuthor}) and, on every changed node, {@code fields: [{path, before, after}]} - dotted
     * paths ({@code type}, {@code label}, {@code config.maxIterations}, {@code
     * config.headers.Authorization}); arrays compare whole under their own path. Values whose key
     * looks credential-bearing are replaced by {@code [redacted]} on both sides (the change is
     * still reported), and every other value goes through the trace redactor and a 2 KB cap - a
     * VIEWER can read a diff. The older {@code changes} strings stay for existing callers.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> diffVersions(WorkflowVersion from, WorkflowVersion to) {
        return differ.diffVersions(from, to);
    }

    /** Pure structural diff of two graph_json documents - exposed for the run-diff path too. */
    public Map<String, Object> diffGraphJson(String graphJsonA, String graphJsonB) {
        return differ.diffGraphJson(graphJsonA, graphJsonB);
    }

    /**
     * Run-to-run diff - see this class's javadoc. Both runs must belong to the same workflow (the
     * caller enforces); {@code versionA/B} are the snapshots each run's stamped {@code
     * workflow_version} resolves to, either nullable (run predates stamping, or the snapshot is
     * gone).
     */
    public Map<String, Object> diffRuns(
            WorkflowRun a, WorkflowRun b, WorkflowVersion versionA, WorkflowVersion versionB) {
        return differ.diffRuns(a, b, versionA, versionB);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
