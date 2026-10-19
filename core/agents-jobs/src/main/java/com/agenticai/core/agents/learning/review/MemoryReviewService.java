package com.agenticai.core.agents.learning.review;

import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.learning.review.MemoryReviewDetector.Finding;
import com.agenticai.core.agents.learning.review.MemoryReviewDetector.Settings;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * The daily memory review: reads one person's remembered facts, finds near-duplicates, contradictions and facts
 * nobody has used for a while ({@link MemoryReviewDetector}) and files each as a CONSOLIDATION proposal for the
 * person to approve ({@link MemoryConsolidationApplier} performs it). Nothing is changed here.
 *
 * <p>No model call: similarity is word overlap, and a merged note is the more informative of the notes, which
 * the person can reword before approving. One run per person per day (claimed in {@code learning_review_runs}),
 * at most {@code app.learning.review.max-per-run} proposals a run, plus the proposal store's own pending cap.
 * Runs on the scheduler thread of the sweep, never on a chat turn. Needs the tenant context to be set.
 */
@Service
@ConditionalOnProperty(
        name = {"app.learning.proposals.enabled", "app.learning.review.enabled"},
        havingValue = "true")
public class MemoryReviewService {

    private static final Logger log = LoggerFactory.getLogger(MemoryReviewService.class);
    private static final int MAX_SNIPPET = 70;

    private final LearningProposalService proposals;
    private final LearningReviewRunRepository runs;
    private final ObjectProvider<AgentMemoryService> agentMemory;
    private final ObjectProvider<TenantMemoryService> tenantMemory;
    private final Settings settings;
    private final int maxSubjects;

    public MemoryReviewService(
            LearningProposalService proposals,
            LearningReviewRunRepository runs,
            ObjectProvider<AgentMemoryService> agentMemory,
            ObjectProvider<TenantMemoryService> tenantMemory,
            @Value("${app.learning.review.stale-days:60}") int staleDays,
            @Value("${app.learning.review.max-per-run:5}") int maxPerRun,
            @Value("${app.learning.review.max-people-per-tick:200}") int maxSubjects) {
        this.proposals = proposals;
        this.runs = runs;
        this.agentMemory = agentMemory;
        this.tenantMemory = tenantMemory;
        Settings d = Settings.defaults();
        this.settings =
                new Settings(
                        d.duplicateThreshold(),
                        d.contradictionThreshold(),
                        Math.max(7, staleDays),
                        Math.max(1, maxPerRun),
                        d.maxStale());
        this.maxSubjects = Math.max(1, maxSubjects);
    }

    /** Reviews everyone in the current tenant who has not been reviewed today; returns proposals filed. */
    public int reviewTenant() {
        AgentMemoryService agent = agentMemory.getIfAvailable();
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (agent == null && tenant == null) {
            return 0;
        }
        Set<String> subjects = new LinkedHashSet<>();
        if (agent != null) {
            subjects.addAll(agent.listSubjects(maxSubjects));
        }
        if (tenant != null && subjects.size() < maxSubjects) {
            subjects.addAll(tenant.listFactSubjects(maxSubjects));
        }
        runs.deleteOlderThan(today().minusDays(14));
        int filed = 0;
        int seen = 0;
        for (String subject : subjects) {
            if (seen++ >= maxSubjects) {
                break;
            }
            try {
                filed += reviewSubject(subject);
            } catch (RuntimeException e) {
                // One person's failure must not stop the rest of the tenant.
                log.warn("Daily memory review failed for one person: {}", e.getMessage());
            }
        }
        return filed;
    }

