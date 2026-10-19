package com.agenticai.core.common.resolution;

import com.agenticai.core.billing.resolution.ResolutionEvidence;
import com.agenticai.core.billing.resolution.ResolutionMeteringService;
import com.agenticai.core.billing.resolution.ResolutionOutcome;
import com.agenticai.core.billing.resolution.ResolutionPricingService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hourly quiet-close sweep that turns agent-handled {@code channel_session}
 * conversations into {@code resolution_event} rows - the detection half of
 * the resolution meter (Tier-4 outcome-based pricing substrate; table +
 * vocabulary in tenant V280, classification policy in
 * {@link ResolutionMeteringService}).
 *
 * <p>Lives in {@code core/app/common} rather than {@code core/omnichannel}
 * for the same reason {@code common.datausage} does: this job reads
 * {@code channel_session} (omnichannel) AND {@code turn_feedback} (rl) AND
 * writes through {@code core/billing} - no single feature module sees all
 * three without closing a dependency cycle ({@code tenancy} already depends
 * on {@code billing}). Raw JDBC for both source tables, not their
 * repositories: the query needs nothing the entities add, and staying off
 * those files keeps this feature out of every other in-flight session's
 * working tree.
 *
 * <p>Classification per idle session (still {@code AGENT}-handled past
 * {@code platform.resolution.idle-close-hours}, default 72h):
 * <ul>
 *   <li>{@code AWAITING_HUMAN}/{@code HUMAN} at close -> ESCALATED /
 *       ESCALATION_OPEN, never billable.</li>
 *   <li>latest {@code turn_feedback} RESOLVED -> RESOLVED_AI /
 *       USER_RESOLVED_SIGNAL, confidence 1.0.</li>
 *   <li>latest feedback UNRESOLVED or THUMBS_DOWN -> UNRESOLVED /
 *       FEEDBACK_NEGATIVE.</li>
 *   <li>otherwise -> RESOLVED_AI / NO_ESCALATION_IDLE, confidence 0.6,
 *       billable only while {@code platform.resolution.bill-idle-close} is
 *       true (the "assumed resolved" posture an outcome-priced plan can
 *       turn off without a deploy).</li>
 * </ul>
 *
 * <p>Exactly-once rides on the dedupe key {@code cs:<session uuid>:<last
 * message epoch-ms>}: a still-idle session reproduces the same key and is
 * skipped by the unique constraint; a customer writing back moves
 * {@code last_message_at}, so the NEXT idle-close is legitimately a new
 * event. The SQL itself excludes sessions whose current key is already
 * recorded, so the bounded 200-row window keeps advancing instead of
 * rescanning the same stuck sessions every hour.
 *
 * <p>Known, documented imprecision (v1): a conversation an operator took
 * over and later {@code releaseToAgent()}'d is indistinguishable from a
 * never-escalated one - release clears the handoff fields by design and the
 * durable record lives in the audit log, which this sweep deliberately does
 * not re-derive. A post-release idle-close therefore reads RESOLVED_AI;
 * honest reporting of human-handled resolutions needs an explicit record at
 * close time ({@code POST /api/tenant/resolutions} or a future hook in
 * {@code OperatorInboxService}).
 *
 * <p>A second v1 imprecision: {@code turn_feedback} is today populated by
 * the web Playground's own session ids, not {@code channel_session}
 * session ids - no channel adapter exposes a resolved/thumbs UX yet, so
 * the {@code LATEST_FEEDBACK_SQL} join is a forward-looking hook that
 * matches only if/when channel surfaces start writing feedback under the
 * channel session id. Until then the practical outcomes of this sweep are
 * ESCALATED (handoff fields) and RESOLVED_AI/NO_ESCALATION_IDLE; explicit
 * customer signals arrive via {@code POST /api/tenant/resolutions}.
 *
 * <p>Same operational shape as {@code LlmUsageRollupScheduler}:
 * {@link TenantSchemaIterator} for per-tenant {@code TenantContext} (the
 * ONLY correct way to reach tenant tables without an HTTP request),
 * {@link PgAdvisoryJobLock} so exactly one replica sweeps, per-tenant
 * failure isolation, and an off-switch ({@code resolution.sweep.enabled} /
 * env {@code RESOLUTION_SWEEP_ENABLED}, default true) matching this
 * codebase's opt-out posture for background jobs.
 */
@Component
public class ChannelSessionResolutionSweep {

    private static final Logger log = LoggerFactory.getLogger(ChannelSessionResolutionSweep.class);

    private static final String JOB_LOCK_NAME = "channel-session-resolution-sweep";
    private static final int BATCH_LIMIT = 200;

