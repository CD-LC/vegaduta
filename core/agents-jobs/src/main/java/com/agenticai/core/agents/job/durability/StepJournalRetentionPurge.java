package com.agenticai.core.agents.job.durability;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention purge of {@code agent_step_journal}: every DURABLE tool call, turn and swarm branch
 * writes a row that nothing else ever deletes, so without this the table grows for ever.
 *
 * <p>Inert unless {@code app.agent-jobs.durability.step-journal-enabled=true} (the same flag that
 * makes rows exist at all). Deletes rows older than {@code step-journal-retention-days} (default
 * 14, floored at 1). Safe by construction: a deleted row only ever means "no safe resume point" for
 * a run older than the retention, never a fabricated resume.
 *
 * <p>Same shape as every other scheduled tenant sweep here: the Postgres advisory lock so replicas
 * do not all purge at once (a convenience, not a correctness guarantee: the delete is idempotent),
 * the tenant-schema iterator because a {@code @Scheduled} thread has no {@code TenantContext}, and
 * a per-schema try/catch.
 */
@Component
public class StepJournalRetentionPurge {

    private static final Logger log = LoggerFactory.getLogger(StepJournalRetentionPurge.class);
    private static final String PURGE_LOCK = "agent-step-journal-purge";

    private final StepJournal journal;
    private final AgentJobProperties properties;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final Clock clock;

    @Autowired
    public StepJournalRetentionPurge(
            StepJournal journal,
            AgentJobProperties properties,
            TenantSchemaIterator schemaIterator,
            PgAdvisoryJobLock jobLock) {
        this(journal, properties, schemaIterator, jobLock, Clock.systemUTC());
    }

    StepJournalRetentionPurge(
            StepJournal journal,
            AgentJobProperties properties,
            TenantSchemaIterator schemaIterator,
            PgAdvisoryJobLock jobLock,
            Clock clock) {
        this.journal = journal;
        this.properties = properties;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.agent-jobs.durability.step-journal-purge-interval:6h}",
            initialDelayString = "${app.agent-jobs.durability.step-journal-purge-interval:6h}")
    public void purge() {
        if (!journal.isEnabled() || !properties.getDurability().isStepJournalEnabled()) {
            return;
        }
        jobLock.tryRunExclusive(PURGE_LOCK, this::purgeExclusively);
    }

    private void purgeExclusively() {
        int days = Math.max(1, properties.getDurability().getStepJournalRetentionDays());
        Instant cutoff = clock.instant().minus(java.time.Duration.ofDays(days));
        AtomicInteger total = new AtomicInteger();
        schemaIterator.forEachActiveTenantSchema(
                schema -> {
                    try {
                        total.addAndGet(journal.purgeOlderThan(cutoff));
                    } catch (RuntimeException e) {
                        log.warn("Step journal purge failed in schema '{}'", schema, e);
                    }
                });
        if (total.get() > 0) {
            log.info(
                    "Step journal retention purge removed {} row(s) older than {} day(s)",
                    total.get(),
                    days);
        }
    }
}
