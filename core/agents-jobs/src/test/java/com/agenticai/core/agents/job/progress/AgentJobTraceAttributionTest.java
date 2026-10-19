package com.agenticai.core.agents.job.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.job.AgentJobTraceService;
import com.agenticai.core.agents.job.dto.AgentJobProgressNoteView;
import com.agenticai.core.agents.job.dto.JobTraceEntry;
import com.agenticai.core.agents.job.dto.JobTraceEntryType;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PR-7's second acceptance criterion: <b>a job's full tool history is reconstructable afterwards,
 * attributed to the job.</b>
 *
 * <p>The attribution mechanism is worth stating because it is what lets this feature exist without
 * a single new writer: every tool call is ALREADY written to {@code tool_call_log} by {@code
 * LoggingToolCallback}, keyed on the turn's session id - and a job's session id is derived from its
 * own primary key ({@code job:<uuid>}). If that derivation, or the read that uses it, ever drifts,
 * the trace silently returns someone else's tool calls or none at all, and nothing else in the
 * system would notice. Hence the explicit assertion on the exact session id the lookup is made
 * with.
 */
class AgentJobTraceAttributionTest {

    private final UUID agentId = UUID.randomUUID();
    private final AgentService agentService = mock(AgentService.class);
    private final AgentJobRepository jobRepository = mock(AgentJobRepository.class);
    private final AgentJobProgressService progressService = mock(AgentJobProgressService.class);
    private final ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);

    private final AgentJobTraceService traceService =
            new AgentJobTraceService(
                    agentService, jobRepository, progressService, toolCallLogService);

    @Test
    void aJobsToolCallsAndProgressNotesMergeIntoOneChronologicalTrace() {
        AgentJob job = job();
        String sessionId = AgentJobSession.sessionIdFor(job.getId());
        Instant t0 = Instant.parse("2026-08-17T09:00:00Z");

        when(jobRepository.findByIdAndAgentId(job.getId(), agentId)).thenReturn(Optional.of(job));
        when(progressService.notesAfter(job.getId(), null))
                .thenReturn(
                        List.of(
                                new AgentJobProgressNoteView(
                                        UUID.randomUUID(),
                                        "Fetching last month's invoices",
                                        10,
                                        ProgressSource.AGENT,
                                        t0)));
        when(toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId))
                .thenReturn(
                        List.of(
                                new ToolCallLog(
                                        agentId,
                                        sessionId,
                                        "knowledge_search",
                                        "{\"q\":\"invoices\"}",
                                        "3 hits",
                                        null,
                                        t0.plusSeconds(5),
                                        t0.plusSeconds(7))));

        List<JobTraceEntry> trace = traceService.trace(agentId, job.getId());

        // The lookup key is the whole attribution mechanism - assert it exactly.
        verify(toolCallLogService).findByAgentIdAndSessionId(agentId, sessionId);
        assertTrue(sessionId.startsWith(AgentJobSession.PREFIX));

        assertEquals(2, trace.size());
        // Ordered by when each event BEGAN: the note the agent wrote before
        // starting the tool call must come first, or the trace inverts the
        // causality an operator is reading it for.
        assertEquals(JobTraceEntryType.PROGRESS, trace.get(0).type());
        assertEquals("Fetching last month's invoices", trace.get(0).label());
        assertNull(trace.get(0).durationMs(), "a progress note is an instant, not an interval");

        assertEquals(JobTraceEntryType.TOOL_CALL, trace.get(1).type());
        assertEquals("knowledge_search", trace.get(1).label());
        assertEquals("{\"q\":\"invoices\"}", trace.get(1).detail());
        assertEquals(Long.valueOf(2000L), trace.get(1).durationMs());
    }

    /**
     * The incremental read behind the live stream. {@code after} is EXCLUSIVE on BOTH sources - a
     * client that re-sends the timestamp of the last entry it received must not be handed that
     * entry a second time.
     */
    @Test
    void theIncrementalTraceExcludesToolCallsAtOrBeforeTheCursor() {
        AgentJob job = job();
        String sessionId = AgentJobSession.sessionIdFor(job.getId());
        Instant cursor = Instant.parse("2026-08-17T09:00:10Z");

        when(jobRepository.findByIdAndAgentId(job.getId(), agentId)).thenReturn(Optional.of(job));
        when(progressService.notesAfter(any(UUID.class), any(Instant.class))).thenReturn(List.of());
        when(toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId))
                .thenReturn(
                        List.of(
                                // exactly at the cursor - already delivered
                                new ToolCallLog(
                                        agentId, sessionId, "before", "{}", "ok", null, cursor,
                                        cursor),
                                new ToolCallLog(
                                        agentId,
                                        sessionId,
                                        "after",
                                        "{}",
                                        "ok",
                                        null,
                                        cursor.plusSeconds(1),
                                        cursor.plusSeconds(2))));

        List<JobTraceEntry> trace = traceService.traceAfter(agentId, job.getId(), cursor);

        assertEquals(1, trace.size());
        assertEquals("after", trace.get(0).label());
    }

    private AgentJob job() {
        return AgentJob.queued(
                UUID.randomUUID(),
                "t_test",
                agentId,
                "Reconcile yesterday's invoices",
                "alice@example.com",
                "alice@example.com",
                Set.of("ROLE_user"),
                AgentJobOrigin.API,
                null,
                60,
                1,
                15,
                null);
    }
}
