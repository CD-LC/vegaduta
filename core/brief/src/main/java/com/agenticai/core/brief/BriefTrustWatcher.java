package com.agenticai.core.brief;

import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.ProofData.EvalRunPoint;
import com.agenticai.core.brief.dto.TrustDtos.TrustChange;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.guardrails.GuardrailViolationEvent;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

/**
 * Automatic demotion (contract section 1): a guardrail strike on an envoy agent, or a gate breach on an eval run,
 * lowers the brief one rung through {@link TrustLadderService#demote}.
 *
 * <p><b>The hook is an event, not a change to the guardrail path.</b> {@code GuardrailViolationLogger} now publishes a
 * {@link GuardrailViolationEvent} after it has written its audit row; this class listens. Nothing in chat waits on
 * it: the listener does two cheap things on the chat thread (find the briefs that own the struck agent, write one
 * audit row per brief as durable evidence) and hands the demotion itself - which edits workflows, tiers and the
 * gate - to a small worker with a system tenant context. Strikes from {@code eval:} and {@code sim:} sessions are
 * quality-tooling replays, never a live agent misbehaving, and are ignored (a rehearsal must not demote what it tests).
 *
 * <p><b>The poll is the backstop and the only gate-breach detector.</b> Every five minutes, per tenant, each APPLIED
 * brief above UNDER_WATCH is checked for (a) a strike recorded since its rung last changed that the event path failed
 * to act on (an apply was running, the worker queue was full, the process restarted) and (b) a finished eval run since
 * then whose verdict is FAILED. Both compare against the rung's own start ({@link ProofLedgerService#observedSince}),
 * which a demotion moves forward, so one breach lowers one rung, once.
 *
 * <p><b>Authority.</b> The demotion is a protective action nobody is present to authorise, so it runs as
 * {@code system:brief-trust} with the tenant-admin / manage-agents / manage-workflows authorities inside the one tenant
 * it concerns - the same elevation {@code TenantSchemaIterator} jobs use to touch tenant data, scoped narrower.
 * It can only ever lower autonomy (it calls {@code demote}, nothing else).
 *
 * <p><b>What is not covered.</b> Only content-guardrail blocks reach {@code GuardrailViolationLogger}. Tool-call
 * guardrail rejections and moderation-pack blocks are recorded elsewhere and are not strikes here.
 */
@Component
public class BriefTrustWatcher {

    private static final Logger log = LoggerFactory.getLogger(BriefTrustWatcher.class);

    static final String SYSTEM_SUBJECT = "system:brief-trust";
    static final Set<String> SYSTEM_ROLES = Set.of("ROLE_tenant-admin", "PERM_MANAGE_AGENTS", "PERM_MANAGE_WORKFLOWS_ADMIN");
    private static final String AGENT = "AGENT";

    private final BriefBindingRepository bindingRepository;
    private final BriefRepository briefRepository;
    private final BriefApplyLedger applyLedger;
    private final ProofData data;
    private final ProofLedgerService proofLedger;
    private final TrustLadderService trust;
    private final EnvoyPorts ports;
    private final TenantSchemaIterator tenantIterator;
    private final ThreadPoolExecutor worker;
    private final PgAdvisoryJobLock jobLock;
    /**
     * One demotion decision at a time. The worker and the poll thread both read the rung, decide, then lower it;
     * without this they could each lower it from the same strike. A {@link ReentrantLock}, not
     * {@code synchronized}: the critical section does DB reads and writes, and a {@code synchronized} block
     * around blocking I/O pins a virtual thread to its carrier on JDK 21 (JAVA-024).
     */
    private final ReentrantLock demotionLock = new ReentrantLock();

