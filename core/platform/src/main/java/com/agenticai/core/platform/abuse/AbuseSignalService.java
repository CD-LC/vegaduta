package com.agenticai.core.platform.abuse;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueRepository;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.audit.AuditLogRepository;
import com.agenticai.core.platform.PlatformTenantNotFoundException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantEmergencyControlService;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Phase P5 (Fraud & Anomaly) - computes a per-(tenant, user) risk score from
 * existing platform signals and maps it to a reversible, audited action tier.
 * Grounded in what the codebase already records (governance doc §1/§7): no new
 * hot-path instrumentation and no new fact table - spend/token signals come
 * from {@code llm_call_log}, flooding/tool-error/signup signals from {@code
 * audit_log_entry} (both tenant-schema-scoped, read via the same {@code
 * TenantContext} sweep {@code PlatformObservabilityService} established), and
 * anomalies are surfaced as fingerprinted {@code ABUSE_ANOMALY} issues via
 * {@code PlatformIssueService}.
 *
 * <p><b>Fail-open by construction.</b> Anomaly detection is a safeguard, never
 * a correctness gate: every signal is computed under its own try/catch and
 * marked {@code unavailable} on error (dropping out of the weighted aggregate),
 * and the whole {@link #score} call is wrapped so any infrastructure hiccup
 * yields a NORMAL zero score rather than an exception. Scoring has NO side
 * effects; taking action is the separate, explicit {@link #evaluateAndAct} step
 * used only by the opt-in scheduled sweep, so the read endpoints stay idempotent.
 *
 * <p><b>Attribution boundary, stated honestly.</b> {@code llm_call_log} has no
 * user column (it attributes by agent/session), so spend-spike and token-burn
 * are tenant-scoped and identical across users of the same tenant; flooding,
 * tool-error and signup-velocity are true per-user (audit {@code actorId}).
 * Geo-velocity has no data source today - it degrades to {@code unavailable}
 * unless a {@link GeoVelocityProvider} bean is wired.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class AbuseSignalService {

    private static final Logger log = LoggerFactory.getLogger(AbuseSignalService.class);
    private static final String SYSTEM_ACTOR = "system:abuse-signal";
    private static final String TARGET_TYPE = "abuse-principal";
    // Bounds the sweep's work per tenant regardless of how many distinct actors were active.
    private static final int MAX_ACTORS_PER_TENANT = 100;

    private static final String JOB_LOCK_NAME = "abuse-signal-sweep";

    private final TenantRepository tenantRepository;
    private final LlmCallLogRepository llmCallLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final PlatformIssueService platformIssueService;
    private final PlatformIssueRepository platformIssueRepository;
    private final AuditLogService auditLogService;
    private final TenantEmergencyControlService emergencyControlService;
    private final AbuseThrottleService throttleService;
    private final AbuseConfig config;
    private final List<AbuseAlertSink> alertSinks;
    private final ObjectProvider<GeoVelocityProvider> geoVelocityProvider;
    private final PgAdvisoryJobLock jobLock;

    public AbuseSignalService(
        TenantRepository tenantRepository,
        LlmCallLogRepository llmCallLogRepository,
        AuditLogRepository auditLogRepository,
        PlatformIssueService platformIssueService,
        PlatformIssueRepository platformIssueRepository,
        AuditLogService auditLogService,
        TenantEmergencyControlService emergencyControlService,
        AbuseThrottleService throttleService,
        AbuseConfig config,
        List<AbuseAlertSink> alertSinks,
        ObjectProvider<GeoVelocityProvider> geoVelocityProvider,
        PgAdvisoryJobLock jobLock
    ) {
        this.tenantRepository = tenantRepository;
        this.llmCallLogRepository = llmCallLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.platformIssueService = platformIssueService;
        this.platformIssueRepository = platformIssueRepository;
        this.auditLogService = auditLogService;
        this.emergencyControlService = emergencyControlService;
        this.throttleService = throttleService;
        this.config = config;
        this.alertSinks = alertSinks;
        this.geoVelocityProvider = geoVelocityProvider;
        this.jobLock = jobLock;
    }

    // ---- Read (no side effects) -------------------------------------------

    /**
     * On-demand risk score for one principal. Throws {@link
     * PlatformTenantNotFoundException} (-&gt; 404) for an unknown tenant; every
     * other failure is swallowed into a NORMAL zero score (fail-open).
     */
    public AbuseRiskScore score(UUID tenantId, String userId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        return scoreTenantScoped(tenant, userId, config.current());
    }

    /**
     * Current scores across every active tenant's recently-active principals -
     * the admin dashboard read. Capped per tenant and overall; a single
     * tenant's failure is logged and skipped, never aborts the sweep. Only
     * returns principals at or above WATCH (NORMAL principals are the vast
     * majority and would drown the view).
     */
    public List<AbuseRiskScore> currentScores() {
        AbuseThresholds t = config.current();
        Instant since = Instant.now().minus(Duration.ofMinutes(t.recentWindowMinutes()));
        List<AbuseRiskScore> out = new ArrayList<>();
        for (Tenant tenant : activeTenants()) {
            try {
                TenantContext.set(TenantContext.of(tenant.getSchemaName(), SYSTEM_ACTOR, Set.of()));
                try {
                    List<String> actors = auditLogRepository.findDistinctActorIdsSince(since);
                    int limit = Math.min(actors.size(), MAX_ACTORS_PER_TENANT);
                    for (int i = 0; i < limit; i++) {
                        AbuseRiskScore s = computeSignalsInContext(tenant, actors.get(i), t);
                        if (s.tier() != AbuseActionTier.NORMAL) {
                            out.add(s);
                        }
                    }
                } finally {
                    TenantContext.clear();
                }
            } catch (RuntimeException e) {
                log.warn("Abuse scoring failed for tenant schema '{}' (skipped)", tenant.getSchemaName(), e);
            }
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /**
     * Recent {@code ABUSE_ANOMALY} issues across every active tenant, newest
     * first - the durable record of what the detector has flagged/acted on
     * (WATCH/THROTTLE/SUSPEND all write one). Read-only; per-tenant failures
     * are logged and skipped.
     */
    public List<AbuseAnomalyEntry> recentAnomalies() {
        List<AbuseAnomalyEntry> out = new ArrayList<>();
        for (Tenant tenant : activeTenants()) {
            try {
                TenantContext.set(TenantContext.of(tenant.getSchemaName(), SYSTEM_ACTOR, Set.of()));
                try {
                    for (PlatformIssue issue :
                        platformIssueRepository.findTop200BySourceOrderByLastSeenAtDesc(IssueSource.ABUSE_ANOMALY)) {
                        out.add(new AbuseAnomalyEntry(
                            tenant.getId(), tenant.getName(), issue.getId(), issue.getSourceId(),
                            issue.getSeverity(), issue.getStatus(), issue.getTitle(),
                            issue.getOccurrenceCount(), issue.getFirstSeenAt(), issue.getLastSeenAt()
                        ));
                    }
                } finally {
                    TenantContext.clear();
                }
            } catch (RuntimeException e) {
                log.warn("Failed to read anomalies for tenant schema '{}' (skipped)", tenant.getSchemaName(), e);
            }
        }
        out.sort((a, b) -> b.lastSeenAt().compareTo(a.lastSeenAt()));
        return out.size() > 200 ? out.subList(0, 200) : out;
    }

    /** Clear a principal's throttle - the reversible admin action. */
    public void clearThrottle(UUID tenantId, String userId, String actor) {
        throttleService.clear(tenantId.toString(), userId, actor);
    }

    /** Current thresholds snapshot - a read-only view (editing is via {@code PUT /api/platform/settings/{key}}). */
    public AbuseThresholds currentThresholds() {
        return config.current();
    }

    /** Currently-throttled principals. */
    public List<AbuseThrottleService.ThrottleEntry> currentThrottled() {
        return throttleService.current();
    }

    // ---- Detection + action (scheduled, opt-in) ---------------------------

    /**
     * Score one principal and apply its tier's action. Public so an operator
     * tool/test can trigger it deliberately; the scheduled sweep is its only
     * automatic caller. Returns the score that drove the decision.
     */
    public AbuseRiskScore evaluateAndAct(UUID tenantId, String userId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        AbuseThresholds t = config.current();
        AbuseRiskScore score = scoreTenantScoped(tenant, userId, t);
        applyTier(tenant, score, t);
        return score;
    }

    /**
     * Opt-in background sweep (default OFF via {@code abuse.scan.enabled}). Fixed
     * schedule so the bean wires cleanly; the body no-ops instantly when
     * disabled, so it never touches the DB in tests or on a default deployment.
     * When enabled it scores every active tenant's recently-active principals
     * and acts on any at or above WATCH.
     *
     * <p>Multi-replica: the enabled sweep runs under {@link PgAdvisoryJobLock}.
     * The scan-enabled check deliberately stays OUTSIDE the lock so the
     * default-off deployment keeps its "never touches the DB" property - an
     * advisory-lock probe is itself a DB round-trip. Two replicas sweeping the
     * same window would double-apply tiered actions (duplicate alerts/audit
     * entries, redundant throttle writes), so the lock is real de-duplication,
     * not just insurance.
     */
    @Scheduled(
        fixedDelayString = "${abuse.scan.interval-ms:300000}",
        initialDelayString = "${abuse.scan.initial-delay-ms:120000}"
    )
    public void scheduledSweep() {
        AbuseThresholds t = config.current();
        if (!t.scanEnabled()) {
            return;
        }
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica sweeping is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, () -> sweepExclusively(t));
    }

    private void sweepExclusively(AbuseThresholds t) {
        Instant since = Instant.now().minus(Duration.ofMinutes(t.recentWindowMinutes()));
        int acted = 0;
        for (Tenant tenant : activeTenants()) {
            List<String> actors;
            try {
                TenantContext.set(TenantContext.of(tenant.getSchemaName(), SYSTEM_ACTOR, Set.of()));
                try {
                    actors = auditLogRepository.findDistinctActorIdsSince(since);
                } finally {
                    TenantContext.clear();
                }
            } catch (RuntimeException e) {
                log.warn("Abuse sweep: failed to list actors for tenant '{}' (skipped)", tenant.getSchemaName(), e);
                continue;
            }
            int limit = Math.min(actors.size(), MAX_ACTORS_PER_TENANT);
            for (int i = 0; i < limit; i++) {
                try {
                    AbuseRiskScore s = scoreTenantScoped(tenant, actors.get(i), t);
                    if (s.tier() != AbuseActionTier.NORMAL) {
                        applyTier(tenant, s, t);
                        acted++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Abuse sweep: scoring/acting failed for {}:{} (skipped)",
                        tenant.getId(), actors.get(i), e);
                }
            }
        }
        if (acted > 0) {
            log.info("Abuse sweep completed - acted on {} principal(s)", acted);
        }
    }

    // ---- Scoring internals ------------------------------------------------

    /** Sets tenant context around signal computation; fail-open to a NORMAL zero score. */
    private AbuseRiskScore scoreTenantScoped(Tenant tenant, String userId, AbuseThresholds t) {
        try {
            TenantContext.set(TenantContext.of(tenant.getSchemaName(), SYSTEM_ACTOR, Set.of()));
            try {
                return computeSignalsInContext(tenant, userId, t);
            } finally {
                TenantContext.clear();
            }
        } catch (RuntimeException e) {
            log.warn("Abuse scoring hiccup for {}:{} - returning NORMAL (fail-open)", tenant.getId(), userId, e);
            return new AbuseRiskScore(tenant.getId(), tenant.getName(), userId, 0.0,
                AbuseActionTier.NORMAL, List.of(), Instant.now());
        }
    }

    /** Must be called with TenantContext already set to {@code tenant}'s schema. */
    private AbuseRiskScore computeSignalsInContext(Tenant tenant, String userId, AbuseThresholds t) {
        Instant now = Instant.now();
        Instant recentSince = now.minus(Duration.ofMinutes(t.recentWindowMinutes()));
        Instant baselineSince = now.minus(Duration.ofMinutes(t.baselineWindowMinutes()));
        double recentMinutes = Math.max(1, t.recentWindowMinutes());
        double baselineMinutes = Math.max(recentMinutes, t.baselineWindowMinutes());

        List<AbuseSignal> signals = new ArrayList<>();
        signals.add(spendSpikeSignal(recentSince, baselineSince, recentMinutes, baselineMinutes, t));
        signals.add(tokenBurnSignal(recentSince, recentMinutes, t));
        signals.add(messageFloodSignal(userId, recentSince, recentMinutes, t));
        signals.add(signupVelocitySignal(userId, now, t));
        signals.add(toolErrorSignal(userId, recentSince, t));
        signals.add(geoVelocitySignal(tenant.getId(), userId, t));

        double aggregate = AbuseScoreCalculator.aggregate(signals);
        AbuseActionTier tier = AbuseScoreCalculator.decideTier(aggregate, t);
        return new AbuseRiskScore(tenant.getId(), tenant.getName(), userId, round1(aggregate), tier, signals, now);
    }

    private AbuseSignal spendSpikeSignal(
        Instant recentSince, Instant baselineSince, double recentMinutes, double baselineMinutes, AbuseThresholds t
    ) {
        try {
            double recentCost = toDouble(llmCallLogRepository.costSince(recentSince));
            double baselineCost = toDouble(llmCallLogRepository.costSince(baselineSince));
            if (recentCost < t.spendSpikeMinUsd()) {
                return AbuseSignal.of("spend-spike", recentCost, 0.0, t.spendSpikeWeight(),
                    "Recent spend $" + fmt(recentCost) + " below floor $" + fmt(t.spendSpikeMinUsd()));
            }
            // Expected cost in a recent-length window at the baseline rate.
            double expected = baselineCost * (recentMinutes / baselineMinutes);
            double ratio = expected <= 0 ? Double.POSITIVE_INFINITY : recentCost / expected;
            double norm = AbuseScoreCalculator.normalizeSpike(ratio, t.spendSpikeMultiplier());
            String detail = "Recent $" + fmt(recentCost) + " vs expected $" + fmt(expected)
                + " (x" + fmt(Double.isInfinite(ratio) ? 999 : ratio) + " baseline)";
            return AbuseSignal.of("spend-spike", Double.isInfinite(ratio) ? 999 : ratio, norm, t.spendSpikeWeight(), detail);
        } catch (RuntimeException e) {
            return AbuseSignal.unavailable("spend-spike", t.spendSpikeWeight(), "unavailable: " + e.getClass().getSimpleName());
        }
    }

    private AbuseSignal tokenBurnSignal(Instant recentSince, double recentMinutes, AbuseThresholds t) {
        try {
            long tokens = llmCallLogRepository.tokensSince(recentSince);
            double perMin = tokens / recentMinutes;
            double norm = AbuseScoreCalculator.normalizeRate(perMin, t.tokenBurnPerMinThreshold());
            return AbuseSignal.of("token-burn", perMin, norm, t.tokenBurnWeight(),
                fmt(perMin) + " tokens/min (threshold " + fmt(t.tokenBurnPerMinThreshold()) + ")");
        } catch (RuntimeException e) {
            return AbuseSignal.unavailable("token-burn", t.tokenBurnWeight(), "unavailable: " + e.getClass().getSimpleName());
        }
    }

    private AbuseSignal messageFloodSignal(String userId, Instant recentSince, double recentMinutes, AbuseThresholds t) {
        if (userId == null) {
            return AbuseSignal.unavailable("message-flood", t.messageFloodWeight(), "no user in scope");
        }
        try {
            long count = auditLogRepository.countByActorIdAndCreatedAtAfter(userId, recentSince);
            double perMin = count / recentMinutes;
            double norm = AbuseScoreCalculator.normalizeRate(perMin, t.messageFloodPerMinThreshold());
            return AbuseSignal.of("message-flood", perMin, norm, t.messageFloodWeight(),
                count + " actions in window (" + fmt(perMin) + "/min, threshold " + fmt(t.messageFloodPerMinThreshold()) + ")");
        } catch (RuntimeException e) {
            return AbuseSignal.unavailable("message-flood", t.messageFloodWeight(), "unavailable: " + e.getClass().getSimpleName());
        }
    }

    private AbuseSignal signupVelocitySignal(String userId, Instant now, AbuseThresholds t) {
        if (userId == null) {
            return AbuseSignal.unavailable("signup-velocity", t.signupVelocityWeight(), "no user in scope");
        }
        try {
            Instant hourAgo = now.minus(Duration.ofHours(1));
            long count = auditLogRepository
                .countByActorIdAndActionTypeContainingIgnoreCaseAndCreatedAtAfter(userId, "signup", hourAgo);
            double norm = AbuseScoreCalculator.normalizeRate(count, t.signupVelocityPerHourThreshold());
            return AbuseSignal.of("signup-velocity", count, norm, t.signupVelocityWeight(),
                count + " signup-type actions/hour (threshold " + fmt(t.signupVelocityPerHourThreshold()) + ")");
        } catch (RuntimeException e) {
            return AbuseSignal.unavailable("signup-velocity", t.signupVelocityWeight(), "unavailable: " + e.getClass().getSimpleName());
        }
    }

    private AbuseSignal toolErrorSignal(String userId, Instant recentSince, AbuseThresholds t) {
        if (userId == null) {
            return AbuseSignal.unavailable("tool-error", t.toolErrorWeight(), "no user in scope");
        }
        try {
            long total = auditLogRepository.countByActorIdAndCreatedAtAfter(userId, recentSince);
            if (total < t.toolErrorMinSample()) {
                return AbuseSignal.of("tool-error", 0.0, 0.0, t.toolErrorWeight(),
                    total + " actions (< min sample " + t.toolErrorMinSample() + ")");
            }
            long failures = auditLogRepository
                .countByActorIdAndOutcomeAndCreatedAtAfter(userId, AuditOutcome.FAILURE, recentSince);
            double ratio = (double) failures / total;
            double norm = AbuseScoreCalculator.normalizeRate(ratio, t.toolErrorRatioThreshold());
            return AbuseSignal.of("tool-error", ratio, norm, t.toolErrorWeight(),
                failures + "/" + total + " failures (ratio " + fmt(ratio) + ", threshold " + fmt(t.toolErrorRatioThreshold()) + ")");
        } catch (RuntimeException e) {
            return AbuseSignal.unavailable("tool-error", t.toolErrorWeight(), "unavailable: " + e.getClass().getSimpleName());
        }
    }

    private AbuseSignal geoVelocitySignal(UUID tenantId, String userId, AbuseThresholds t) {
        GeoVelocityProvider provider = geoVelocityProvider.getIfAvailable();
        if (provider == null || userId == null) {
            return AbuseSignal.unavailable("geo-velocity", t.geoVelocityWeight(),
                provider == null ? "no geo provider wired" : "no user in scope");
        }
        try {
            Optional<Double> impossible = provider.impossibleTravelScore(tenantId, userId, t.recentWindowMinutes());
            if (impossible.isEmpty()) {
                return AbuseSignal.unavailable("geo-velocity", t.geoVelocityWeight(), "insufficient location data");
            }
            double v = impossible.get();
            return AbuseSignal.of("geo-velocity", v, v, t.geoVelocityWeight(), "impossible-travel score " + fmt(v));
        } catch (RuntimeException e) {
            return AbuseSignal.unavailable("geo-velocity", t.geoVelocityWeight(), "unavailable: " + e.getClass().getSimpleName());
        }
    }

    // ---- Actions (reversible + audited) -----------------------------------

    /**
     * Applies the tier's action inside {@code tenant}'s schema context - the
     * fingerprinted issue and audit rows are tenant-scoped tables, so they must
     * be written under that schema; the SUSPEND path's {@code stopTenant} hits
     * the control-plane {@code Tenant} registry and is context-independent.
     */
    private void applyTier(Tenant tenant, AbuseRiskScore score, AbuseThresholds t) {
        UUID tenantId = tenant.getId();
        String userId = score.userId();
        String principal = AbuseThrottleService.principalKey(tenantId.toString(), userId);
        try {
            TenantContext.set(TenantContext.of(tenant.getSchemaName(), SYSTEM_ACTOR, Set.of()));
            switch (score.tier()) {
                case NORMAL -> { /* nothing */ }
                case WATCH -> {
                    recordIssue(IssueSeverity.WARNING, principal, "Abuse WATCH: risk " + score.score(), summarize(score));
                    audit("abuse.watch", principal, AuditOutcome.SUCCESS, summarize(score));
                    fanOutAlert(score, false, "WATCH");
                }
                case THROTTLE -> {
                    recordIssue(IssueSeverity.WARNING, principal, "Abuse THROTTLE: risk " + score.score(), summarize(score));
                    throttleService.throttle(tenantId.toString(), userId,
                        "Risk score " + score.score(), Duration.ofMinutes(t.throttleTtlMinutes()), SYSTEM_ACTOR);
                    audit("abuse.throttle", principal, AuditOutcome.SUCCESS, summarize(score));
                    fanOutAlert(score, true, "THROTTLE");
                }
                case SUSPEND -> applySuspend(tenantId, userId, principal, score, t);
            }
        } finally {
            TenantContext.clear();
        }
    }

    private void applySuspend(UUID tenantId, String userId, String principal, AbuseRiskScore score, AbuseThresholds t) {
        recordIssue(IssueSeverity.ERROR, principal, "Abuse SUSPEND: risk " + score.score(), summarize(score));
        // Always take the strongest reversible per-principal action available.
        throttleService.throttle(tenantId.toString(), userId,
            "SUSPEND-tier risk " + score.score(), Duration.ofMinutes(t.throttleTtlMinutes()), SYSTEM_ACTOR);
        if (t.autoSuspendEnabled()) {
            try {
                emergencyControlService.stopTenant(tenantId,
                    "Automated abuse suspension - principal " + principal + " risk " + score.score(), SYSTEM_ACTOR);
                audit("abuse.suspend", principal, AuditOutcome.SUCCESS,
                    "Tenant emergency-stopped (auto-suspend enabled). " + summarize(score));
                fanOutAlert(score, true, "SUSPEND");
            } catch (RuntimeException e) {
                log.warn("Auto-suspend of tenant {} failed (throttle still applied)", tenantId, e);
                audit("abuse.suspend", principal, AuditOutcome.FAILURE,
                    "Auto-suspend failed: " + e.getClass().getSimpleName() + ". Throttle applied instead.");
                fanOutAlert(score, true, "SUSPEND (auto-suspend failed - throttled)");
            }
        } else {
            // Safe default: don't auto-nuke a whole tenant off one principal - raise ERROR + throttle for human review.
            audit("abuse.suspend.deferred", principal, AuditOutcome.PENDING_APPROVAL,
                "Auto-suspend disabled - throttled + ERROR issue raised for operator review. " + summarize(score));
            fanOutAlert(score, true, "SUSPEND (deferred to operator - throttled)");
        }
    }

    private void recordIssue(IssueSeverity severity, String principal, String title, String message) {
        try {
            platformIssueService.record(IssueSource.ABUSE_ANOMALY, principal, severity, title, message);
        } catch (RuntimeException e) {
            log.warn("Failed to record ABUSE_ANOMALY issue for {} (swallowed)", principal, e);
        }
    }

    private void audit(String action, String principal, AuditOutcome outcome, String detail) {
        try {
            auditLogService.record(SYSTEM_ACTOR, action, TARGET_TYPE, principal, outcome, detail);
        } catch (RuntimeException e) {
            log.warn("Failed to audit {} for {} (swallowed)", action, principal, e);
        }
    }

    private void fanOutAlert(AbuseRiskScore score, boolean actionApplied, String label) {
        AbuseAlert alert = new AbuseAlert(score.tenantId(), score.tenantName(), score.userId(),
            score.score(), score.tier(), actionApplied, label + " - " + summarize(score), Instant.now());
        for (AbuseAlertSink sink : alertSinks) {
            try {
                sink.send(alert);
            } catch (RuntimeException e) {
                log.warn("Alert sink {} failed (swallowed)", sink.getClass().getSimpleName(), e);
            }
        }
    }

    private String summarize(AbuseRiskScore score) {
        StringBuilder sb = new StringBuilder("score=").append(score.score()).append(" [");
        boolean first = true;
        for (AbuseSignal s : score.signals()) {
            if (!s.available() || s.normalizedScore() <= 0) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(s.name()).append("=").append(fmt(s.normalizedScore()));
            first = false;
        }
        return sb.append("]").toString();
    }

    private List<Tenant> activeTenants() {
        return tenantRepository.findByStatus(TenantStatus.ACTIVE);
    }

    private static double toDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
