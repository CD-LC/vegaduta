package com.agenticai.core.platform.auditseal;

import com.agenticai.core.audit.seal.AuditSeal;
import com.agenticai.core.audit.seal.AuditSealService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically seals new audit rows for every active tenant (see {@link AuditSealService}). Same shape as the
 * other tenant sweeps ({@code StepJournalRetentionPurge}, {@code AbuseSignalService}): a Postgres advisory
 * lock so one replica per tick does the work, {@link TenantSchemaIterator} because a {@code @Scheduled}
 * thread has no tenant context, a per-tenant try/catch inside the iterator, and bounded work per tick.
 *
 * <ul>
 *   <li>{@code app.audit.seal.enabled} (default FALSE) - master switch; turn on only once the A2A signing key
 *       is pinned (see {@link AuditSealA2aSigner}).</li>
 *   <li>{@code app.audit.seal.interval} (default 15m) - delay between ticks.</li>
 *   <li>{@code app.audit.seal.max-tick-seconds} (default 600) - time budget of one tick; tenants not reached
 *       are picked up by the next tick. The advisory-lock connection is held for the whole tick, so this
 *       also bounds how long other replicas skip it.</li>
 *   <li>{@code app.audit.seal.max-rows-per-seal} (default 5000) - read by {@link AuditSealService}.</li>
 * </ul>
 *
 * <p>Never holds a long transaction: each seal is its own short transaction inside the service. A tenant with
 * a large backlog (first run, or a long outage) is drained {@value #MAX_SEALS_PER_TENANT_PER_TICK} seals per
 * tick, so one tenant cannot monopolise the sweep. Without a signing key nothing is sealed (an unsigned seal
 * proves little) and a single WARN says so.
 */
@Component
public class AuditSealSweep {

    private static final Logger log = LoggerFactory.getLogger(AuditSealSweep.class);
    static final String LOCK_NAME = "audit-seal-sweep";
    static final int MAX_SEALS_PER_TENANT_PER_TICK = 20;

    private final AuditSealService sealService;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final boolean enabled;
    private final long maxTickSeconds;
    private final Clock clock;
    private final AtomicBoolean warnedNoSigner = new AtomicBoolean(false);

    @Autowired
    public AuditSealSweep(
        AuditSealService sealService,
        TenantSchemaIterator schemaIterator,
        PgAdvisoryJobLock jobLock,
        @Value("${app.audit.seal.enabled:false}") boolean enabled,
        @Value("${app.audit.seal.max-tick-seconds:600}") long maxTickSeconds
    ) {
        this(sealService, schemaIterator, jobLock, enabled, maxTickSeconds, Clock.systemUTC());
    }

    AuditSealSweep(
        AuditSealService sealService,
        TenantSchemaIterator schemaIterator,
        PgAdvisoryJobLock jobLock,
        boolean enabled,
        long maxTickSeconds,
        Clock clock
    ) {
        this.sealService = sealService;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.enabled = enabled;
        this.maxTickSeconds = Math.max(1, maxTickSeconds);
        this.clock = clock;
    }

    @Scheduled(
        fixedDelayString = "${app.audit.seal.interval:15m}",
        initialDelayString = "${app.audit.seal.initial-delay:2m}"
    )
    public void tick() {
        if (!enabled) {
            return;
        }
        jobLock.tryRunExclusive(LOCK_NAME, this::sealAllTenants);
    }

    void sealAllTenants() {
        if (!sealService.signerAvailable()) {
            if (warnedNoSigner.compareAndSet(false, true)) {
                log.warn("Audit sealing is enabled but no PINNED signing key is available (set "
                    + "APP_A2A_SIGNING_PRIVATE_KEY + APP_A2A_SIGNING_PUBLIC_KEY, and keep app.a2a.signing.enabled=true) "
                    + "- no seals are created");
            }
            return;
        }
        Instant now = clock.instant();
        Instant deadline = now.plusSeconds(maxTickSeconds);
        AtomicInteger sealed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        schemaIterator.forEachActiveTenantSchema(schema -> {
            if (!clock.instant().isBefore(deadline)) {
                skipped.incrementAndGet(); // out of time budget: the next tick continues
                return;
            }
            sealed.addAndGet(sealTenant(schema, now));
        });
        if (sealed.get() > 0) {
            log.info("Audit seal sweep created {} seal(s)", sealed.get());
        }
        if (skipped.get() > 0) {
            log.warn("Audit seal sweep ran out of its {} s budget; {} tenant(s) deferred to the next tick",
                maxTickSeconds, skipped.get());
        }
    }

    /** Seals up to {@value #MAX_SEALS_PER_TENANT_PER_TICK} batches for the tenant bound to this thread. */
    int sealTenant(String schema, Instant now) {
        int created = 0;
        for (int i = 0; i < MAX_SEALS_PER_TENANT_PER_TICK; i++) {
            Optional<AuditSeal> seal;
            try {
                seal = sealService.sealNextBatch(schema, now);
            } catch (DataIntegrityViolationException e) {
                // another replica sealed the same range first (unique seq) - success, not an incident
                log.info("Audit seal for schema '{}' was created concurrently elsewhere; skipping this tick", schema);
                return created;
            }
            if (seal.isEmpty()) {
                return created;
            }
            created++;
        }
        return created;
    }
}