    public BriefTrustWatcher(
        BriefBindingRepository bindingRepository,
        BriefRepository briefRepository,
        BriefApplyLedger applyLedger,
        ProofData data,
        ProofLedgerService proofLedger,
        TrustLadderService trust,
        EnvoyPorts ports,
        TenantSchemaIterator tenantIterator,
        PgAdvisoryJobLock jobLock
    ) {
        this.jobLock = jobLock;
        this.bindingRepository = bindingRepository;
        this.briefRepository = briefRepository;
        this.applyLedger = applyLedger;
        this.data = data;
        this.proofLedger = proofLedger;
        this.trust = trust;
        this.ports = ports;
        this.tenantIterator = tenantIterator;
        CustomizableThreadFactory threads = new CustomizableThreadFactory("brief-trust-");
        threads.setDaemon(true);
        this.worker = TenantAwareExecutors.newThreadPool(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), threads);
        this.worker.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // ================================================================== guardrail strikes (event)

    @EventListener
    public void onGuardrailViolation(GuardrailViolationEvent event) {
        if (event == null || event.agentId() == null || isQualityReplay(event.sessionId())) {
            return;
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return;
        }
        List<BriefBindingEntity> owners;
        try {
            owners = bindingRepository.findByKindAndEntityId(AGENT, event.agentId().toString());
        } catch (RuntimeException e) {
            log.debug("Could not look up the briefs owning agent {}: {}", event.agentId(), e.getMessage());
            return;
        }
        for (BriefBindingEntity owner : owners) {
            UUID briefId = owner.getBriefId();
            String detail = String.format(Locale.ROOT, "path=%s guardrail=%s session=%s reason=%s",
                event.inputPath() ? "input" : "output", event.guardrail(), event.sessionId(), event.reason());
            boolean recorded = true;
            try {
                data.recordStrike(briefId, event.agentId().toString(), detail);
            } catch (RuntimeException e) {
                recorded = false;
                log.warn("Brief {}: could not record a guardrail strike", briefId, e);
            }
            boolean strikeOnRecord = recorded;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("agent", event.agentId().toString());
            evidence.put("path", event.inputPath() ? "input" : "output");
            evidence.put("guardrail", event.guardrail());
            evidence.put("session", event.sessionId());
            String reason = "A guardrail blocked a live turn of one of this solution's agents ("
                + (event.guardrail() == null ? "content guardrail" : event.guardrail()) + ").";
            try {
                worker.execute(() -> demoteForStrike(tenantId, briefId, reason, evidence, strikeOnRecord));
            } catch (RejectedExecutionException e) {
                log.warn("Brief {}: demotion queue is full; the poll will act on the recorded strike", briefId);
            }
        }
    }

    static boolean isQualityReplay(String sessionId) {
        return sessionId != null && (sessionId.startsWith("eval:") || sessionId.startsWith("sim:"));
    }

    // ================================================================== poll (gate breach + strike backstop)

    /**
     * One replica per tick (JAVA-023): the in-JVM {@link #demotionLock} cannot stop two replicas demoting the same
     * brief from the same strike, the advisory job lock does.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 2 * 60 * 1000L)
    public void poll() {
        // demotionLock only serialises threads of ONE JVM; two replicas polling the same brief could each
        // lower its rung from the same strike. The advisory lock makes one replica per tick do the poll.
        jobLock.tryRunExclusive("brief-trust-watcher", this::pollAllTenants);
    }

    private void pollAllTenants() {
        tenantIterator.forEachActiveTenantSchema(schema -> {
            TenantContext.set(TenantContext.of(schema, SYSTEM_SUBJECT, SYSTEM_ROLES));
            for (BriefEntity brief : briefRepository.findAllByOrderByUpdatedAtDesc()) {
                try {
                    check(brief);
                } catch (RuntimeException e) {
                    log.warn("Brief {}: trust check failed", brief.getId(), e);
                }
            }
        });
    }

    /**
     * One brief, one look: lowers it a rung when a strike or a failed eval run exists since its rung last changed.
     * The caller has the tenant context set. Empty when there is nothing to do.
     */
    Optional<TrustChange> check(BriefEntity brief) {
        demotionLock.lock();
        try {
            return checkLocked(brief);
        } finally {
            demotionLock.unlock();
        }
    }

