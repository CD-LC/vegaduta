package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin read service over {@link ToolCallLogRepository} - exists purely so {@link
 * com.agenticai.core.agents.core.AgentController} depends on a service, not a Spring Data repository
 * directly. Controller -> service -> repository is the layering every other controller in this
 * codebase follows ({@code AgentService}, {@code TwilioCredentialService}, {@code
 * ChannelSessionService}, ...) - {@code AgentController} calling {@code ToolCallLogRepository}
 * directly was the one place that slipped, caught during a design-principles review pass. Not just
 * style: a repository is a persistence-layer contract (query methods, paging, whatever Spring Data
 * exposes) - routing every access through a service keeps that contract from leaking into the web
 * layer, and gives future changes (caching, tenant-scoping once Phase 0 Stage 4 lands, etc.)
 * exactly one place to land instead of every controller that happened to reach in directly.
 */
@Service
public class ToolCallLogService {

    private final ToolCallLogRepository repository;

    public ToolCallLogService(ToolCallLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ToolCallLog> findByAgentId(UUID agentId) {
        return repository.findByAgentIdOrderByStartedAtDesc(agentId);
    }

    // Quick Task live trace (#474 follow-up: "I want to see the live trace
    // log for that task - everything running for that task") - the query
    // WorkflowNodeExecutor already uses internally to fold an Agent node's
    // tool calls into its own run-log event, now also exposed as its own
    // read so QuickTaskService can poll it from a separate thread while a
    // run is still in flight. Oldest first, matching the order tools
    // actually fired in.
    @Transactional(readOnly = true)
    public List<ToolCallLog> findByAgentIdAndSessionId(UUID agentId, String sessionId) {
        return repository.findByAgentIdAndSessionIdOrderByStartedAtAsc(agentId, sessionId);
    }

    /** The first 200 calls of this run that started at or after {@code since}, oldest first. */
    @Transactional(readOnly = true)
    public List<ToolCallLog> findSince(UUID agentId, String sessionId, java.time.Instant since) {
        return repository
                .findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                        agentId, sessionId, since);
    }

    /**
     * One logged call of THIS run (agent + session), for restore_tool_result; empty for any other
     * run.
     */
    @Transactional(readOnly = true)
    public Optional<ToolCallLog> findByIdAndAgentIdAndSessionId(
            UUID id, UUID agentId, String sessionId) {
        return repository.findByIdAndAgentIdAndSessionId(id, agentId, sessionId);
    }

    /**
     * The newest calls (up to 50) of one tool in THIS run, newest first, for restore_tool_result.
     */
    @Transactional(readOnly = true)
    public List<ToolCallLog> findRecentByAgentIdAndSessionIdAndToolName(
            UUID agentId, String sessionId, String toolName) {
        return repository.findTop50ByAgentIdAndSessionIdAndToolNameOrderByStartedAtDesc(
                agentId, sessionId, toolName);
    }

    /**
     * Task #959 - one agent's all-time tool-call volume/failure-count for the per-agent reliability
     * panel.
     */
    @Transactional(readOnly = true)
    public AgentToolCallStats statsForAgent(UUID agentId) {
        long total = repository.countByAgentId(agentId);
        long failed = repository.countByAgentIdAndErrorMessageIsNotNull(agentId);
        return new AgentToolCallStats(total, failed);
    }

    /**
     * Data-governance audit fix (2026-07-20) - see {@link
     * ToolCallLogRepository#findByMcpGrantIdOrderByStartedAtDesc}.
     */
    @Transactional(readOnly = true)
    public List<ToolCallLog> findByMcpGrantId(UUID mcpGrantId) {
        return repository.findByMcpGrantIdOrderByStartedAtDesc(mcpGrantId);
    }
}
