package com.agenticai.core.agents.tools;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, UUID> {

    List<ToolCallLog> findByAgentIdOrderByStartedAtDesc(UUID agentId);

    // Workflow run detailed logs (#151 follow-up: "all the agents logs
    // should be printed when workflow is ran") - WorkflowExecutionEngine
    // passes the workflow run's own id as AgentChatService's sessionId (see
    // executeAgentNode), so every tool call an Agent node's LLM made during
    // that one node execution is queryable by (agentId, that run's id),
    // oldest first so the log reads in the order the tools actually fired.
    List<ToolCallLog> findByAgentIdAndSessionIdOrderByStartedAtAsc(UUID agentId, String sessionId);

    /**
     * The first 200 calls of one run that started at or after {@code since}, oldest first. For a
     * reader that only wants ONE turn of a long-lived session (the skill-learning loop) and must
     * not pull the session's whole history, outputs included, on every turn.
     */
    List<ToolCallLog> findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
            UUID agentId, String sessionId, Instant since);

    /**
     * restore_tool_result (app.context.compaction.*): one logged call, addressed by id but ALWAYS
     * bound to the calling agent and session, so a model-supplied id can never reach another run's
     * output.
     */
    Optional<ToolCallLog> findByIdAndAgentIdAndSessionId(UUID id, UUID agentId, String sessionId);

    /**
     * restore_tool_result: the newest calls of one tool in this run, newest first (bounded; outputs
     * are large).
     */
    List<ToolCallLog> findTop50ByAgentIdAndSessionIdAndToolNameOrderByStartedAtDesc(
            UUID agentId, String sessionId, String toolName);

    /**
     * Autonomous agent jobs (PR-1) - the per-JOB tool-call ceiling. A job's chat turns all run
     * under one derived session id ({@code AgentJobSession.sessionIdFor}), so counting rows for
     * (agentId, that session) is exactly "how many tool calls has this job made in total", with no
     * new column and no change to the write side.
     *
     * <p>Read at TURN BOUNDARIES rather than before each individual tool call: exact per-call
     * enforcement would need an {@code AgentToolProvider#resolveToolsFor} overload, and within a
     * single turn the existing {@code app.guardrails.max-tool-calls-per-turn} ceiling already
     * applies unchanged - so the window is coarser, never unbounded.
     */
    long countByAgentIdAndSessionId(UUID agentId, String sessionId);

    /**
     * Task #959 - this agent's total tool-call volume, all-time, for the per-agent reliability
     * panel.
     */
    long countByAgentId(UUID agentId);

    /**
     * Task #959 - counterpart to {@link #countByAgentId}: how many of those tool calls recorded an
     * error.
     */
    long countByAgentIdAndErrorMessageIsNotNull(UUID agentId);

    /**
     * Data-governance audit fix (2026-07-20) - "show me every action this platform has taken as a
     * result of MCP grant X" for a real incident investigation or compliance audit. Most-recent
     * first, matching {@link #findByAgentIdOrderByStartedAtDesc}'s ordering convention. Backs
     * {@code McpServerAccessController}'s own {@code GET /api/mcpserver/grants/{id}/tool-calls}.
     */
    List<ToolCallLog> findByMcpGrantIdOrderByStartedAtDesc(UUID mcpGrantId);

    /**
     * Memory provenance ({@code MemoryProvenanceService}) - the {@code remember_fact} / {@code
     * remember_shared_fact} calls one agent made in a short window around a fact's creation time,
     * oldest first. Read-only; a handful of rows at most.
     */
    List<ToolCallLog> findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
            UUID agentId, Collection<String> toolNames, Instant from, Instant to);
}