    private Optional<TrustChange> checkLocked(BriefEntity brief) {
        TrustRung rung = TrustRung.parseOrLowest(brief.getRung());
        if (!BriefEntity.APPLIED.equals(brief.getStatus()) || rung == TrustRung.UNDER_WATCH) {
            return Optional.empty();
        }
        Instant since = proofLedger.observedSince(brief);
        int strikes = data.strikes(brief.getId(), since);
        if (strikes > 0) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("strikes", strikes);
            return trust.demote(brief.getId(), strikes + " guardrail strike(s) since this rung began.", evidence);
        }
        Optional<String> breach = gateBreach(brief.getId(), since);
        if (breach.isPresent()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("gate", breach.get());
            return trust.demote(brief.getId(), breach.get(), evidence);
        }
        return Optional.empty();
    }

    /** A finished eval run since {@code since} whose verdict is FAILED, worded for the trust event. */
    private Optional<String> gateBreach(UUID briefId, Instant since) {
        if (!ports.planAllowsEval()) {
            return Optional.empty();
        }
        Binding dataset = applyLedger.bindings(briefId).get(BriefApplyService.EVAL_REF);
        if (dataset == null) {
            return Optional.empty();
        }
        EvalRunPoint worst = null;
        for (EvalRunPoint run : data.evalRuns(dataset.entityId())) {
            if (!run.at().isBefore(since) && "FAILED".equals(run.verdict()) && (worst == null || run.score() < worst.score())) {
                worst = run;
            }
        }
        return worst == null ? Optional.empty() : Optional.of(String.format(Locale.ROOT,
            "An eval run scored %.2f, below the quality gate of %.2f.", worst.score(), worst.passThreshold()));
    }

    // ================================================================== the demotion itself

    /**
     * The event path's demotion. Two strikes queued back to back both describe the same rung, so the second must find
     * the first already acted on: a strike only counts when it is newer than the rung's own start
     * ({@link ProofLedgerService#observedSince}), which the first demotion moved forward. One breach, one rung.
     *
     * @param strikeOnRecord false when the strike's audit row could not be written; the event itself is then the only
     *     evidence and is acted on once
     */
    void demoteForStrike(String tenantId, UUID briefId, String reason, Map<String, Object> evidence, boolean strikeOnRecord) {
        Optional<TenantContext> before = TenantContext.current();
        try {
            TenantContext.set(TenantContext.of(tenantId, SYSTEM_SUBJECT, SYSTEM_ROLES));
            demotionLock.lock();
            try {
                if (strikeOnRecord) {
                    Optional<BriefEntity> brief = briefRepository.findById(briefId);
                    if (brief.isEmpty() || data.strikes(briefId, proofLedger.observedSince(brief.get())) == 0) {
                        return;
                    }
                }
                demoteAs(tenantId, briefId, reason, evidence);
            } finally {
                demotionLock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("Brief {}: automatic demotion did not complete ({}); the poll will retry while the strike stands",
                briefId, e.getMessage());
        } finally {
            before.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /** Lowers {@code briefId} one rung as the system in {@code tenantId}; never throws (a failure is logged, the poll retries). */
    void demoteAs(String tenantId, UUID briefId, String reason, Map<String, Object> evidence) {
        Optional<TenantContext> before = TenantContext.current();
        try {
            TenantContext.set(TenantContext.of(tenantId, SYSTEM_SUBJECT, SYSTEM_ROLES));
            Optional<TrustChange> change = trust.demote(briefId, reason, evidence);
            change.ifPresent(c -> log.warn("Brief {} lowered {} -> {}: {}", briefId, c.fromRung(), c.toRung(), reason));
        } catch (RuntimeException e) {
            log.warn("Brief {}: automatic demotion did not complete ({}); the poll will retry while the strike stands",
                briefId, e.getMessage());
        } finally {
            before.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }
}
