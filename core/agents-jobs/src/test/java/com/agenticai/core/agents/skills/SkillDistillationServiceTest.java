package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobLookupAdapter;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.repo.EpisodeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * SkillDistillationService - the "agents that acquire skills" loop's detect + stage halves. The
 * claims under test: patterns need THREE distinct sessions (an accident repeated inside one run is
 * not a skill), only error-free calls teach a sequence, consecutive repeats collapse (a 12x poll
 * and a 3x poll are the same procedure), every staged draft goes through {@code propose} (disabled
 * + digest-pinned + admin-gated), and a second run over the same evidence is idempotent by
 * generated name.
 */
class SkillDistillationServiceTest {

    private static final UUID AGENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant T0 = Instant.parse("2026-08-17T09:00:00Z");

    private AgentJobRepository jobRepository;
    private EpisodeRepository episodeRepository;
    private ToolCallLogRepository toolCallLogRepository;
    private SkillService skillService;
    private SkillDistillationService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(AgentJobRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        toolCallLogRepository = mock(ToolCallLogRepository.class);
        skillService = mock(SkillService.class);
        service =
                new SkillDistillationService(
                        new AgentJobLookupAdapter(jobRepository),
                        episodeRepository,
                        toolCallLogRepository,
                        skillService);
        when(episodeRepository.findByAgentIdAndRewardIsNotNullOrderByStartedAtAsc(AGENT_ID))
                .thenReturn(List.of());
    }

    // ------------------------------------------------------------------ detect

    @Test
    void detectRequiresThreeDistinctSessions() {
        // Same sequence twice - not yet a skill.
        AgentJob j1 = succeededJob("look up the invoice");
        AgentJob j2 = succeededJob("reconcile the ledger");
        stubJobs(List.of(j1, j2));
        stubCalls(j1, calls(j1.getSessionId(), "knowledge_search", "report_generate"));
        stubCalls(j2, calls(j2.getSessionId(), "knowledge_search", "report_generate"));

        assertTrue(service.detectPatterns(AGENT_ID).isEmpty());
    }

    @Test
    void detectGroupsRepeatsAcrossSessionsAndCollapsesConsecutiveDupes() {
        // Three runs of the same procedure; run 3 polls the search tool 4x in
        // a row - consecutive repeats are one step, so it must still match.
        AgentJob j1 = succeededJob("invoice summary");
        AgentJob j2 = succeededJob("weekly rollup");
        AgentJob j3 = succeededJob("debtor report");
        stubJobs(List.of(j1, j2, j3));
        stubCalls(j1, calls(j1.getSessionId(), "knowledge_search", "report_generate"));
        stubCalls(j2, calls(j2.getSessionId(), "knowledge_search", "report_generate"));
        stubCalls(
                j3,
                calls(
                        j3.getSessionId(),
                        "knowledge_search",
                        "knowledge_search",
                        "knowledge_search",
                        "knowledge_search",
                        "report_generate"));

        List<SkillDistillationService.ToolPattern> patterns = service.detectPatterns(AGENT_ID);
        assertEquals(1, patterns.size());
        SkillDistillationService.ToolPattern p = patterns.get(0);
        assertEquals(List.of("knowledge_search", "report_generate"), p.tools());
        assertEquals(3, p.distinctSessions());
        assertEquals(3, p.runs());
        assertEquals(List.of("invoice summary", "weekly rollup", "debtor report"), p.sampleGoals());
    }

    @Test
    void detectIgnoresSingleToolRunsFailedCallsAndOverlongChains() {
        AgentJob j1 = succeededJob("g1");
        AgentJob j2 = succeededJob("g2");
        AgentJob j3 = succeededJob("g3");
        stubJobs(List.of(j1, j2, j3));
        // j1: one tool only - not a procedure.
        stubCalls(j1, calls(j1.getSessionId(), "knowledge_search"));
        // j2: the second call errored - the run "succeeded" but the failed call
        // is recovery, not the procedure; what remains is a single step.
        Instant t = T0;
        stubCalls(
                j2,
                List.of(
                        call(j2.getSessionId(), "knowledge_search", null, t),
                        call(j2.getSessionId(), "http_fetch", "boom", t.plusSeconds(2))));
        // j3: a 9-tool chain - a run, not a reusable pattern.
        stubCalls(j3, calls(j3.getSessionId(), "a", "b", "c", "d", "e", "f", "g", "h", "i"));

        assertTrue(service.detectPatterns(AGENT_ID).isEmpty());
    }

    @Test
    void detectMinesHighRewardEpisodesAndHonoursTheEpisodeWindow() {
        AgentJob j1 = succeededJob("from jobs");
        stubJobs(List.of(j1));
        stubCalls(j1, calls(j1.getSessionId(), "knowledge_search", "report_generate"));

        // Two chat sessions whose episodes scored well - same procedure.
        Episode e1 = episode("chat:s1", new BigDecimal("0.9"), T0, T0.plusSeconds(60));
        Episode e2 =
                episode("chat:s2", new BigDecimal("0.8"), T0.plusSeconds(120), T0.plusSeconds(180));
        // Below the reward floor - its calls must not count.
        Episode e3 = episode("chat:s3", new BigDecimal("0.2"), T0, T0.plusSeconds(60));
        when(episodeRepository.findByAgentIdAndRewardIsNotNullOrderByStartedAtAsc(AGENT_ID))
                .thenReturn(List.of(e1, e2, e3));

        // e1's window covers only the first call pair; a stray call AFTER
        // endedAt belongs to a different turn and must be windowed out.
        stubCalls(
                "chat:s1",
                List.of(
                        call("chat:s1", "knowledge_search", null, T0.plusSeconds(1)),
                        call("chat:s1", "report_generate", null, T0.plusSeconds(5)),
                        call("chat:s1", "delete_everything", null, T0.plusSeconds(90))));
        stubCalls(
                "chat:s2",
                List.of(
                        call("chat:s2", "knowledge_search", null, T0.plusSeconds(121)),
                        call("chat:s2", "report_generate", null, T0.plusSeconds(125))));
        stubCalls("chat:s3", calls("chat:s3", "knowledge_search", "report_generate"));

        List<SkillDistillationService.ToolPattern> patterns = service.detectPatterns(AGENT_ID);
        assertEquals(1, patterns.size());
        SkillDistillationService.ToolPattern p = patterns.get(0);
        // j1 + e1 + e2 = 3 sessions; e3 (low reward) never counted.
        assertEquals(3, p.distinctSessions());
        assertEquals(3, p.runs());
        assertEquals(0.85, p.meanReward(), 1e-9);
    }

