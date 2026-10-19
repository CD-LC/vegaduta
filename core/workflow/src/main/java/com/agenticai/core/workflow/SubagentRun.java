package com.agenticai.core.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One structured Agent-node invocation from within a workflow run - the
 * per-agent audit trail {@link WorkflowRun#getTraceJson()} doesn't provide on
 * its own, since the run trace is generic across every node type and doesn't
 * retain the full structured input/output an Agent node built via {@link
 * com.agenticai.core.agents.turn.AgentChatService#chatWithParams}. Written by
 * {@link WorkflowNodeExecutor}'s structured Agent-node path only - a node
 * with no {@code inputMapping} config (the legacy plain-text path) does not
 * create a row here, matching this feature's "structured parameter passing"
 * scope.
 */
@Entity
@Table(name = "subagent_run")
public class SubagentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "node_id", nullable = false, length = 128)
    private String nodeId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "input_json", columnDefinition = "TEXT")
    private String inputJson;

    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "error_text", columnDefinition = "TEXT")
    private String errorText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SubagentRun() {
        // JPA
    }

    public SubagentRun(
        UUID workflowRunId, String nodeId, UUID agentId, String inputJson, String outputJson,
        String status, Long latencyMs, String errorText
    ) {
        this.workflowRunId = workflowRunId;
        this.nodeId = nodeId;
        this.agentId = agentId;
        this.inputJson = inputJson;
        this.outputJson = outputJson;
        this.status = status;
        this.latencyMs = latencyMs;
        this.errorText = errorText;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getInputJson() {
        return inputJson;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public String getStatus() {
        return status;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public String getErrorText() {
        return errorText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