    /** Reviews one person unless they were already reviewed today. Returns the proposals filed. */
    public int reviewSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return 0;
        }
        LocalDate day = today();
        if (runs.claim(subject, day) == 0) {
            return 0;
        }
        int filed = 0;
        try {
            for (Finding f : MemoryReviewDetector.detect(collect(subject), Instant.now(), settings)) {
                Proposal p = toProposal(subject, f);
                if (p != null && proposals.propose(p).created()) {
                    filed++;
                }
            }
        } catch (RuntimeException e) {
            if (filed == 0) {
                runs.release(subject, day);
            }
            throw e;
        }
        runs.recordFiled(subject, day, filed);
        return filed;
    }

    private List<MemoryFactRef> collect(String subject) {
        List<MemoryFactRef> facts = new ArrayList<>();
        AgentMemoryService agent = agentMemory.getIfAvailable();
        if (agent != null) {
            for (AgentMemoryEntry m : agent.listAllForSubject(subject)) {
                facts.add(new MemoryFactRef(
                        MemoryFactRef.AGENT, m.id(), m.agentId(), m.content(), m.createdAt(), later(m.createdAt(), m.lastUsedAt())));
            }
        }
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (tenant != null) {
            for (TenantMemoryEntry m : tenant.listFactsForSubject(subject)) {
                facts.add(new MemoryFactRef(
                        MemoryFactRef.SHARED, m.id(), m.createdByAgentId(), m.content(), m.createdAt(), later(m.createdAt(), m.lastUsedAt())));
            }
        }
        return facts;
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    /** Null when the finding involves text the scrubber would alter: such a note is left for the person to handle. */
    static Proposal toProposal(String subject, Finding f) {
        SkillDraftScrubber scrub = SkillDraftScrubber.basic();
        boolean merge = f.keep() != null;
        if (merge && !scrub.scrub(f.keep().content()).equals(f.keep().content())) {
            return null;
        }
        // The proposal store strips and truncates the body, and an approval without an edit writes that body
        // over the kept fact. A fact the store would change is never offered for merge.
        if (merge
                && (!f.keep().content().equals(f.keep().content().strip())
                        || f.keep().content().length() > LearningProposalService.MAX_BODY_CHARS)) {
            return null;
        }
        MemoryFactRef first = merge ? f.keep() : f.dropped().get(0);
        boolean agentScope = MemoryFactRef.AGENT.equals(first.scope());
        UUID agentId = agentScope ? first.agentId() : null;
        List<UUID> dropIds = f.dropped().stream().map(MemoryFactRef::id).toList();
        ConsolidationPayload payload =
                new ConsolidationPayload(
                        merge ? ConsolidationPayload.MERGE : ConsolidationPayload.DROP,
                        first.scope(),
                        agentId,
                        merge ? f.keep().id() : null,
                        dropIds,
                        hashesOf(f, merge));
        String dropped = '"' + snippet(f.dropped().get(0).content()) + '"' + (dropIds.size() > 1 ? " and " + (dropIds.size() - 1) + " more" : "");
        String title;
        String body;
        switch (f.type()) {
            case DUPLICATE -> {
                title = "Similar notes, forgetting " + dropped;
                body = f.keep().content();
            }
            case CONTRADICTION -> {
                title = "These notes disagree, keeping the newer and forgetting " + dropped;
                body = f.keep().content();
            }
            default -> {
                title = "Not used in a while, forget this note?";
                body = f.dropped().get(0).content();
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("signal", f.type().name().toLowerCase(Locale.ROOT));
        evidence.put("replaced", f.dropped().stream().map(d -> snippet(d.content())).toList());
        return new Proposal(subject, agentId, LearningProposalKind.CONSOLIDATION, title, body, payload.toMap(), evidence);
    }

    private static Map<UUID, String> hashesOf(Finding f, boolean merge) {
        Map<UUID, String> h = new LinkedHashMap<>();
        if (merge) {
            h.put(f.keep().id(), ConsolidationPayload.fingerprint(f.keep().content()));
        }
        f.dropped().forEach(d -> h.put(d.id(), ConsolidationPayload.fingerprint(d.content())));
        return h;
    }

    static String snippet(String s) {
        String one = s.strip().replaceAll("\\s+", " ");
        return one.length() > MAX_SNIPPET ? one.substring(0, MAX_SNIPPET - 3) + "..." : one;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
