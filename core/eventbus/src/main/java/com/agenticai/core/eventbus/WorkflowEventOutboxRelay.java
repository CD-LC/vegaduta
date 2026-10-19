package com.agenticai.core.eventbus;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Interactive Workflow Designer PRD, Phase 0 / Section 9 - the background half of the transactional
 * outbox pattern. {@link WorkflowEventPublisher} already knows how to queue an event; this class is
 * what finds unpublished rows and actually puts them on the {@value #TOPIC} Kafka topic, on a
 * schedule, across every tenant schema.
 *
 * <p>Uses {@link TenantSchemaIterator} rather than querying {@link WorkflowEventOutboxRepository}
 * directly - the same reason {@code RetryFailedActionsJob} and {@code KnowledgeIngestionJobRunner}
 * do (see {@link TenantSchemaIterator}'s javadoc): a {@code @Scheduled} method has no {@link
 * com.agenticai.core.security.TenantContext} of its own, and without this, the poll query would
 * silently only ever see the {@code public} schema.
 *
 * <p>5s poll interval - shorter than any existing job in this codebase, since this is the latency
 * floor for every future workflow-run's run-trace UI to show a step as "happened" at all once Phase
 * 2 builds that overlay.
 *
 * <p>Keyed by {@link WorkflowEvent#partitionKey()} ({@code "<tenantId>:<runId>"}), not just {@code
 * runId} alone - see the PRD's Section 9 for why this specific keying scheme was chosen (ordering
 * guaranteed per run, free parallelism across tenants/runs, one topic to operate rather than one
 * per tenant).
 *
 * <p>#913/#914 (Kafka/queue reliability hardening, V81): closes the dead-letter gap this class's
 * own javadoc used to flag as unbuilt. A row is retried up to {@link #MAX_RETRIES} times (its
 * {@code retryCount}/{@code lastError} tracked via {@link
 * WorkflowEventOutbox#recordFailure(String)}); once it hits the cap it is excluded from all future
 * polls by {@link WorkflowEventOutboxRepository#findByPublishedAtIsNullAndRetryCountLessThan(int)}
 * and a single {@link PlatformIssue} is raised via {@link PlatformIssueService} so a
 * permanently-broken row (bad payload, a topic ACL problem, anything that fails on every attempt)
 * becomes an actionable, visible admin issue instead of silently burning a relay cycle every 5s
 * forever.
 *
 * <p>Multi-replica safe since the {@link PgAdvisoryJobLock} wrap: without it, two replicas polling
 * the same outbox rows would both publish them (at-least-once would silently become at-least-twice
 * on every event, every 5s). Skipped ticks are counted on the {@code job_lock_skipped} counter -
 * this module already exports micrometer meters, so the skip signal rides the same registry the
 * outbox gauges above use.
 */
@Component
@Profile("workflow")
@ConditionalOnWorkflowKafka
// B6: the "workflow" profile alone used to start the relay even with
// app.workflow-events.enabled=false
// (the publisher honoured the flag, the relay did not), so a disabled deployment still polled every
// tenant schema and Kafka every 5s. The flag now gates the relay too.
@ConditionalOnProperty(prefix = "app.workflow-events", name = "enabled", havingValue = "true")
public class WorkflowEventOutboxRelay {

    public static final String TOPIC = "workflow.events";

    private static final long FIXED_DELAY_MS = 5_000L;
    private static final long INITIAL_DELAY_MS = 5_000L;
    private static final long ACK_TIMEOUT_SECONDS = 5L;

    /**
     * After this many failed publish attempts a row is dead-lettered: excluded from all future
     * polls and surfaced as a {@link PlatformIssue}. Chosen to comfortably absorb a transient
     * broker blip (each attempt is already spaced 5s apart by the poll interval, so 5 attempts is
     * ~25s of persistent failure) without letting a truly broken row poll forever.
     *
     * <p>Public since #916 (Kafka dashboard wiring) - {@code SystemStatusController} reads this
     * same constant rather than hardcoding a second copy of "5", so the dashboard's queue-vs-DLQ
     * split can never drift out of sync with what this relay actually enforces.
     */
    public static final int MAX_RETRIES = 5;

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventOutboxRelay.class);
    private static final String JOB_LOCK_NAME = "workflow-event-outbox-relay";

    private final TenantSchemaIterator schemaIterator;
    private final WorkflowEventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PlatformIssueService platformIssueService;
    private final PgAdvisoryJobLock jobLock;
    private final Counter jobLockSkippedCounter;
    // Infra audit (2026-07-27) - platform-wide (summed across every active
    // tenant schema) pending/dead-lettered outbox depth, exported as
    // Prometheus gauges. Backing AtomicLongs rather than computing the value
    // live on each scrape: a Gauge's value-supplier can be invoked from
    // Prometheus's own scrape thread at any time, and re-running
    // TenantSchemaIterator + a DB query per tenant per scrape would be far
    // more expensive than reusing the numbers this relay already computes on
    // its own 5s poll cycle (see relayUnpublishedEvents below) - same
    // "compute once, read many" shape SystemStatusController's per-tenant
    // kafkaQueue() reading takes for its own single-tenant version of this
    // same pair of counts.
    private final AtomicLong pendingGaugeValue = new AtomicLong();
    private final AtomicLong deadLetteredGaugeValue = new AtomicLong();

    // B6: the relay's own bounded worker. KafkaProducer.send blocks (metadata wait, up to
    // max.block.ms)
    // when the broker is unreachable; run on the shared @Scheduled pool that parked a scheduler
    // thread per
    // tick and starved every other job (a due workflow Wait node did not wake for ~6 minutes). The
    // scheduled method now only hands the tick to this single daemon thread and returns at once.
    private final Executor relayExecutor;
    private final ExecutorService ownedExecutor;
    private final AtomicBoolean tickInFlight = new AtomicBoolean();

    @Autowired
    public WorkflowEventOutboxRelay(
            TenantSchemaIterator schemaIterator,
            WorkflowEventOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            PlatformIssueService platformIssueService,
            PgAdvisoryJobLock jobLock,
            MeterRegistry meterRegistry) {
        this(
                schemaIterator,
                outboxRepository,
                kafkaTemplate,
                platformIssueService,
                jobLock,
                meterRegistry,
                TenantAwareExecutors.newSingleThreadExecutor("workflow-outbox-relay", true),
                true);
    }

    /** Test seam: supply the executor (e.g. a direct one) the relay ticks run on. */
    WorkflowEventOutboxRelay(
            TenantSchemaIterator schemaIterator,
            WorkflowEventOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            PlatformIssueService platformIssueService,
            PgAdvisoryJobLock jobLock,
            MeterRegistry meterRegistry,
            Executor relayExecutor) {
        this(
                schemaIterator,
                outboxRepository,
                kafkaTemplate,
                platformIssueService,
                jobLock,
                meterRegistry,
                relayExecutor,
                false);
    }

    private WorkflowEventOutboxRelay(
            TenantSchemaIterator schemaIterator,
            WorkflowEventOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            PlatformIssueService platformIssueService,
            PgAdvisoryJobLock jobLock,
            MeterRegistry meterRegistry,
            Executor relayExecutor,
            boolean owned) {
        this.relayExecutor = relayExecutor;
        this.ownedExecutor = owned ? (ExecutorService) relayExecutor : null;
        this.schemaIterator = schemaIterator;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.platformIssueService = platformIssueService;
        this.jobLock = jobLock;
        // Registered as "job_lock_skipped" - Prometheus's naming convention
        // appends "_total" to counters, so it scrapes as
        // job_lock_skipped_total{job="workflow-event-outbox-relay"}.
        this.jobLockSkippedCounter =
                Counter.builder("job_lock_skipped")
                        .tag("job", JOB_LOCK_NAME)
                        .description(
                                "Scheduled ticks skipped because another core replica held the"
                                        + " advisory job lock.")
                        .register(meterRegistry);
        Gauge.builder("workflow_event_outbox_pending", pendingGaugeValue, AtomicLong::get)
                .description(
                        "Workflow event outbox rows awaiting publish, summed across every active"
                                + " tenant schema (retryCount < MAX_RETRIES).")
                .register(meterRegistry);
        Gauge.builder(
                        "workflow_event_outbox_dead_lettered",
                        deadLetteredGaugeValue,
                        AtomicLong::get)
                .description(
                        "Workflow event outbox rows that exhausted MAX_RETRIES publish attempts,"
                                + " summed across every active tenant schema.")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
    public void relayUnpublishedEvents() {
        // One tick at a time: a slow tick (broker down, each send bounded but not instant) must not
        // pile
        // up behind itself, and the shared scheduler thread must never wait on it.
        if (!tickInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            relayExecutor.execute(
                    () -> {
                        try {
                            if (!jobLock.tryRunExclusive(
                                    JOB_LOCK_NAME, this::relayUnpublishedEventsExclusively)) {
                                jobLockSkippedCounter.increment();
                            }
                        } catch (RuntimeException e) {
                            log.error("Workflow outbox relay tick failed", e);
                        } finally {
                            tickInFlight.set(false);
                        }
                    });
        } catch (RuntimeException rejected) {
            tickInFlight.set(false);
            log.error("Workflow outbox relay tick could not be scheduled", rejected);
        }
    }

    @PreDestroy
    void shutdownRelayExecutor() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    private void relayUnpublishedEventsExclusively() {
        // Infra audit (2026-07-27) - accumulated across every tenant schema
        // visited this poll cycle, then published to the gauges once at the
        // end (see fields above). pendingTotal reuses `unpublished.size()`
        // below - the exact same query/predicate SystemStatusController's
        // kafkaQueue() already calls countByPublishedAtIsNullAndRetryCountLessThan
        // for - at zero extra DB cost, since this method already fetches that
        // same list to actually relay it. deadLetteredTotal calls that
        // repository's sibling count method directly (the same one
        // kafkaQueue() calls) - one extra cheap COUNT query per tenant per
        // poll, not a new query shape.
        AtomicLong pendingTotal = new AtomicLong();
        AtomicLong deadLetteredTotal = new AtomicLong();
        schemaIterator.forEachActiveTenantSchema(
                schema -> {
                    List<WorkflowEventOutbox> unpublished =
                            outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(
                                    MAX_RETRIES);
                    pendingTotal.addAndGet(unpublished.size());
                    deadLetteredTotal.addAndGet(
                            outboxRepository.countByPublishedAtIsNullAndRetryCountGreaterThanEqual(
                                    MAX_RETRIES));
                    for (WorkflowEventOutbox row : unpublished) {
                        try {
                            String key = schema + ":" + row.getRunId();
                            // Deliberately blocks on the broker ack (bounded by
                            // ACK_TIMEOUT_SECONDS) before marking this row
                            // published, rather than firing-and-forgetting - the
                            // whole point of the outbox pattern is at-least-once
                            // delivery, which only holds if a row is marked
                            // published strictly after Kafka has actually
                            // acknowledged it. A fire-and-forget version of this
                            // method looked simpler in an earlier draft but marked
                            // rows published before confirming the send succeeded,
                            // silently losing events on any broker hiccup - exactly
                            // the failure mode this whole mechanism exists to
                            // prevent. The tradeoff is throughput (one row's publish
                            // blocks the next within a tenant's batch), acceptable
                            // at Phase 0's event volume and 5s poll interval.
                            kafkaTemplate
                                    .send(TOPIC, key, row.getPayloadJson())
                                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            row.markPublished();
                            outboxRepository.save(row);
                        } catch (TimeoutException
                                | InterruptedException
                                | java.util.concurrent.ExecutionException e) {
                            if (e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                            handleFailure(row, schema, e);
                        } catch (RuntimeException e) {
                            // Same per-row isolation guarantee every other
                            // TenantSchemaIterator-based job in this codebase gives:
                            // one bad row can never stop the rest of this tenant's
                            // batch (or the next tenant's, since the exception never
                            // escapes this lambda).
                            handleFailure(row, schema, e);
                        }
                    }
                });
        pendingGaugeValue.set(pendingTotal.get());
        deadLetteredGaugeValue.set(deadLetteredTotal.get());
    }

    /**
     * Records the failure on the row itself, then either leaves it for the next poll (retryCount
     * still below {@link #MAX_RETRIES}) or dead-letters it: excludes it from future polls (a
     * natural consequence of the poll query above) and raises exactly one {@link PlatformIssue} for
     * it, relying on {@link PlatformIssueService}'s fingerprint-based deduplication so re-running
     * this method never double-reports the same row.
     */
    private void handleFailure(WorkflowEventOutbox row, String schema, Exception e) {
        String errorMessage =
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        row.recordFailure(errorMessage);
        outboxRepository.save(row);
        if (row.getRetryCount() >= MAX_RETRIES) {
            log.error(
                    "Workflow event {} (run {}) for schema '{}' exhausted {} retries -"
                            + " dead-lettering",
                    row.getEventType(),
                    row.getRunId(),
                    schema,
                    MAX_RETRIES,
                    e);
            try {
                platformIssueService.record(
                        IssueSource.EVENT_OUTBOX,
                        row.getId().toString(),
                        IssueSeverity.ERROR,
                        "Workflow event failed to publish after " + MAX_RETRIES + " retries",
                        "Event type "
                                + row.getEventType()
                                + ", run "
                                + row.getRunId()
                                + ", schema '"
                                + schema
                                + "': "
                                + errorMessage);
            } catch (RuntimeException issueRecordingFailure) {
                // Mirrors PlatformIssueService's own "never let an
                // observability write become a second failure" contract -
                // a bug in issue-recording must never re-throw and disrupt
                // this tenant's remaining rows.
                log.error(
                        "Failed to record PlatformIssue for dead-lettered outbox row {}",
                        row.getId(),
                        issueRecordingFailure);
            }
        } else {
            log.error(
                    "Failed to publish workflow event {} (run {}) for schema '{}' - attempt {}/{},"
                            + " will retry next poll",
                    row.getEventType(),
                    row.getRunId(),
                    schema,
                    row.getRetryCount(),
                    MAX_RETRIES,
                    e);
        }
    }
}