    private static final String IDLE_SESSIONS_SQL =
        "SELECT cs.id, cs.channel, cs.session_id, cs.agent_id, cs.handoff_state, cs.last_message_at"
            + " FROM channel_session cs"
            + " WHERE cs.paired AND cs.last_message_at < ?"
            + " AND NOT EXISTS (SELECT 1 FROM resolution_event re"
            + "   WHERE re.dedupe_key = 'cs:' || cs.id::text || ':' ||"
            + "     (extract(epoch FROM cs.last_message_at) * 1000)::bigint::text)"
            + " ORDER BY cs.last_message_at ASC LIMIT " + BATCH_LIMIT;

    private static final String LATEST_FEEDBACK_SQL =
        "SELECT signal FROM turn_feedback WHERE session_id = ? ORDER BY created_at DESC LIMIT 1";

    private record IdleSession(
        UUID id, String channel, String sessionId, UUID agentId, String handoffState, Instant lastMessageAt) {}

    private final JdbcTemplate jdbcTemplate;
    private final TenantSchemaIterator tenantSchemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final ResolutionMeteringService metering;
    private final ResolutionPricingService pricing;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public ChannelSessionResolutionSweep(
        JdbcTemplate jdbcTemplate,
        TenantSchemaIterator tenantSchemaIterator,
        PgAdvisoryJobLock jobLock,
        ResolutionMeteringService metering,
        ResolutionPricingService pricing,
        PlatformTransactionManager transactionManager,
        @Value("${resolution.sweep.enabled:true}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.jobLock = jobLock;
        this.metering = metering;
        this.pricing = pricing;
        this.tx = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 41 * * * *", zone = "UTC")
    public void sweep() {
        if (!enabled) {
            return;
        }
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::sweepExclusively);
    }

    private void sweepExclusively() {
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            try {
                // A transaction per tenant: channel_session / turn_feedback / resolution_event are
                // tenant tables reached with a plain JdbcTemplate, which only runs in the tenant
                // schema when it joins the JPA-bound connection (see ResolutionEventRepository).
                // Self-invocation would bypass @Transactional, hence the template. A failure rolls
                // back the tenant's batch; the dedupe key makes the next hour's retry exactly-once.
                Integer recorded = tx.execute(status -> sweepTenant());
                if (recorded != null && recorded > 0) {
                    log.info("Resolution sweep recorded {} event(s) in tenant schema '{}'", recorded, schema);
                }
            } catch (RuntimeException e) {
                log.error("Resolution sweep failed in tenant schema '{}'", schema, e);
            }
        });
    }

    int sweepTenant() {
        Instant cutoff = Instant.now().minus(pricing.snapshot().idleCloseHours(), ChronoUnit.HOURS);
        List<IdleSession> sessions = jdbcTemplate.query(IDLE_SESSIONS_SQL,
            (rs, n) -> new IdleSession(
                rs.getObject("id", UUID.class),
                rs.getString("channel"),
                rs.getString("session_id"),
                rs.getObject("agent_id", UUID.class),
                rs.getString("handoff_state"),
                rs.getTimestamp("last_message_at").toInstant()),
            Timestamp.from(cutoff));
        int recorded = 0;
        for (IdleSession s : sessions) {
            if (recordSession(s).isPresent()) {
                recorded++;
            }
        }
        return recorded;
    }

    private java.util.Optional<UUID> recordSession(IdleSession s) {
        String dedupeKey = "cs:" + s.id() + ":" + s.lastMessageAt().toEpochMilli();
        ResolutionOutcome outcome;
        ResolutionEvidence evidence;
        BigDecimal confidence;

        if ("AWAITING_HUMAN".equals(s.handoffState()) || "HUMAN".equals(s.handoffState())) {
            outcome = ResolutionOutcome.ESCALATED;
            evidence = ResolutionEvidence.ESCALATION_OPEN;
            confidence = BigDecimal.ONE;
        } else {
            String latestSignal = latestFeedbackSignal(s.sessionId());
            if ("RESOLVED".equals(latestSignal)) {
                outcome = ResolutionOutcome.RESOLVED_AI;
                evidence = ResolutionEvidence.USER_RESOLVED_SIGNAL;
                confidence = BigDecimal.ONE;
            } else if ("UNRESOLVED".equals(latestSignal) || "THUMBS_DOWN".equals(latestSignal)) {
                outcome = ResolutionOutcome.UNRESOLVED;
                evidence = ResolutionEvidence.FEEDBACK_NEGATIVE;
                confidence = BigDecimal.ONE;
            } else {
                outcome = ResolutionOutcome.RESOLVED_AI;
                evidence = ResolutionEvidence.NO_ESCALATION_IDLE;
                confidence = new BigDecimal("0.6");
            }
        }

        return metering.record(new ResolutionMeteringService.RecordRequest(
            "cs:" + s.id(), s.agentId(), s.channel(), outcome, evidence, confidence,
            Instant.now(), dedupeKey, null));
    }

    private String latestFeedbackSignal(String sessionId) {
        List<String> rows = jdbcTemplate.query(LATEST_FEEDBACK_SQL,
            (rs, n) -> rs.getString(1), sessionId);
        return rows.stream().findFirst().orElse(null);
    }

}
