package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.agents.learning.review.MemoryFactRef;
import com.agenticai.core.agents.learning.review.MemoryReviewDetector;
import com.agenticai.core.agents.memory.MemoryTrust;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memorystore.MemoryTombstoneGuard;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlanner;
import com.agenticai.core.agents.memorystore.decay.ConsolidationRequest;
import com.agenticai.core.agents.memorystore.decay.DecayProperties;
import com.agenticai.core.agents.memorystore.decay.FactImportance;
import com.agenticai.core.agents.memorystore.decay.ItemSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The nightly consolidation of one tenant's remembered facts (phase 1 design 03, section 4;
 * ADR-0028 WP5b). Same skeleton as the daily review ({@code MemoryReviewService}): subjects are
 * claimed once per day in {@code memory_consolidation_runs}, all decisions come from the pure
 * {@link ConsolidationPlanner} (no model call, ever - a summarise band is reported as "would
 * summarise" and left for a later package that owns the model step), and one person's failure never
 * stops the rest.
 *
 * <p><b>Two stores.</b> The legacy fact stores ({@code agent_memory}, {@code tenant_memory}) and,
 * since WP5c, the unified store's {@code memory_item} rows (EPISODIC, PROCEDURAL, SELF_MODEL), each
 * agent claimed once a day as {@code item:<agentId>}. Both go through the same planner.
 *
 * <p><b>Real importance.</b> A fact's importance is its stored prior attenuated by evidence of use
 * ({@link FactImportance}), so an unpinned, person-authored-free fact nobody ever recalled can
 * reach the FORGET band; pinned and person-authored rows are never touched, and a fact that is used
 * or recently used keeps its salience. Items carry no recorded usage yet, so they use the stored
 * importance and age only.
 *
 * <p><b>Dry run first.</b> Every plan is computed and reported; nothing is applied while {@code
 * app.memory.consolidation.auto-apply} (alias {@code apply.enabled}) is off (the default) or the
 * plan is still inside the planner's {@code dry-run-days} window (measured from the first recorded
 * run in this tenant) or its circuit breaker tripped. Forgets of TRUSTED facts therefore need an
 * explicit opt-in. Reported counts land on the run row and in the log.
 *
 * <p><b>Proposals, not changes.</b> Merges and summaries over {@code memory_item} rows are always
 * <em>filed</em> (dry run, tripped breaker or apply alike) as PENDING proposals through {@link
 * ConsolidationProposalFiler} and the {@code MemoryProposalSink}, with lineage; a person's approval
 * supersedes the sources (in the review service, which this package cannot reach). Merges and
 * summaries of legacy facts cannot be stored as proposals (the sink does not store SEMANTIC rows):
 * merges are tombstoned only when auto-apply is on, summaries are reported.
 *
 * <p><b>Only ever removes, never grants.</b> The apply path's only mutation is a {@code
 * memory_tombstone} insert through {@link MemoryTombstoneWriter}: a hash-only "forgotten" marker
 * that recall honours (see {@code MemoryTombstoneGuard}). It never deletes, rewrites, approves or
 * activates a row - the facts and items it reads come through the read-only {@link
 * ConsolidationFactSource} and {@link ConsolidationItemSource} ports (enforced by {@code
 * ConsolidationSurfaceTest}).
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

    /** Tombstone scope of a {@code memory_item} row; its subject key is {@code item:<agentId>}. */
    static final String ITEM_SCOPE = "item";

    private final ConsolidationFactSource facts;
    private final ConsolidationItemSource itemSource;
    private final MemoryConsolidationRunRepository runs;
    private final MemoryTombstoneWriter tombstones;
    private final ConsolidationProposalFiler filer;
    private final DecayProperties decay;
    private final boolean applyEnabled;
    private final int maxSubjects;

    private volatile java.time.Clock clock = java.time.Clock.systemUTC();

    /**
     * The memory clock ({@code memoryClock} bean, system UTC by default); tests pin it to control
     * the day and ages.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useClock(
            @org.springframework.beans.factory.annotation.Qualifier("memoryClock")
                    java.time.Clock clock) {
        this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
    }

    @Autowired
    public MemoryConsolidationService(
            ConsolidationFactSource facts,
            ConsolidationItemSource itemSource,
            MemoryConsolidationRunRepository runs,
            MemoryTombstoneWriter tombstones,
            ConsolidationProposalFiler filer,
            ObjectProvider<DecayProperties> decay,
            @Value(
                            "${app.memory.consolidation.auto-apply:${app.memory.consolidation.apply.enabled:false}}")
                    boolean applyEnabled,
            @Value("${app.memory.consolidation.max-subjects-per-tick:200}") int maxSubjects) {
        this.facts = facts;
        this.itemSource = itemSource;
        this.runs = runs;
        this.tombstones = tombstones;
        this.filer = filer;
        this.decay = decay.getIfAvailable(DecayProperties::new);
        this.applyEnabled = applyEnabled;
        this.maxSubjects = Math.max(1, maxSubjects);
    }

    /**
     * Legacy facts only (no unified store, nothing filed): the shape before WP5c; for hand-built
     * instances in tests.
     */
    MemoryConsolidationService(
            ConsolidationFactSource facts,
            MemoryConsolidationRunRepository runs,
            MemoryTombstoneWriter tombstones,
            ObjectProvider<DecayProperties> decay,
            boolean applyEnabled,
            int maxSubjects) {
        this(facts, null, runs, tombstones, null, decay, applyEnabled, maxSubjects);
    }

    /**
     * Totals of one tenant's tick; {@code proposals} is how many merge/summary proposals the store
     * accepted.
     */
    public record Report(
            int subjects,
            int rows,
            int forgets,
            int merges,
            int summaries,
            int tombstoned,
            int proposals) {

        public Report(
                int subjects, int rows, int forgets, int merges, int summaries, int tombstoned) {
            this(subjects, rows, forgets, merges, summaries, tombstoned, 0);
        }

        Report plus(Report o) {
            return new Report(
                    subjects + o.subjects,
                    rows + o.rows,
                    forgets + o.forgets,
                    merges + o.merges,
                    summaries + o.summaries,
                    tombstoned + o.tombstoned,
                    proposals + o.proposals);
        }
    }

    /** Consolidates every subject in the current tenant not already claimed today. */
    public Report consolidateTenant() {
        LocalDate day = today();
        runs.deleteOlderThan(day.minusDays(30));
        Report total = new Report(0, 0, 0, 0, 0, 0, 0);
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
        if (itemSource != null) {
            for (UUID agent : itemSource.agents(maxSubjects)) {
                try {
                    Report r = consolidateAgentItems(agent, day);
                    total = r == null ? total : total.plus(r);
                } catch (RuntimeException e) {
                    log.warn(
                            "Nightly memory consolidation failed for one agent's items: {}",
                            e.getMessage());
                }
            }
        }
        return total;
    }

    /**
     * Consolidates one agent's unified-memory items unless already done today; null when the day
     * was claimed.
     */
    Report consolidateAgentItems(UUID agentId, LocalDate day) {
        String key = ITEM_SCOPE + ":" + agentId;
        if (agentId == null || runs.claim(key, day) == 0) {
            return null;
        }
        try {
            return runItems(agentId, key, day);
        } catch (RuntimeException e) {
            runs.release(key, day);
            throw e;
        }
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
            snapshots.add(toSnapshot(f, clock.instant()));
        }
        ConsolidationPlan plan =
                new ConsolidationPlanner(decay, clock)
                        .plan(
                                new ConsolidationRequest(
                                        subject,
                                        snapshots,
                                        duplicateGroups(facts),
                                        enabledSince()));
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
            Set<UUID> skip = hiddenSurvivors(facts, plan);
            for (ConsolidationPlan.Batch b : plan.batches()) {
                for (ConsolidationPlan.Forget f : b.forgets()) {
                    if (!skip.contains(f.id())) {
                        tombstoned +=
                                tombstone(
                                        subject,
                                        byId.get(f.id()),
                                        f.reason().name(),
                                        f.score(),
                                        now);
                    }
                }
                for (ConsolidationPlan.Merge m : b.merges()) {
                    for (UUID drop : m.dropIds()) {
                        if (!skip.contains(drop)) {
                            tombstoned +=
                                    tombstone(
                                            subject,
                                            byId.get(drop),
                                            MemoryTombstoneWriter.REASON_MERGED_INTO_PREFIX
                                                    + m.keepId(),
                                            null,
                                            now);
                        }
                    }
                }
                // Summaries are never applied here: folding rows into a summary is the only step
                // that needs a model, and this job makes none. They are reported above and
                // re-planned nightly until a later package owns the model step.
            }
        }
        runs.record(
                subject,
                day,
                mode,
                status,
                snapshots.size(),
                forgets,
                merges,
                summaries,
                plan.deferred(),
                tombstoned,
                0);
        return new Report(1, snapshots.size(), forgets, merges, summaries, tombstoned, 0);
    }

    /**
     * One agent's {@code memory_item} rows: plan with the same planner, tombstone forgets only when
     * the plan may auto-apply, and always file merges and summaries as proposals (skipping any
     * source that already feeds a pending proposal, so a night does not file the same one twice).
     */
    private Report runItems(UUID agentId, String key, LocalDate day) {
        List<ConsolidationItemSource.Item> items = itemSource.items(agentId);
        Map<UUID, ConsolidationItemSource.Item> byId = new HashMap<>();
        List<ItemSnapshot> snapshots = new ArrayList<>(items.size());
        for (ConsolidationItemSource.Item item : items) {
            byId.put(item.id(), item);
            snapshots.add(toSnapshot(item));
        }
        ConsolidationPlan plan =
                new ConsolidationPlanner(decay, clock)
                        .plan(
                                new ConsolidationRequest(
                                        key,
                                        snapshots,
                                        itemDuplicateGroups(items),
                                        enabledSince()));
        Set<UUID> pending = itemSource.sourcesOfPendingProposals(agentId);
        int forgets = 0;
        int merges = 0;
        int summaries = 0;
        int tombstoned = 0;
        int proposals = 0;
        boolean apply = applyEnabled && plan.mayAutoApply();
        Instant now = clock.instant();
        Set<UUID> skip =
                apply
                        ? hiddenSurvivors(
                                items.stream().map(MemoryConsolidationService::itemAsFact).toList(),
                                plan)
                        : Set.of();
        for (ConsolidationPlan.Batch b : plan.batches()) {
            forgets += b.forgets().size();
            if (apply) {
                for (ConsolidationPlan.Forget f : b.forgets()) {
                    if (!skip.contains(f.id())) {
                        tombstoned +=
                                tombstone(
                                        key,
                                        itemAsFact(byId.get(f.id())),
                                        f.reason().name(),
                                        f.score(),
                                        now);
                    }
                }
            }
            for (ConsolidationPlan.Merge m : b.merges()) {
                merges += m.dropIds().size();
                if (!touchesPending(pending, m.keepId(), m.dropIds())) {
                    proposals += filer.fileMerge(m, byId);
                }
            }
            for (ConsolidationPlan.Summarise sm : b.summaries()) {
                summaries++;
                if (!touchesPending(pending, null, sm.sourceIds())) {
                    proposals += filer.fileSummary(sm, byId);
                }
            }
        }
        runs.record(
                key,
                day,
                apply ? "apply" : "dry_run",
                "completed",
                snapshots.size(),
                forgets,
                merges,
                summaries,
                plan.deferred(),
                tombstoned,
                proposals);
        return new Report(1, snapshots.size(), forgets, merges, summaries, tombstoned, proposals);
    }

    private static boolean touchesPending(Set<UUID> pending, UUID keep, List<UUID> ids) {
        return (keep != null && pending.contains(keep)) || ids.stream().anyMatch(pending::contains);
    }

    /**
     * Near duplicates among the agent's items, compared inside one kind (a merge never changes an
     * item's kind).
     */
    private List<Set<UUID>> itemDuplicateGroups(List<ConsolidationItemSource.Item> items) {
        Map<String, List<MemoryFactRef>> byKind = new HashMap<>();
        for (ConsolidationItemSource.Item i : items) {
            byKind.computeIfAbsent(i.kind().name(), k -> new ArrayList<>())
                    .add(
                            new MemoryFactRef(
                                    MemoryFactRef.AGENT,
                                    i.id(),
                                    i.agentId(),
                                    i.content(),
                                    i.createdAt(),
                                    later(i.createdAt(), i.lastUsedAt())));
        }
        List<Set<UUID>> groups = new ArrayList<>();
        for (List<MemoryFactRef> refs : byKind.values()) {
            for (MemoryReviewDetector.Finding f :
                    MemoryReviewDetector.detect(
                            refs, clock.instant(), MemoryReviewDetector.Settings.defaults())) {
                if (f.type() == MemoryReviewDetector.Type.DUPLICATE && f.keep() != null) {
                    Set<UUID> g = new HashSet<>();
                    g.add(f.keep().id());
                    f.dropped().forEach(d -> g.add(d.id()));
                    groups.add(g);
                }
            }
        }
        return groups;
    }

    private static ConsolidationFactSource.Fact itemAsFact(ConsolidationItemSource.Item i) {
        return i == null
                ? null
                : new ConsolidationFactSource.Fact(
                        ITEM_SCOPE,
                        i.id(),
                        i.agentId(),
                        i.content(),
                        MemoryTrust.TRUSTED,
                        i.useCount(),
                        i.createdAt(),
                        i.lastUsedAt());
    }

    /**
     * A {@code memory_item} as a planner snapshot. ACTIVE items are all TRUSTED and human-approved.
     * Importance is the stored prior (0 = never assigned = neutral 0.5) with no use-based
     * attenuation, because the unified recall records no usage yet; the recency clock starts at the
     * later of last use and approval.
     */
    private static ItemSnapshot toSnapshot(ConsolidationItemSource.Item i) {
        double importance = i.importance() > 0 ? i.importance() : FactImportance.DEFAULT_PRIOR;
        return new ItemSnapshot(
                i.id(),
                i.agentId(),
                i.kind().name().toLowerCase(Locale.ROOT),
                MemoryTrustLevel.TRUSTED,
                ItemSnapshot.Status.ACTIVE,
                i.pinned(),
                i.personAuthored(),
                importance,
                i.useCount(),
                i.createdAt(),
                later(i.lastUsedAt(), i.approvedAt()),
                List.of(),
                i.generation(),
                i.modelGenerated(),
                i.humanConfirmed());
    }

    /**
     * A tombstone is the hash of a text, and recall hides every row with that hash in the tier. So
     * a row is not tombstoned while another live row of the same tier holds the exact same text and
     * is not itself being removed (an exact-duplicate "merge drop", or a stale copy of a text that
     * is also in recent use): that would hide the survivor. The skipped row stays for a later night
     * (or until its twin goes too).
     */
    private static Set<UUID> hiddenSurvivors(
            List<ConsolidationFactSource.Fact> all, ConsolidationPlan plan) {
        Set<UUID> planned = new HashSet<>();
        for (ConsolidationPlan.Batch b : plan.batches()) {
            b.forgets().forEach(f -> planned.add(f.id()));
            b.merges().forEach(m -> planned.addAll(m.dropIds()));
        }
        Map<String, List<UUID>> byText = new HashMap<>();
        Map<UUID, String> keyOf = new HashMap<>();
        for (ConsolidationFactSource.Fact f : all) {
            String key = f.tier() + "|" + MemoryTombstoneGuard.sha256(f.content());
            keyOf.put(f.id(), key);
            byText.computeIfAbsent(key, k -> new ArrayList<>()).add(f.id());
        }
        Set<UUID> skip = new HashSet<>();
        for (UUID id : planned) {
            String key = keyOf.get(id);
            if (key != null
                    && byText.get(key).stream().anyMatch(other -> !planned.contains(other))) {
                skip.add(id);
            }
        }
        return skip;
    }

    private int tombstone(
            String subject,
            ConsolidationFactSource.Fact fact,
            String reason,
            Double score,
            Instant now) {
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
            refs.add(
                    new MemoryFactRef(
                            f.scope(),
                            f.id(),
                            f.agentId(),
                            f.content(),
                            f.createdAt(),
                            later(f.createdAt(), f.lastUsedAt())));
        }
        List<Set<UUID>> groups = new ArrayList<>();
        for (MemoryReviewDetector.Finding f :
                MemoryReviewDetector.detect(
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
     * A stored fact as a planner snapshot. Pin and authorship are the V684 columns (a pinned or
     * person-authored fact is never touched); importance is the stored prior attenuated by use
     * ({@link FactImportance}), so a fact that was never recalled and sits idle for a few
     * half-lives can fall into the forget band while a used one cannot. Every stored row is a live
     * (ACTIVE) candidate; trust maps fail-closed onto {@link MemoryTrustLevel}. Generation 0: the
     * stores hold no derived rows.
     */
    private ItemSnapshot toSnapshot(ConsolidationFactSource.Fact f, Instant now) {
        double importance =
                FactImportance.effective(
                        f.importance(),
                        f.useCount(),
                        f.createdAt(),
                        f.lastUsedAt(),
                        now,
                        decay.halfLifeFor(FACT_KIND),
                        decay.getFrequencySaturation());
        return new ItemSnapshot(
                f.id(),
                f.agentId(),
                FACT_KIND,
                f.trust() == MemoryTrust.TRUSTED
                        ? MemoryTrustLevel.TRUSTED
                        : MemoryTrustLevel.QUARANTINED,
                ItemSnapshot.Status.ACTIVE,
                f.pinned(),
                f.personAuthored(),
                importance,
                f.useCount(),
                f.createdAt(),
                f.lastUsedAt(),
                List.of(),
                0,
                false,
                false);
    }

    /**
     * When consolidation first ran in this tenant; null means "just now", which keeps plans
     * dry-run.
     */
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