    // ------------------------------------------------------------------ stage

    @Test
    void distillStagesEveryPatternThroughPropose() {
        AgentJob j1 = succeededJob("g1");
        AgentJob j2 = succeededJob("g2");
        AgentJob j3 = succeededJob("g3");
        stubJobs(List.of(j1, j2, j3));
        for (AgentJob j : List.of(j1, j2, j3)) {
            stubCalls(j, calls(j.getSessionId(), "knowledge_search", "report_generate"));
        }

        SkillDistillationService.DistillResult result = service.distill(AGENT_ID);

        assertEquals(1, result.proposedSkillNames().size());
        String name = result.proposedSkillNames().get(0);
        assertTrue(name.startsWith("distilled-"));
        assertTrue(name.length() <= 64);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(skillService)
                .propose(
                        eq(name),
                        description.capture(),
                        body.capture(),
                        eq("agent:skill-distiller"));
        // The staged draft records its evidence; the actual enabled/disabled
        // flagging lives inside propose (always disabled).
        assertTrue(description.getValue().contains("3 successful runs"));
        assertTrue(body.getValue().contains("knowledge_search"));
        assertTrue(body.getValue().contains("report_generate"));
        assertTrue(body.getValue().contains("1. `knowledge_search`"));
    }

    @Test
    void distillRecordsDuplicateProposalsAsSkipped() {
        // Second run over the same evidence: same name -> propose rejects as
        // duplicate -> recorded in skipped, not silently double-staged.
        AgentJob j1 = succeededJob("g1");
        AgentJob j2 = succeededJob("g2");
        AgentJob j3 = succeededJob("g3");
        stubJobs(List.of(j1, j2, j3));
        for (AgentJob j : List.of(j1, j2, j3)) {
            stubCalls(j, calls(j.getSessionId(), "knowledge_search", "report_generate"));
        }
        when(skillService.propose(any(), any(), any(), any()))
                .thenThrow(new SkillValidationException("A skill named 'x' already exists"));

        SkillDistillationService.DistillResult result = service.distill(AGENT_ID);

        assertTrue(result.proposedSkillNames().isEmpty());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().values().iterator().next().contains("already exists"));
    }

    @Test
    void distillNothingWhenNoPatternQualifies() {
        stubJobs(List.of());
        SkillDistillationService.DistillResult result = service.distill(AGENT_ID);
        assertTrue(result.patterns().isEmpty());
        assertTrue(result.proposedSkillNames().isEmpty());
        verify(skillService, never()).propose(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ helpers

    private AgentJob succeededJob(String goal) {
        AgentJob job =
                AgentJob.queued(
                        UUID.randomUUID(),
                        "t_test",
                        AGENT_ID,
                        goal,
                        "alice@example.com",
                        "alice@example.com",
                        Set.of("ROLE_user"),
                        AgentJobOrigin.API,
                        null,
                        60,
                        1,
                        15,
                        null);
        job.markRunning("test", T0);
        job.succeed("done", "{}", BigDecimal.ZERO, 0, T0.plusSeconds(30));
        return job;
    }

    private Episode episode(String sessionId, BigDecimal reward, Instant start, Instant end) {
        Episode e = new Episode(AGENT_ID, sessionId, "turn-1", start, null, null);
        e.setEndedAt(end);
        e.setReward(reward);
        return e;
    }

    private void stubJobs(List<AgentJob> jobs) {
        when(jobRepository.findByAgentIdAndStatusInOrderByCreatedAtDesc(
                        eq(AGENT_ID), eq(List.of(AgentJobStatus.SUCCEEDED)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(jobs));
    }

    private void stubCalls(AgentJob job, List<ToolCallLog> calls) {
        stubCalls(job.getSessionId(), calls);
    }

    private void stubCalls(String sessionId, List<ToolCallLog> calls) {
        when(toolCallLogRepository.findByAgentIdAndSessionIdOrderByStartedAtAsc(
                        AGENT_ID, sessionId))
                .thenReturn(calls);
    }

    private List<ToolCallLog> calls(String sessionId, String... toolNames) {
        List<ToolCallLog> out = new ArrayList<>();
        for (int i = 0; i < toolNames.length; i++) {
            out.add(call(sessionId, toolNames[i], null, T0.plusSeconds(i * 2L)));
        }
        return out;
    }

    private ToolCallLog call(String sessionId, String tool, String error, Instant at) {
        return new ToolCallLog(AGENT_ID, sessionId, tool, "{}", "ok", error, at, at.plusSeconds(1));
    }
}
