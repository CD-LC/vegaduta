package com.agenticai.core.agents.edge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Edge Wave 2 (EDGE-B, migration V191) - one row per reported on-device turn, the metering half
 * of finding E-4/F-25. Written exclusively by {@link EdgeTurnTelemetryController} for
 * tenant-resolved callers (the anonymous {@code /try} surface stays log-only), tenant-schema
 * like every other per-tenant operational log. {@code agentId} is deliberately NOT a JPA
 * relation/foreign key - telemetry must survive agent deletion and a fire-and-forget write must
 * never fail on a referential race (see V191's header).
 *
 * <p>Free-text columns ({@code sessionKey}/{@code modelId}/{@code outcome}/{@code reason}) are
 * bounded at WRITE time by the controller (truncation, never rejection) rather than by DB
 * constraints, keeping the "a malformed report still gets its row" fire-and-forget contract.
 */
@Entity
@Table(name = "edge_turn")
public class EdgeTurn {

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "session_key", length = 255)
    private String sessionKey;

    /** 0 = Chrome built-in, 1 = WebLLM; SMALLINT column, nullable (see V191). */
    @Column(name = "tier")
    private Short tier;

    @Column(name = "model_id", length = 128)
    private String modelId;

    /** "success" | "fallback" | "error" - the client tierRouter's vocabulary. */
    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EdgeTurn() {
        // JPA
    }

    public EdgeTurn(
        UUID agentId, String sessionKey, Short tier, String modelId,
        String outcome, String reason, Long latencyMs
    ) {
        this.id = UUID.randomUUID();
        this.agentId = agentId;
        this.sessionKey = sessionKey;
        this.tier = tier;
        this.modelId = modelId;
        this.outcome = outcome;
        this.reason = reason;
        this.latencyMs = latencyMs;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public Short getTier() {
        return tier;
    }

    public String getModelId() {
        return modelId;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
