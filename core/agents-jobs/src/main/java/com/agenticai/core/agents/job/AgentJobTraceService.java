package com.agenticai.core.agents.job;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.dto.AgentJobProgressNoteView;
import com.agenticai.core.agents.job.dto.JobTraceEntry;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The merged, chronological answer to "what has this job actually been doing?"
 *
 * <h2>This adds no new logging mechanism whatsoever</h2>
 *
 * That is the design, not a limitation. Every tool call a job makes is ALREADY written to {@code
 * tool_call_log} by {@code LoggingToolCallback}, keyed on the chat turn's {@code sessionId} - and a
 * job's session id is derived from its own primary key ({@link AgentJobSession}). So the entire
 * tool half of this trace is one existing read, {@code
 * ToolCallLogService#findByAgentIdAndSessionId}, called with a string this workstream can compute.
 * No new table, no new writer, no edit to {@code LoggingToolCallback}, {@code GuardedToolCallback}
 * or {@code AgentToolProvider}. The progress half is the one genuinely new source.
 *
 * <h2>Merging in memory rather than in SQL</h2>
 *
 * The two sources live in different tables with different shapes and, more importantly, are reached
 * through two services that each enforce their own access rules. A UNION query would bypass both
 * and re-implement their filtering as a predicate - which is exactly how the query and the services
 * drift apart. The volume makes this a non-question: a job is bounded at 200 progress notes and a
 * per-job tool ceiling in the same order of magnitude, so the "large" case is a few hundred rows
 * sorted in memory.
 *
 * <h2>Ordering</h2>
 *
 * Both sources are ordered by when the event BEGAN ({@code created_at} for a note, {@code
 * started_at} for a tool call), with the entry type as a tiebreak so the order is total and a
 * repeated poll cannot reshuffle two events that share a timestamp. Ordering tool calls by {@code
 * completed_at} instead would put a slow tool AFTER the progress note the agent wrote while waiting
 * for it, which inverts the actual causality an operator is trying to read.
 */
@Service
public class AgentJobTraceService {

    private final AgentService agentService;
    private final AgentJobRepository jobRepository;
    private final AgentJobProgressService progressService;
    private final ToolCallLogService toolCallLogService;

    public AgentJobTraceService(
            AgentService agentService,
            AgentJobRepository jobRepository,
            AgentJobProgressService progressService,
            ToolCallLogService toolCallLogService) {
        this.agentService = agentService;
        this.jobRepository = jobRepository;
        this.progressService = progressService;
        this.toolCallLogService = toolCallLogService;
    }

    /**
     * The whole trace. VIEWER, matching {@code AgentJobService#get}: a trace carries the agent's
     * tool inputs and its own commentary, which is conversation content, not metadata.
     */
    public List<JobTraceEntry> trace(UUID agentId, UUID jobId) {
        return traceAfter(agentId, jobId, null);
    }

    /**
     * The incremental trace, for the live stream. {@code after} is EXCLUSIVE on both sources, so a
     * client that re-sends the timestamp of the last entry it received never sees it twice.
     */
    public List<JobTraceEntry> traceAfter(UUID agentId, UUID jobId, Instant after) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        AgentJob job = requireJob(agentId, jobId);

        List<JobTraceEntry> entries = new ArrayList<>();
        for (AgentJobProgressNoteView note : progressService.notesAfter(jobId, after)) {
            entries.add(JobTraceEntry.progress(note.createdAt(), note.note()));
        }
        // Filtered here rather than in a dedicated repository method: the read is
        // reused VERBATIM from the existing service (see the class javadoc), and
        // a per-job tool-call list is small enough that adding a query variant to
        // a shared repository - a file outside this feature - would cost more
        // than it saves.
        for (ToolCallLog call :
                toolCallLogService.findByAgentIdAndSessionId(agentId, job.getSessionId())) {
            if (after != null && !call.getStartedAt().isAfter(after)) {
                continue;
            }
            entries.add(
                    JobTraceEntry.toolCall(
                            call.getStartedAt(),
                            call.getToolName(),
                            call.getToolInput(),
                            call.getErrorMessage(),
                            durationMs(call)));
        }

        entries.sort(
                Comparator.comparing(JobTraceEntry::at)
                        .thenComparing(entry -> entry.type().name()));
        return entries;
    }

    /**
     * Path-scoped load, so a job id belonging to a different agent 404s exactly as a non-existent
     * one does - see {@link AgentJobNotFoundException} on the anti-enumeration posture.
     */
    private AgentJob requireJob(UUID agentId, UUID jobId) {
        return jobRepository
                .findByIdAndAgentId(jobId, agentId)
                .orElseThrow(() -> new AgentJobNotFoundException(jobId));
    }

    /**
     * Null when the call has not finished. {@code tool_call_log.completed_at} is NOT NULL in the
     * schema, so this is defensive rather than expected - but a trace view whose whole purpose is
     * diagnosing a job in flight is the wrong place to take an NPE on a row that somehow violated
     * that.
     */
    private static Long durationMs(ToolCallLog call) {
        if (call.getStartedAt() == null || call.getCompletedAt() == null) {
            return null;
        }
        return call.getCompletedAt().toEpochMilli() - call.getStartedAt().toEpochMilli();
    }
}
