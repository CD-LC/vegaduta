package com.agenticai.core.common.improve;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Live, per-agent health/confidence snapshot the meta-agent supervisor
 * sweep ({@link AgentSupervisorSweep}) writes every tick - the "how is this
 * agent doing right now" counterpart to {@link AgentImprovementSuggestion}'s
 * "here is a specific fix" (Intercom "Fin Operator"-style: an agent that
 * watches a live, customer-facing agent and surfaces tuning).
 *
 * <p>One row per {@code agentId}, upserted in place by
 * {@link AgentHealthScoreService#recompute}: this is a current snapshot, not
 * an append-only history - the auditable trail of what changed and why
 * already lives in {@code platform_issue} (the failure clusters) and
 * {@code agent_improvement_suggestion} (the proposed fixes) this score is
 * derived from.
 *
 * <p><b>Tenant-schema entity</b> (no {@code schema=} on {@code @Table}),
 * same convention as {@link AgentImprovementSuggestion}: lives in the
 * calling tenant's own Postgres schema and dies with it.
 *
 * <p>Persisted via migration V311 ({@code agent_health_score},
 * {@code db/migration/tenant}).
 */
@Entity
@Table(name = "agent_health_score")
public class AgentHealthScore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Column(name = "agent_id", nullable = false, unique = true)
    private UUID agentId;

    @Column(name = "score", nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AgentHealthStatus status;

    @Column(name = "open_issue_count", nullable = false)
    private int openIssueCount;

    @Column(name = "pending_suggestion_count", nullable = false)
    private int pendingSuggestionCount;

    @Column(name = "last_swept_at", nullable = false)
    private Instant lastSweptAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentHealthScore() {
        // JPA
    }

    AgentHealthScore(String tenantId, UUID agentId) {
        this.tenantId = tenantId;
        this.agentId = agentId;
    }

    /** Overwrites this snapshot in place - called once per sweep tick for this agent. */
    void update(int score, AgentHealthStatus status, int openIssueCount, int pendingSuggestionCount) {
        this.score = score;
        this.status = status;
        this.openIssueCount = openIssueCount;
        this.pendingSuggestionCount = pendingSuggestionCount;
        this.lastSweptAt = Instant.now();
        this.updatedAt = this.lastSweptAt;
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getAgentId() { return agentId; }
    public int getScore() { return score; }
    public AgentHealthStatus getStatus() { return status; }
    public int getOpenIssueCount() { return openIssueCount; }
    public int getPendingSuggestionCount() { return pendingSuggestionCount; }
    public Instant getLastSweptAt() { return lastSweptAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
