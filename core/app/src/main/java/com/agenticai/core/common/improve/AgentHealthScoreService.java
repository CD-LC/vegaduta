package com.agenticai.core.common.improve;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueStatus;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Computes and upserts the live, per-agent health/confidence snapshot
 * ({@link AgentHealthScore}) the meta-agent supervisor sweep
 * ({@link AgentSupervisorSweep}) writes every tick.
 *
 * <p>Deliberately reuses {@link AgentImprovementService}'s own clustering
 * read (same {@code AGENT_KEYED_SOURCES}, same OPEN-{@link PlatformIssue}
 * source) rather than inventing a second notion of "what counts as trouble
 * for this agent" - this class is a read-only score derived from the exact
 * same evidence the suggestion loop already distills, not a parallel
 * detector.
 *
 * <h2>Score formula (0-100, higher is healthier)</h2>
 * Start at 100 and subtract:
 * <ul>
 *   <li>{@value #ERROR_PENALTY} points per ERROR-severity open issue
 *       cluster keyed to this agent (capped at {@value #OCCURRENCE_CAP}
 *       occurrences per cluster, so one endlessly-recurring cluster cannot
 *       alone floor the score),</li>
 *   <li>{@value #WARNING_PENALTY} points per WARNING-severity one (same
 *       cap),</li>
 *   <li>{@value #PENDING_SUGGESTION_PENALTY} points per PENDING
 *       improvement suggestion still awaiting an admin decision - a queue
 *       of unreviewed fixes is itself a confidence signal.</li>
 * </ul>
 * Clamped to [0, 100]. This is intentionally simple and legible - an admin
 * reading {@link AgentHealthScore#getOpenIssueCount()} /
 * {@link AgentHealthScore#getPendingSuggestionCount()} next to the score can
 * reconstruct roughly how it was reached without a model call, unlike the
 * LLM-distilled suggestions themselves.
 */
@Service
public class AgentHealthScoreService {

    static final int HEALTHY_THRESHOLD = 80;
    static final int AT_RISK_THRESHOLD = 50;

    private static final int ERROR_PENALTY = 6;
    private static final int WARNING_PENALTY = 2;
    private static final int PENDING_SUGGESTION_PENALTY = 5;
    private static final int OCCURRENCE_CAP = 5;

    private final AgentHealthScoreRepository healthScoreRepository;
    private final PlatformIssueRepository issueRepository;
    private final AgentImprovementSuggestionRepository suggestionRepository;

    public AgentHealthScoreService(
        AgentHealthScoreRepository healthScoreRepository,
        PlatformIssueRepository issueRepository,
        AgentImprovementSuggestionRepository suggestionRepository
    ) {
        this.healthScoreRepository = healthScoreRepository;
        this.issueRepository = issueRepository;
        this.suggestionRepository = suggestionRepository;
    }

    public Optional<AgentHealthScore> getForAgent(UUID agentId) {
        return healthScoreRepository.findByAgentId(agentId);
    }

    /**
     * Recomputes and upserts this agent's health snapshot from its current
     * OPEN issue clusters and PENDING suggestions. Cheap and idempotent -
     * safe to call once per agent per sweep tick, and safe to call again on
     * demand (e.g. right after {@code AgentImprovementService#generateForAgent}
     * on the same tick, so the two never disagree about "as of when").
     */
    public AgentHealthScore recompute(UUID agentId, String tenantId) {
        String agentKey = agentId.toString();
        List<PlatformIssue> openIssues = issueRepository
            .findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN).stream()
            .filter(i -> i.getSource() != null
                && AgentImprovementService.AGENT_KEYED_SOURCES.contains(i.getSource().name())
                && agentKey.equals(i.getSourceId()))
            .toList();

        int penalty = 0;
        for (PlatformIssue issue : openIssues) {
            int weight = Math.min(issue.getOccurrenceCount(), OCCURRENCE_CAP);
            penalty += (issue.getSeverity() == IssueSeverity.ERROR ? ERROR_PENALTY : WARNING_PENALTY) * weight;
        }

        long pendingSuggestions = suggestionRepository.findTop100ByAgentIdOrderByCreatedAtDesc(agentId).stream()
            .filter(s -> s.getStatus() == ImprovementStatus.PENDING)
            .count();
        penalty += (int) (pendingSuggestions * PENDING_SUGGESTION_PENALTY);

        int score = Math.max(0, Math.min(100, 100 - penalty));
        AgentHealthStatus status = statusFor(score);

        AgentHealthScore snapshot = healthScoreRepository.findByAgentId(agentId)
            .orElseGet(() -> new AgentHealthScore(tenantId, agentId));
        snapshot.update(score, status, openIssues.size(), (int) pendingSuggestions);
        return healthScoreRepository.save(snapshot);
    }

    private static AgentHealthStatus statusFor(int score) {
        if (score >= HEALTHY_THRESHOLD) {
            return AgentHealthStatus.HEALTHY;
        }
        if (score >= AT_RISK_THRESHOLD) {
            return AgentHealthStatus.AT_RISK;
        }
        return AgentHealthStatus.DEGRADED;
    }
}
