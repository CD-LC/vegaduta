package com.agenticai.core.quality;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A record of evaluation scores for one agent response/turn.
 *
 * Stores the individual eval scores (helpfulness, accuracy, tone, etc.)
 * and an aggregated overall quality score for this turn.
 *
 * This is an append-only log: evaluation records are never updated or
 * deleted, only inserted. This allows historical analysis and trend tracking.
 *
 * <h2>Control-plane placement (fixed 2026-08-26 - see V218's header)</h2>
 * The table is genuinely control-plane by design (V152/V186 both put its DDL
 * in {@code db/migration/controlplane/}), but until now this entity had NO
 * {@code schema} on its {@code @Table} - unlike every other real
 * control-plane entity in this codebase (e.g. {@code PlatformKillSwitch},
 * {@code FeatureFlag}, {@code EvalDataset}'s sibling {@code
 * AgentDefinitionLookup}), which all carry {@code
 * schema = "control_plane"}. Hibernate therefore emitted unqualified {@code
 * agent_quality_record} SQL, which resolves against whatever schema is on
 * the JDBC connection's {@code search_path} - the ACTIVE TENANT's schema for
 * every real request ({@link com.agenticai.core.tenancy.SchemaMultiTenantConnectionProvider}
 * calls {@code Connection.setSchema(tenantIdentifier)} per request, which is
 * a single-schema {@code SET search_path}, no {@code public} fallback). Since
 * V152's unqualified DDL actually created the physical table in {@code
 * public} (Flyway's default connection schema - see V218's header, and
 * {@code db/migration/tenant/V213__agent_eval_dataset.sql}'s header, which
 * already diagnosed this same fact), every one of {@code
 * QualityController}'s four endpoints threw {@code relation
 * "agent_quality_record" does not exist} (Postgres 42P01) for any real,
 * tenant-scoped request - reachable today regardless of whether {@code
 * QualityEvaluationService#evaluateResponseAsync} has callers, because the
 * GET endpoints query the repository directly. The explicit {@code schema}
 * below (plus matching {@code @CollectionTable} schemas on the two
 * element-collection maps, which previously inherited no schema either) make
 * Hibernate always emit {@code control_plane.agent_quality_record}-qualified
 * SQL, immune to whatever tenant schema happens to be active on the calling
 * thread - the same fix shape {@code SubscriptionChargedHandler} uses at the
 * JdbcTemplate level with its explicit {@code control_plane.} prefix, just
 * applied at the JPA-entity level to match this table's own persistence
 * mechanism. V218 physically relocates the (guaranteed-empty, per V186's own
 * comment) orphaned tables from {@code public} to {@code control_plane} to
 * match.
 */
@Entity
@Table(name = "agent_quality_record", schema = "control_plane")
public class AgentQualityRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    /**
     * Overall quality score (0-1), aggregated from individual eval scores.
     * Computed as the weighted average of enabled evaluations.
     */
    @Column(name = "overall_score", nullable = false)
    private double overallScore;

    /**
     * Individual evaluation scores by type.
     * Map from EvaluationType.name() to score (0-1).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "agent_quality_record_scores",
        schema = "control_plane",
        joinColumns = @JoinColumn(name = "agent_quality_record_id")
    )
    @Column(name = "score")
    private Map<String, Double> scores = new HashMap<>();

    /**
     * Individual evaluation feedback by type.
     * Map from EvaluationType.name() to feedback string.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "agent_quality_record_feedback",
        schema = "control_plane",
        joinColumns = @JoinColumn(name = "agent_quality_record_id")
    )
    @Column(name = "feedback", columnDefinition = "TEXT")
    private Map<String, String> feedback = new HashMap<>();

    /**
     * The user's message that was evaluated (for context).
     */
    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    /**
     * The agent's response that was evaluated (for context).
     */
    @Column(name = "agent_response", columnDefinition = "TEXT")
    private String agentResponse;

    /**
     * Token counts from this turn.
     */
    @Column(name = "input_tokens")
    private long inputTokens;

    @Column(name = "output_tokens")
    private long outputTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentQualityRecord() {
        // JPA
    }

    public AgentQualityRecord(
        UUID agentId,
        int turnNumber,
        double overallScore,
        Map<String, Double> scores,
        Map<String, String> feedback,
        String userMessage,
        String agentResponse,
        long inputTokens,
        long outputTokens
    ) {
        this.agentId = agentId;
        this.turnNumber = turnNumber;
        this.overallScore = overallScore;
        this.scores = scores;
        this.feedback = feedback;
        this.userMessage = userMessage;
        this.agentResponse = agentResponse;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.createdAt = Instant.now();
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public double getOverallScore() {
        return overallScore;
    }

    public Map<String, Double> getScores() {
        return scores;
    }

    public Map<String, String> getFeedback() {
        return feedback;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getAgentResponse() {
        return agentResponse;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
