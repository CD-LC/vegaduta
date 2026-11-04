package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.agents.learning.review.MemoryFactRef;
import com.agenticai.core.agents.learning.review.MemoryReviewDetector;
import com.agenticai.core.agents.memory.MemoryTrust;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlanner;
import com.agenticai.core.agents.memorystore.decay.ConsolidationRequest;
import com.agenticai.core.agents.memorystore.decay.DecayProperties;
import com.agenticai.core.agents.memorystore.decay.ItemSnapshot;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The nightly consolidation of one tenant's remembered facts (phase 1 design 03, section 4;
 * ADR-0028 WP5b). Same skeleton as the daily review ({@code MemoryReviewService}): subjects are
 * claimed once per day in {@code memory_consolidation_runs}, all decisions come from the pure
 * {@link ConsolidationPlanner} (no model call, ever - a summarise band is reported as
 * "would summarise" and left for a later package that owns the model step), and one person's
 * failure never stops the rest.
 *
 * <p><b>Dry run first.</b> Every plan is computed and reported; nothing is applied while
 * {@code app.memory.consolidation.apply.enabled} is off (the default) or the plan is still inside
 * the planner's {@code dry-run-days} window (measured from the first recorded run in this tenant)
 * or its circuit breaker tripped. Reported counts land on the run row and in the log.
 *
 * <p><b>Only ever removes, never grants.</b> The apply path's only mutation is a {@code
 * memory_tombstone} insert through {@link MemoryTombstoneWriter}: a hash-only "forgotten" marker.
 * It never deletes a fact row, never rewrites one, and cannot set one TRUSTED or ACTIVE - the
 * facts it reads come through {@link ConsolidationFactSource}, a read-only port, so the
 * {@code approve}/{@code forget}/{@code update} methods of the memory services are unreachable
 * from here (enforced by {@code ConsolidationSurfaceTest}).
 *
 * <p>Needs the tenant context on the calling thread (the sweep sets it via {@code
 * TenantSchemaIterator}).
 */
