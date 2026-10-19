package com.agenticai.core.agents.tools;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One record of a tool being invoked during a chat turn - name, input, output/error, and timing.
 * Written by LoggingToolCallback around every MCP tool call so agent behavior is auditable after
 * the fact (Stage 4 Definition of Done: "every tool call is logged and retrievable").
 */
@Entity
@Table(name = "tool_call_log")
public class ToolCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "tool_name", nullable = false, length = 256)
    private String toolName;

    @Column(name = "tool_input", columnDefinition = "TEXT")
    private String toolInput;

    @Column(name = "tool_output", columnDefinition = "TEXT")
    private String toolOutput;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    /**
     * C1 version stamping - the {@code agent_version.id} this call ran under (migration V342). Set
     * by {@code LoggingToolCallback} from {@code AgentVersionPin} - which {@code
     * TenantContextPropagatingToolCallback} re-establishes around tool execution, since this write
     * happens on a worker thread, not the request thread. Nullable: a call made outside any pinned
     * turn stamps an honest null, never a guess.
     */
    @Column(name = "agent_version_id")
    private UUID agentVersionId;

    // #815 Gap 1 - set by LoggingToolCallback when the output it received
    // was ToolCallOutputValidator.VALIDATION_FAILURE_PREFIX-tagged (the
    // prefix itself is stripped before persisting - this boolean is the
    // durable signal, not string-sniffing the stored output later).
    @Column(name = "validation_failed", nullable = false)
    private boolean validationFailed;

    // Data-governance audit fix (2026-07-20) - null for the overwhelming majority of rows
    // (every internally-triggered tool call). Set only when LoggingToolCallback was invoked
    // during a chat turn whose TenantContext (captured synchronously by
    // AgentToolProvider#guardAndLog, before the reactive tool-call stream ever starts - see
    // TenantContextPropagatingToolCallback's javadoc for why that capture point matters)
    // carried an externalTriggerId - today, exclusively an inbound core/mcpserver
    // McpServerAccessGrant id (see McpApiKeyAuthFilter). The first-class, typed, queryable
    // answer to "show me every action this platform has taken as a result of MCP grant X" -
    // see ToolCallLogRepository#findByMcpGrantIdOrderByStartedAtDesc. No FK constraint: a
    // grant can be revoked/rotated later without ever invalidating its historical log rows,
    // same "keep the append-only trail even after the credential is gone" posture
    // audit_log_entry already takes for actorId.
    @Column(name = "mcp_grant_id")
    private UUID mcpGrantId;

    protected ToolCallLog() {
        // JPA
    }

    public ToolCallLog(
            UUID agentId,
            String sessionId,
            String toolName,
            String toolInput,
            String toolOutput,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {
        this(
                agentId,
                sessionId,
                toolName,
                toolInput,
                toolOutput,
                errorMessage,
                startedAt,
                completedAt,
                false,
                null);
    }

    public ToolCallLog(
            UUID agentId,
            String sessionId,
            String toolName,
            String toolInput,
            String toolOutput,
            String errorMessage,
            Instant startedAt,
            Instant completedAt,
            boolean validationFailed) {
        this(
                agentId,
                sessionId,
                toolName,
                toolInput,
                toolOutput,
                errorMessage,
                startedAt,
                completedAt,
                validationFailed,
                null);
    }

    /** Data-governance audit fix (2026-07-20) - see {@code mcpGrantId}'s own field javadoc. */
    public ToolCallLog(
            UUID agentId,
            String sessionId,
            String toolName,
            String toolInput,
            String toolOutput,
            String errorMessage,
            Instant startedAt,
            Instant completedAt,
            boolean validationFailed,
            UUID mcpGrantId) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.toolOutput = toolOutput;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.validationFailed = validationFailed;
        this.mcpGrantId = mcpGrantId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getAgentVersionId() {
        return agentVersionId;
    }

    /**
     * Set once at write time by the call-site logger from the turn's resolved pin - see the field's
     * javadoc.
     */
    public void setAgentVersionId(UUID agentVersionId) {
        this.agentVersionId = agentVersionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolInput() {
        return toolInput;
    }

    public String getToolOutput() {
        return toolOutput;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public UUID getMcpGrantId() {
        return mcpGrantId;
    }
}
