package com.agenticai.core.brief;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.brief.BriefApplyLedger.JobView;
import com.agenticai.core.brief.BriefApplyLedger.StepView;
import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyRequest;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyResult;
import com.agenticai.core.brief.dto.ApplyDtos.StepDto;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

/**
 * The apply engine: turns a stored Brief into a running envoy through the platform's real services,
 * as a saga.
 *
 * <p><b>Order</b> (each phase depends on the ones before): knowledge, procedures, tools, MCP
 * connectors, agents (created at the rung's autonomy tier, then wired to their tools / knowledge /
 * procedures / guardrails), workflows (created as drafts, with the rung's approval nodes already in
 * the graph), the approval policy those nodes need, workflow activation and triggers, channels, the
 * eval dataset, the quality gate, and finally a prune of anything the brief no longer declares.
 * Deviation from a pure "trust last" order, on purpose: the approval policy and the autonomy tier
 * are in place BEFORE anything is activated or routed, so there is no moment where a live trigger
 * fires an envoy that is less supervised than its rung.
 *
 * <p><b>Atomicity by compensation.</b> There is no transaction around an apply - each real service
 * call is its own transaction. Instead every step is written to {@code brief_apply_step} before the
 * call and finished after it, and each created entity is bound ({@code brief_binding}) the moment
 * the service returns it. On any failure the engine walks its undo log in reverse and calls the
 * real delete services, marks the steps COMPENSATED, and marks the job FAILED naming the offending
 * step. An update made on a re-apply is undone by re-applying the previous spec. An undo that
 * itself fails (for example a FREE plan cannot delete agents) is reported in {@code orphans}, never
 * hidden.
 *
 * <p><b>Re-apply</b> diffs the stored manifest against the last successfully applied one and, for
 * each artifact already bound, updates it in place through the same services (which is what creates
 * their versions); unchanged artifacts are left alone, and artifacts the brief no longer declares
 * are pruned last. Agents are always re-wired (the brief is the source of truth for the allow-list
 * and scopes).
 *
 * <p><b>Degrade, don't fail.</b> Tools and MCP connectors are skipped (and stay WAITING_ON_ADMIN /
 * MISSING in readiness) when the caller lacks PERM_MANAGE_INTEGRATIONS or a credential is not in
 * the vault; an allow-list entry for a skipped artifact is dropped, never widened.
 *
 * <p><b>Background jobs</b> capture the caller's {@link TenantContext} and restore it on the worker
 * thread, clearing it afterwards, the same way {@code WorkflowBackgroundRunService} does.
 */
@Service
public class BriefApplyService {

    private static final Logger log = LoggerFactory.getLogger(BriefApplyService.class);

    /**
     * An allow-list entry that names no tool. An EMPTY allow-list means "unrestricted" to the
     * platform, so an agent that must have no tools is given this instead.
     */
    public static final String NO_TOOLS = "brief:none";

    static final String NOTE = "NOTE: ";
    static final String SKIPPED = "SKIPPED: ";
    static final String EVAL_REF = "eval";
    private static final double DEFAULT_GATE_MIN_SCORE = 0.7;
    private static final Duration STALE_JOB = Duration.ofMinutes(30);
    private static final int MAX_CONCURRENT_JOBS = 2;
    private static final int MAX_QUEUED_JOBS = 8;
    private static final int GATE_MAX_RUN_AGE_HOURS = 24 * 7;

    private final BriefVersionStore store;
    private final BriefApplyLedger ledger;
    private final EnvoyPorts ports;
    private final BriefValidator validator;
    private final BriefReadinessService readinessService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final ObjectProvider<ProofSource> proofSource;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor pool;
    private final BriefApplyPhases phases;
    private final BriefApplyPrune prune;

    /**
     * What to tell once an apply has succeeded (the proof ledger's baseline run); empty until set.
     */
    private volatile List<BriefAppliedHook> appliedHooks = List.of();