@Service
@ConditionalOnProperty(name = "app.memory.consolidation.enabled", havingValue = "true")
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);

    /** Kind label given to fact-store rows for the per-kind half-life lookup. */
    static final String FACT_KIND = "fact";

    private final ConsolidationFactSource facts;
    private final MemoryConsolidationRunRepository runs;
    private final MemoryTombstoneWriter tombstones;
    private final DecayProperties decay;
    private final boolean applyEnabled;
    private final int maxSubjects;

    private volatile java.time.Clock clock = java.time.Clock.systemUTC();

    /** The memory clock ({@code memoryClock} bean, system UTC by default); tests pin it to control the day and ages. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useClock(
            @org.springframework.beans.factory.annotation.Qualifier("memoryClock") java.time.Clock clock) {
        this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
    }

    public MemoryConsolidationService(
            ConsolidationFactSource facts,
            MemoryConsolidationRunRepository runs,
            MemoryTombstoneWriter tombstones,
            ObjectProvider<DecayProperties> decay,
            @Value("${app.memory.consolidation.apply.enabled:false}") boolean applyEnabled,
            @Value("${app.memory.consolidation.max-subjects-per-tick:200}") int maxSubjects) {
        this.facts = facts;
        this.runs = runs;
        this.tombstones = tombstones;
        this.decay = decay.getIfAvailable(DecayProperties::new);
        this.applyEnabled = applyEnabled;
        this.maxSubjects = Math.max(1, maxSubjects);
    }

    /** Totals of one tenant's tick. */
    public record Report(int subjects, int rows, int forgets, int merges, int summaries, int tombstoned) {
        Report plus(Report o) {
            return new Report(
                    subjects + o.subjects,
                    rows + o.rows,
                    forgets + o.forgets,
                    merges + o.merges,
                    summaries + o.summaries,
                    tombstoned + o.tombstoned);
        }
    }

    /** Consolidates every subject in the current tenant not already claimed today. */
    public Report consolidateTenant() {
        LocalDate day = today();
        runs.deleteOlderThan(day.minusDays(30));
        Report total = new Report(0, 0, 0, 0, 0, 0);
        int seen = 0;
        for (String subject : facts.subjects(maxSubjects)) {
            if (seen++ >= maxSubjects) {
                break;
            }
            try {
                Report r = consolidateSubject(subject, day);
                total = r == null ? total : total.plus(r);
            } catch (RuntimeException e) {
                // One person's failure must not stop the rest of the tenant.
                log.warn("Nightly memory consolidation failed for one person: {}", e.getMessage());
            }
        }
        return total;
    }

    /** Consolidates one person unless already done today; null when the day was already claimed. */
    Report consolidateSubject(String subject, LocalDate day) {
        if (subject == null || subject.isBlank() || runs.claim(subject, day) == 0) {
            return null;
        }
        try {
            Report r = run(subject, day);
            return r;
        } catch (RuntimeException e) {
            runs.release(subject, day);
            throw e;
        }
    }

    private Report run(String subject, LocalDate day) {
        List<ConsolidationFactSource.Fact> facts = this.facts.facts(subject);
        Map<UUID, ConsolidationFactSource.Fact> byId = new HashMap<>();
        List<ItemSnapshot> snapshots = new ArrayList<>(facts.size());
        for (ConsolidationFactSource.Fact f : facts) {
            byId.put(f.id(), f);
            snapshots.add(toSnapshot(f));
        }
        ConsolidationPlan plan =
                new ConsolidationPlanner(decay, clock)
                        .plan(new ConsolidationRequest(subject, snapshots, duplicateGroups(facts), enabledSince()));
        int forgets = 0;
        int merges = 0;
        int summaries = 0;
        int tombstoned = 0;
        for (ConsolidationPlan.Batch b : plan.batches()) {
            forgets += b.forgets().size();
            merges += b.merges().stream().mapToInt(m -> m.dropIds().size()).sum();
            summaries += b.summaries().size();
        }
        String mode = "dry_run";
        String status = "completed";
        if (applyEnabled && plan.mayAutoApply()) {
            mode = "apply";
            Instant now = clock.instant();
            for (ConsolidationPlan.Batch b : plan.batches()) {
                for (ConsolidationPlan.Forget f : b.forgets()) {
                    tombstoned += tombstone(subject, byId.get(f.id()), f.reason().name(), f.score(), now);
                }
                for (ConsolidationPlan.Merge m : b.merges()) {
                    for (UUID drop : m.dropIds()) {
                        tombstoned += tombstone(
                                subject,
                                byId.get(drop),
                                MemoryTombstoneWriter.REASON_MERGED_INTO_PREFIX + m.keepId(),
                                null,
                                now);
                    }
                }
                // Summaries are never applied here: folding rows into a summary is the only step
                // that needs a model, and this job makes none. They are reported above and
                // re-planned nightly until a later package owns the model step.
            }
        }
        runs.record(subject, day, mode, status, snapshots.size(), forgets, merges, summaries, plan.deferred(), tombstoned);
        return new Report(1, snapshots.size(), forgets, merges, summaries, tombstoned);
    }

    private int tombstone(String subject, ConsolidationFactSource.Fact fact, String reason, Double score, Instant now) {
        if (fact == null) {
            return 0;
        }
        try {
            tombstones.markForgotten(subject, fact, reason, score, now);
            return 1;
        } catch (RuntimeException e) {
            log.warn("Could not tombstone fact {} for one person: {}", fact.id(), e.getMessage());
            return 0;
        }
    }

    /**
     * Near-duplicate id sets for the planner, from the same rule-based detector the daily review
     * uses (word overlap, no model). Only DUPLICATE findings become merge groups; a group is the
     * kept fact plus its drops. The detector only compares inside one tier, so a group never spans
     * tiers. QUARANTINED rows are excluded here as in the review: a quarantined text is never a
     * merge input, so it cannot launder into a TRUSTED keeper.
     */
    private List<Set<UUID>> duplicateGroups(List<ConsolidationFactSource.Fact> facts) {
        List<MemoryFactRef> refs = new ArrayList<>();
        for (ConsolidationFactSource.Fact f : facts) {
            if (f.trust() != MemoryTrust.TRUSTED) {
                continue;
            }
            refs.add(new MemoryFactRef(
                    f.scope(), f.id(), f.agentId(), f.content(), f.createdAt(), later(f.createdAt(), f.lastUsedAt())));
        }
        List<Set<UUID>> groups = new ArrayList<>();
        for (MemoryReviewDetector.Finding f : MemoryReviewDetector.detect(
                refs, clock.instant(), MemoryReviewDetector.Settings.defaults())) {
            if (f.type() == MemoryReviewDetector.Type.DUPLICATE && f.keep() != null) {
                Set<UUID> g = new HashSet<>();
                g.add(f.keep().id());
                f.dropped().forEach(d -> g.add(d.id()));
                groups.add(g);
            }
        }
        return groups;
    }

    /**
     * The fact stores carry no {@code pinned}, {@code personAuthored}, {@code importance},
     * {@code generation} or lineage columns: pinned/personAuthored stay false (the conservative
     * default for a dry run is the planner's own min-age and thresholds; the columns arrive with a
     * later migration), importance is the design's default 0.5, generation 0. Every stored row is a
     * live (ACTIVE) candidate; trust maps fail-closed onto {@link MemoryTrustLevel}.
     */
    private static ItemSnapshot toSnapshot(ConsolidationFactSource.Fact f) {
        return new ItemSnapshot(
                f.id(),
                f.agentId(),
                FACT_KIND,
                f.trust() == MemoryTrust.TRUSTED ? MemoryTrustLevel.TRUSTED : MemoryTrustLevel.QUARANTINED,
                ItemSnapshot.Status.ACTIVE,
                false,
                false,
                0.5,
                f.useCount(),
                f.createdAt(),
                f.lastUsedAt(),
                List.of(),
                0,
                false,
                false);
    }

    /** When consolidation first ran in this tenant; null means "just now", which keeps plans dry-run. */
    private Instant enabledSince() {
        return runs.firstRunDate()
                .map(d -> d.atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElse(null);
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
