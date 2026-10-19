package com.agenticai.core.agents.version;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.security.startup.BackgroundStartupTask;
import com.agenticai.core.tenancy.TenantMigrationGate;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The agent half of migration V342's backfill (C1 versioning).
 *
 * <p>V342 backfills {@code workflow_version} in pure SQL because a workflow
 * snapshot is verbatim columns off {@code workflow_definition}. An agent
 * snapshot is NOT - {@code agent_version.config_json} is the {@link
 * AgentConfigSnapshotCodec} serialization (~45 fields incl. sorted
 * collections), and hand-writing that JSON in SQL would produce bundles
 * that decode subtly wrong forever. So agents go through the codec, here,
 * once per boot.
 *
 * <p>What it does per active tenant schema: any {@code agent} row with NO
 * {@code agent_version} rows at all gets {@code v1} snapshotted from its
 * current state via {@link AgentVersionSnapshotService#snapshot}, whose
 * auto-promote path also stamps the PRODUCTION label + label event - the
 * live row is definitionally what production has been running, exactly the
 * reasoning V342's SQL uses for agents that already have version rows.
 * Agents that already have versions are untouched (the SQL half labelled
 * their newest row PRODUCTION), so a re-run is a pure no-op - safe to keep
 * as an every-boot runner rather than a one-shot.
 *
 * <p>Ordering: runs AFTER {@code ExistingTenantSchemaMigrationRunner} by
 * waiting on {@link TenantMigrationGate#isMigrated()} - the snapshot write
 * needs V342's {@code bundle_hash} column present, and a tenant still on a
 * pre-V342 schema would just throw. The wait is bounded; if the gate never
 * opens (a worker shape with {@code app.tenancy.migrate-on-boot=false},
 * where the gate starts open anyway, or a genuinely wedged migrator) the
 * loop proceeds and the per-tenant try/catch inside {@link
 * TenantSchemaIterator} keeps one bad schema from blocking the rest -
 * "one bad tenant never blocks the rest", the same posture both the
 * iterator and the migration runner document.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE) // ApplicationRunner ordering: last, after the Flyway migration runner.
public class VersionSnapshotBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VersionSnapshotBackfillRunner.class);

    /**
     * Ceiling on the wait for the migration gate. Longer than the gate's own
     * readiness safety valve (60 s) because a correct boot matters more than
     * a fast one here; on expiry the per-tenant guard still isolates a
     * not-yet-migrated schema.
     */
    private static final Duration MIGRATION_WAIT_MAX = Duration.ofMinutes(5);

    private final TenantSchemaIterator tenants;
    private final TenantMigrationGate migrationGate;
    private final AgentRepository agentRepository;
    private final AgentVersionRepository versionRepository;
    private final AgentVersionSnapshotService snapshotService;

    public VersionSnapshotBackfillRunner(
        TenantSchemaIterator tenants,
        TenantMigrationGate migrationGate,
        AgentRepository agentRepository,
        AgentVersionRepository versionRepository,
        AgentVersionSnapshotService snapshotService
    ) {
        this.tenants = tenants;
        this.migrationGate = migrationGate;
        this.agentRepository = agentRepository;
        this.versionRepository = versionRepository;
        this.snapshotService = snapshotService;
    }

    /**
     * Cold-start lane: this runner executes on the main thread on EVERY boot, before the
     * {@code ApplicationReadyEvent} and therefore before readiness, and does a {@code findAll()} of every
     * agent (with its eager collections) in every tenant schema even though a re-run is a pure no-op once
     * each agent has a version. With {@code app.startup.background-warmup.enabled=true} it runs on a
     * background daemon thread instead, so a deploy no longer waits for it. Safe because nothing needed to
     * serve a request depends on v1 snapshots existing yet, and a snapshot written concurrently by a user's
     * edit is caught by the per-agent guard in {@link #backfillSchema}. Field-injected so the constructor is
     * unchanged; false (the default) keeps today's synchronous behaviour exactly.
     */
    @Value("${app.startup.background-warmup.enabled:false}")
    private boolean backgroundWarmup;

    @Override
    public void run(ApplicationArguments args) {
        BackgroundStartupTask.run(backgroundWarmup, "agent-version-backfill", this::backfillAllTenants);
    }

    /** The original body of {@link #run}, plus one summary line so the cost of this boot step is visible in the log. */
    void backfillAllTenants() {
        long started = System.nanoTime();
        waitForMigrations();
        AtomicInteger schemas = new AtomicInteger();
        tenants.forEachActiveTenantSchema(schema -> {
            schemas.incrementAndGet();
            backfillSchema(schema);
        });
        log.info("Agent version backfill checked {} tenant schema(s) in {} ms", schemas.get(),
            (System.nanoTime() - started) / 1_000_000L);
    }

    private void waitForMigrations() {
        Instant deadline = Instant.now().plus(MIGRATION_WAIT_MAX);
        while (!migrationGate.isMigrated() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!migrationGate.isMigrated()) {
            log.warn("Tenant migrations still running after {} - starting the version backfill anyway; "
                + "unmigrated schemas will fail per-tenant and retry next boot", MIGRATION_WAIT_MAX);
        }
    }

    private void backfillSchema(String schema) {
        int created = 0;
        for (Agent agent : agentRepository.findAll()) {
            try {
                if (versionRepository.findFirstByAgentIdOrderByVersionNumberDesc(agent.getId()).isPresent()) {
                    continue; // already versioned - V342's SQL half labelled its newest row PRODUCTION
                }
                // "v1 - snapshot of pre-versioning state" wording matches V342's
                // own workflow backfill so both kinds read identically in history.
                snapshotService.snapshot(agent, "v1 - snapshot of pre-versioning state");
                created++;
            } catch (RuntimeException e) {
                // snapshot() itself is fail-open; this catch is belt-and-braces for the
                // repository read - one corrupt agent row must not skip its schema-mates.
                log.warn("Version backfill failed for agent {} in schema {}", agent.getId(), schema, e);
            }
        }
        if (created > 0) {
            log.info("Version backfill: wrote v1 snapshots for {} agent(s) in schema {}", created, schema);
        }
    }
}