    public BriefApplyService(
            BriefVersionStore store,
            BriefApplyLedger ledger,
            EnvoyPorts ports,
            BriefValidator validator,
            BriefReadinessService readinessService,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            ObjectProvider<ProofSource> proofSource) {
        this.store = store;
        this.ledger = ledger;
        this.ports = ports;
        this.validator = validator;
        this.readinessService = readinessService;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.proofSource = proofSource;
        this.phases =
                new BriefApplyPhases(ports, ledger, readinessService, objectMapper, proofSource);
        this.prune = new BriefApplyPrune(ports, ledger);
        CustomizableThreadFactory threads = new CustomizableThreadFactory("brief-apply-");
        threads.setDaemon(true);
        this.pool =
                TenantAwareExecutors.newThreadPool(
                        MAX_CONCURRENT_JOBS,
                        MAX_CONCURRENT_JOBS,
                        60,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(MAX_QUEUED_JOBS),
                        threads);
        this.pool.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    // ================================================================== entry points

    /**
     * Applies the brief's current version. With {@code request.brief()} set, that manifest is first
     * saved as the next version (validated, rung untouched) - this is how an accepted tune proposal
     * is applied.
     */
    public ApplyResult apply(UUID briefId, ApplyRequest request) {
        BriefEntity entity = store.require(briefId);
        int versionBefore = entity.getCurrentVersion();
        boolean background = request != null && request.isBackground();
        boolean appended = false;
        if (request != null && request.brief() != null) {
            if (request.baseVersion() != null
                    && request.baseVersion() != entity.getCurrentVersion()) {
                throw new BriefConflictException(
                        "This brief moved on to version "
                                + entity.getCurrentVersion()
                                + " since the proposal was made from version "
                                + request.baseVersion()
                                + ". Tune it again.");
            }
            requireSavable(request.brief(), ledger.bindings(briefId).isEmpty());
            store.append(
                    briefId,
                    request.brief(),
                    request.note() == null || request.note().isBlank() ? "Tuned" : request.note(),
                    BriefVersionStore.actor(),
                    TrustRung.parseOrLowest(entity.getRung()));
            appended = true;
        }
        try {
            return applyCurrent(briefId, background);
        } catch (RuntimeException e) {
            if (appended) {
                // Refused before a job started: nothing changed, so keep pointing at what is really
                // applied.
                store.setCurrent(briefId, versionBefore);
            }
            throw e;
        }
    }

    /**
     * Applies the brief's current version (used by rollback after it appended the older manifest).
     */
    public ApplyResult applyCurrent(UUID briefId, boolean background) {
        BriefEntity entity = store.require(briefId);
        Brief brief = store.loadCurrent(entity);
        requireSavable(brief, ledger.bindings(briefId).isEmpty());

        if (ledger.runningJob(briefId, Instant.now().minus(STALE_JOB)).isPresent()
                || !running.add(briefId)) {
            throw new BriefConflictException(
                    "This brief is already being applied. Wait for it to finish.");
        }
        UUID jobId = UUID.randomUUID();
        try {
            ledger.startJob(briefId, entity.getCurrentVersion(), jobId);
        } catch (RuntimeException e) {
            running.remove(briefId);
            throw e;
        }
        if (!background) {
            try {
                run(briefId, jobId);
            } finally {
                running.remove(briefId);
            }
            return result(briefId, jobId);
        }
        Optional<TenantContext> tenant = TenantContext.current();
        try {
            pool.execute(
                    () -> {
                        tenant.ifPresent(TenantContext::set);
                        try {
                            run(briefId, jobId);
                        } finally {
                            // Pooled thread: a leaked TenantContext on a reused worker is a
                            // cross-tenant bug.
                            TenantContext.clear();
                            running.remove(briefId);
                        }
                    });
        } catch (RejectedExecutionException e) {
            running.remove(briefId);
            ledger.finishJob(
                    briefId,
                    jobId,
                    false,
                    "Too many applies are running right now - try again in a moment.");
        }
        return result(briefId, jobId);
    }

    /**
     * Setter, not a constructor argument, so the hooks can depend on services that depend on this
     * one.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAppliedHooks(List<BriefAppliedHook> hooks) {
        this.appliedHooks = hooks == null ? List.of() : List.copyOf(hooks);
    }

    public ApplyResult job(UUID briefId, UUID jobId) {
        store.require(briefId);
        if (ledger.job(briefId, jobId).isEmpty()) {
            throw new BriefNotFoundException(briefId);
        }
        return result(briefId, jobId);
    }

    /**
     * Removes every entity this brief created (reverse dependency order) and its bindings.
     * Synchronous. A removal that fails is reported in the returned steps and left bound so the
     * purge can be retried.
     */
    public ApplyResult purge(UUID briefId) {
        BriefEntity entity = store.require(briefId);
        if (!running.add(briefId)) {
            throw new BriefConflictException(
                    "This brief is being applied right now. Wait for it to finish.");
        }
        UUID jobId = UUID.randomUUID();
        try {
            ledger.startJob(briefId, entity.getCurrentVersion(), jobId);
            ApplyCtx c =
                    new ApplyCtx(
                            briefId,
                            entity.getCurrentVersion(),
                            jobId,
                            loadQuietly(briefId, entity.getCurrentVersion()),
                            null,
                            TrustRung.UNDER_WATCH);
            c.bindings.putAll(ledger.bindings(briefId));
            loadCaller(c);
            prune.pruneOrphans(c, Set.of());
            boolean clean =
                    ledger.steps(briefId, jobId).stream()
                            .noneMatch(s -> BriefApplyStepEntity.FAILED.equals(s.status()));
            ledger.finishJob(
                    briefId,
                    jobId,
                    clean,
                    clean ? "Purged." : "Some entities could not be removed.");
        } finally {
            running.remove(briefId);
        }
        return result(briefId, jobId);
    }

    // ================================================================== the job

    private void run(UUID briefId, UUID jobId) {
        ApplyCtx c = null;
        boolean firstApply = true;
        Integer lastAppliedVersion = null;
        try {
            BriefEntity entity = store.require(briefId);
            // The version the job row was created for, not whatever is current when a queued job
            // finally starts:
            // apply() may have appended (and be about to revert) a newer one in between.
            int jobVersion =
                    ledger.job(briefId, jobId)
                            .map(JobView::version)
                            .orElse(entity.getCurrentVersion());
            Brief brief =
                    jobVersion == entity.getCurrentVersion()
                            ? store.loadCurrent(entity)
                            : store.load(briefId, jobVersion);
            TrustRung rung = TrustRung.parseOrLowest(entity.getRung());
            Optional<JobView> lastOk = ledger.latestSuccessfulJob(briefId);
            firstApply = lastOk.isEmpty();
            // A failed re-apply points the brief back at what was current before this job's version
            // was appended:
            // not the last job's version, which predates any rung change made since (its manifest
            // would overclaim).
            lastAppliedVersion =
                    lastOk.map(
                                    j ->
                                            j.version() == jobVersion
                                                    ? null
                                                    : rewindTarget(
                                                            briefId, jobVersion, j.version()))
                            .orElse(null);
            Brief previous = lastOk.map(j -> loadQuietly(briefId, j.version())).orElse(null);
            c = new ApplyCtx(briefId, jobVersion, jobId, brief, previous, rung);
            c.bindings.putAll(ledger.bindings(briefId));
            loadCaller(c);

            phases.knowledge(c);
            phases.procedures(c);
            phases.tools(c);
            phases.mcp(c);
            phases.agents(c);
            phases.workflows(c);
            phases.trustPolicy(c);
            phases.activateAndTriggers(c);
            phases.channels(c);
            phases.eval(c);
            phases.trustGate(c);
            prune.pruneOrphans(c, declaredRefs(brief));

            ledger.finishJob(briefId, jobId, true, summary(c));
            store.setStatus(briefId, BriefEntity.APPLIED);
            audit(
                    "BRIEF_APPLIED",
                    briefId,
                    "version " + entity.getCurrentVersion() + " job " + jobId,
                    AuditOutcome.SUCCESS);
            notifyApplied(briefId);
        } catch (ApplyCtx.StepFailure failure) {
            String message =
                    "Step "
                            + failure.seq
                            + " ("
                            + failure.kind
                            + (failure.ref == null ? "" : " " + failure.ref)
                            + ") failed: "
                            + failure.getMessage();
            failJob(c, briefId, jobId, firstApply, lastAppliedVersion, message, failure);
        } catch (RuntimeException e) {
            failJob(
                    c,
                    briefId,
                    jobId,
                    firstApply,
                    lastAppliedVersion,
                    "Apply stopped unexpectedly: " + messageOf(e),
                    e);
        }
    }

    /**
     * What the caller may do beyond the base apply authorities; each degraded or refused part names
     * its permission.
     */
    private void loadCaller(ApplyCtx c) {
        c.canIntegrate = ports.canManageIntegrations();
        c.canTools = ports.canManageTools();
        c.canChannels = ports.canManageChannels();
    }

    private Integer rewindTarget(UUID briefId, int jobVersion, int lastJobVersion) {
        return store.versions(briefId).stream()
                .filter(v -> v.getVersion() == jobVersion)
                .map(BriefVersionEntity::getParentVersion)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(lastJobVersion);
    }

    private void notifyApplied(UUID briefId) {
        for (BriefAppliedHook hook : appliedHooks) {
            try {
                hook.applied(briefId);
            } catch (RuntimeException e) {
                log.warn(
                        "Brief {}: a post-apply hook failed (the apply itself succeeded)",
                        briefId,
                        e);
            }
        }
    }

    private void failJob(
            ApplyCtx c,
            UUID briefId,
            UUID jobId,
            boolean firstApply,
            Integer lastAppliedVersion,
            String message,
            Throwable cause) {
        log.warn("Brief {} apply job {} failed: {}", briefId, jobId, message, cause);
        if (c != null) {
            compensate(c);
        }
        try {
            ledger.finishJob(briefId, jobId, false, message);
            // A first apply that failed and was rolled back leaves nothing running: FAILED
            // (editable again).
            // A failed re-apply leaves the previously applied envoy in place: still APPLIED.
            if (firstApply) {
                store.setStatus(briefId, BriefEntity.FAILED);
            } else if (lastAppliedVersion != null) {
                // The manifest that failed stays in the version history, but the brief points at
                // what is really running.
                store.setCurrent(briefId, lastAppliedVersion);
            }
            audit("BRIEF_APPLY_FAILED", briefId, message, AuditOutcome.FAILURE);
        } catch (RuntimeException e) {
            log.error("Could not record the failure of brief {} job {}", briefId, jobId, e);
        }
    }

    /**
     * Undo log, newest first. Never throws: an undo that fails is noted on its step and the walk
     * continues.
     */
    private void compensate(ApplyCtx c) {
        while (!c.undo.isEmpty()) {
            ApplyCtx.Undo u = c.undo.pop();
            try {
                u.action().run();
                if (u.created()) {
                    ledger.unbind(c.briefId, u.ref());
                    c.bindings.remove(u.ref());
                }
                ledger.compensated(u.stepId());
            } catch (RuntimeException e) {
                log.error(
                        "Brief {}: could not undo step {} ({}): {}",
                        c.briefId,
                        u.stepId(),
                        u.ref(),
                        e.getMessage());
                try {
                    ledger.compensationFailed(u.stepId(), messageOf(e));
                } catch (RuntimeException ledgerFailure) {
                    log.error(
                            "Could not record a compensation failure for step {}",
                            u.stepId(),
                            ledgerFailure);
                }
            }
        }
    }

    private void requireSavable(Brief brief, boolean checkCaps) {
        if (brief != null && brief.isClarificationOnly()) {
            throw new BriefValidationException(
                    "clarifications",
                    "This is a set of questions, not a plan - answer them and plan again before applying.");
        }
        BriefValidationResult result = validator.validate(brief, checkCaps);
        List<Issue> errors = result.errors();
        if (!errors.isEmpty()) {
            throw new BriefValidationException(errors);
        }
    }

    private Brief loadQuietly(UUID briefId, int version) {
        try {
            return store.load(briefId, version);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ApplyResult result(UUID briefId, UUID jobId) {
        JobView job =
                ledger.job(briefId, jobId).orElseThrow(() -> new BriefNotFoundException(briefId));
        List<StepView> steps = ledger.steps(briefId, jobId);
        String status =
                switch (job.status()) {
                    case BriefApplyStepEntity.DONE -> "SUCCEEDED";
                    case BriefApplyStepEntity.FAILED -> "FAILED";
                    default -> "RUNNING";
                };
        StepDto failed = null;
        List<StepDto> orphans = new ArrayList<>();
        for (StepView s : steps) {
            StepDto dto =
                    new StepDto(s.seq(), s.kind(), s.ref(), s.entityId(), s.status(), s.error());
            if (s.error() != null && s.error().contains("compensation failed")) {
                orphans.add(dto);
            }
            boolean note =
                    s.error() != null
                            && (s.error().startsWith(NOTE) || s.error().startsWith(SKIPPED));
            if (failed == null
                    && s.error() != null
                    && !note
                    && !s.error().startsWith("compensation failed")
                    && !BriefApplyStepEntity.DONE.equals(s.status())) {
                failed = dto;
            }
        }
        List<StepDto> dtos =
                steps.stream()
                        .map(
                                s ->
                                        new StepDto(
                                                s.seq(),
                                                s.kind(),
                                                s.ref(),
                                                s.entityId(),
                                                s.status(),
                                                s.error()))
                        .toList();
        return new ApplyResult(
                jobId,
                status,
                job.version(),
                "FAILED".equals(status) ? job.message() : null,
                "FAILED".equals(status) ? failed : null,
                dtos,
                orphans);
    }

    private String summary(ApplyCtx c) {
        long skipped = c.skipped.size();
        return "Applied version "
                + c.version
                + " with "
                + c.seq
                + " step(s)"
                + (skipped > 0 ? ", " + skipped + " skipped" : "")
                + ".";
    }

    private void audit(String action, UUID briefId, String detail, AuditOutcome outcome) {
        try {
            auditLogService.record(
                    BriefVersionStore.actor(),
                    action,
                    "brief",
                    briefId.toString(),
                    outcome,
                    detail);
        } catch (RuntimeException e) {
            log.warn("Could not audit {} for brief {}", action, briefId, e);
        }
    }

    private static Set<String> declaredRefs(Brief brief) {
        Set<String> refs = new LinkedHashSet<>(BriefRefs.artifactRefs(brief).keySet());
        if (brief.evalSet() != null) {
            refs.add(EVAL_REF);
        }
        return refs;
    }

    static String mcpServerName(UUID briefId, Brief.McpServerSpec m) {
        String base =
                (m.name() != null && !m.name().isBlank() ? m.name() : m.registryId())
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "server";
        }
        if (base.length() > 30) {
            base = base.substring(base.length() - 30).replaceAll("^-+", "");
        }
        return "brief-" + briefId.toString().substring(0, 8) + "-" + base;
    }

    static String messageOf(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
