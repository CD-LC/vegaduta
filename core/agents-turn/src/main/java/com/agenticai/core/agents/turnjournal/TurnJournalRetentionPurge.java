package com.agenticai.core.agents.turnjournal;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention sweep of {@code turn_journal_event}: with {@code app.turn-journal.enabled=true} every chat turn
 * writes several rows that nothing else ever deleted. This deletes WHOLE turns whose newest event is older than
 * {@code app.turn-journal.retention-days}, in every tenant schema, in bounded batches.
 *
 * <p>Inert while the journal is off and when {@code retention-days} is 0 (keep for ever). Same shape as the other
 * scheduled tenant sweeps: a Postgres advisory lock so one replica works per tick (a convenience, the delete is
 * idempotent), the tenant iterator because a {@code @Scheduled} thread has no tenant context, a per-schema
 * try/catch.
 */
@Component
public class TurnJournalRetentionPurge {

    private static final Logger log = LoggerFactory.getLogger(TurnJournalRetentionPurge.class);
    static final String LOCK_NAME = "turn-journal-retention-purge";

    private final TurnJournal journal;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final Clock clock;
    private final java.util.function.BooleanSupplier enabled;
    private final int retentionDays;
    private final int batchTurns;
    private final int maxBatches;

    @Autowired
    public TurnJournalRetentionPurge(
            TurnJournal journal,
            TenantSchemaIterator schemaIterator,
            PgAdvisoryJobLock jobLock,
            @Value("${app.turn-journal.retention-days:90}") int retentionDays,
            @Value("${app.turn-journal.purge-batch-turns:500}") int batchTurns,
            @Value("${app.turn-journal.purge-max-batches:40}") int maxBatches) {
        this(
                journal,
                schemaIterator,
                jobLock,
                Clock.systemUTC(),
                TurnJournalGateway::enabled,
                retentionDays,
                batchTurns,
                maxBatches);
    }

    TurnJournalRetentionPurge(
            TurnJournal journal,
            TenantSchemaIterator schemaIterator,
            PgAdvisoryJobLock jobLock,
            Clock clock,
            java.util.function.BooleanSupplier enabled,
            int retentionDays,
            int batchTurns,
            int maxBatches) {
        this.journal = journal;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.clock = clock;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.batchTurns = Math.max(1, batchTurns);
        this.maxBatches = Math.max(1, maxBatches);
    }

    @Scheduled(
            fixedDelayString = "${app.turn-journal.purge-interval:6h}",
            initialDelayString = "${app.turn-journal.purge-interval:6h}")
    public void purge() {
        if (!enabled.getAsBoolean() || retentionDays <= 0) {
            return;
        }
        jobLock.tryRunExclusive(LOCK_NAME, this::purgeExclusively);
    }

    private void purgeExclusively() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        AtomicInteger total = new AtomicInteger();
        schemaIterator.forEachActiveTenantSchema(
                schema -> {
                    try {
                        for (int batch = 0; batch < maxBatches; batch++) {
                            int removed = journal.purgeOlderThan(cutoff, batchTurns);
                            total.addAndGet(removed);
                            if (removed == 0) {
                                break;
                            }
                        }
                    } catch (RuntimeException e) {
                        log.warn("Turn journal purge failed in schema '{}'", schema, e);
                    }
                });
        if (total.get() > 0) {
            log.info(
                    "Turn journal retention purge removed {} event(s) older than {} day(s)",
                    total.get(),
                    retentionDays);
        }
    }
}
