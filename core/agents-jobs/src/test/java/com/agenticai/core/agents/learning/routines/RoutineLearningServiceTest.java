package com.agenticai.core.agents.learning.routines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Outcome;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.learning.LearningProposalService.Result;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.Capabilities;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.agents.skills.RoutineObservationGateway.Observation;
import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** The worker half of the loop, run inline against an in-memory observation list. */
class RoutineLearningServiceTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final TenantScope SCOPE =
            TenantScope.of(TenantContext.of("t1", "me", Set.of()));
    private static final String ASK = "Summarise my open pull requests";

    private final List<RoutineObservation> rows = new ArrayList<>();
    private RoutineObservationRepository repo;
    private LearningProposalService proposals;
    private ScheduledTaskService scheduled;
    private RoutineLearningService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repo = mock(RoutineObservationRepository.class);
        when(repo.findTop500BySubjectAndObservedAtAfterOrderByObservedAtDesc(anyString(), any()))
                .thenAnswer(
                        inv ->
                                rows.stream()
                                        .sorted((a, b) -> b.getObservedAt().compareTo(a.getObservedAt()))
                                        .toList());
        when(repo.save(any()))
                .thenAnswer(
                        inv -> {
                            RoutineObservation o = inv.getArgument(0);
                            rows.add(o);
                            return o;
                        });
        proposals = mock(LearningProposalService.class);
        when(proposals.claimSlot(anyString(), any())).thenReturn(true);
        when(proposals.propose(any())).thenReturn(new Result(Outcome.DUPLICATE, null));
        scheduled = mock(ScheduledTaskService.class);
        when(scheduled.capabilities()).thenReturn(new Capabilities(true, 25, 15, 4000, List.of("in_app")));
        when(scheduled.list()).thenReturn(List.of());
        ObjectProvider<SkillDraftScrubber> none = mock(ObjectProvider.class);
        service =
                new RoutineLearningService(
                        repo, proposals, scheduled, none, "UTC", 0.6, 3, 3, 120, 28, 45);
        service.useClock(Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC));
    }

    private void ask(String text, String when) {
        service.process(new Observation(AGENT, "s1", text, "chat", Instant.parse(when), SCOPE));
    }

    private void threeMondays() {
        ask(ASK, "2026-10-05T09:10:00Z");
        ask("Please summarise the open pull requests", "2026-10-12T08:55:00Z");
        ask(ASK, "2026-10-19T09:05:00Z");
    }

    @Test
    void thirdMondayFilesARoutineProposalWithAReadySchedule() {
        threeMondays();

        ArgumentCaptor<Proposal> sent = ArgumentCaptor.forClass(Proposal.class);
        verify(proposals, times(1)).propose(sent.capture());
        Proposal p = sent.getValue();
        assertEquals("me", p.subject());
        assertEquals(AGENT, p.agentId());
        assertEquals(LearningProposalKind.ROUTINE, p.kind());
        assertEquals("Every Monday at 09:05 (UTC).\nTask: " + ASK, p.body());
        assertTrue(p.title().startsWith("Every Monday at 09:05 (UTC): "));
        assertEquals("weekly", p.payload().get("frequency"));
        assertEquals("09:05", p.payload().get("time"));
        assertEquals("MON", p.payload().get("day"));
        assertEquals("UTC", p.payload().get("timezone"));
        assertEquals(ASK, p.payload().get("prompt"));
        assertEquals(AGENT.toString(), p.payload().get("agentId"));
        assertEquals(3, p.evidence().get("occurrences"));
        assertEquals(3, p.evidence().get("distinctDays"));
        assertEquals("routine", p.evidence().get("signal"));
    }

    @Test
    void twoMondaysFileNothingAndSpendNoSlot() {
        ask(ASK, "2026-10-12T09:00:00Z");
        ask(ASK, "2026-10-19T09:00:00Z");
        verify(proposals, never()).propose(any());
        verify(proposals, never()).claimSlot(anyString(), any());
    }

    @Test
    void thePatternIsNotReFiledOnEveryLaterRequest() {
        threeMondays();
        ask(ASK, "2026-10-19T11:00:00Z");
        ask(ASK, "2026-10-19T11:30:00Z");
        verify(proposals, times(1)).propose(any());
        verify(proposals, times(1)).claimSlot(anyString(), any());
    }

    @Test
    void theCooldownOrDailyCapStopsTheProposal() {
        when(proposals.claimSlot(anyString(), any())).thenReturn(false);
        threeMondays();
        verify(proposals, never()).propose(any());
    }

    @Test
    void aFullPendingListIsRetriedLater() {
        when(proposals.propose(any())).thenReturn(new Result(Outcome.PENDING_CAP_REACHED, null));
        threeMondays();
        ask(ASK, "2026-10-19T11:00:00Z");
        verify(proposals, times(2)).propose(any());
    }

    @Test
    void noProposalWhenScheduledTasksAreOff() {
        when(scheduled.capabilities()).thenReturn(new Capabilities(false, 25, 15, 4000, List.of()));
        threeMondays();
        verify(proposals, never()).propose(any());
    }

    @Test
    void noProposalWhenATaskAlreadyDoesIt() {
        TaskResponse task =
                new TaskResponse(
                        UUID.randomUUID(), AGENT, "PR digest", "summarise open pull requests", "weekly", "09:00",
                        "MON", null, "UTC", "", "in_app", null, true, null, null, List.of(), null, null, null, 0,
                        0, null, null, "me", null, null);
        when(scheduled.list()).thenReturn(List.of(task));
        threeMondays();
        verify(proposals, never()).propose(any());
    }

    @Test
    void aRequestThatNeededScrubbingIsNeverKept() {
        ask("Email the report to boss@example.com every morning", "2026-10-19T09:00:00Z");
        assertTrue(rows.isEmpty());
        ask("Check the logs with api_key=abcdef1234567890abcdef", "2026-10-19T09:00:00Z");
        assertTrue(rows.isEmpty());
    }

    @Test
    void ordinaryRequestsAreStoredScrubbedAndNormalised() {
        ask(ASK, "2026-10-19T09:00:00Z");
        assertEquals(1, rows.size());
        assertEquals(ASK, rows.get(0).getRawText());
        assertEquals("open pull request summarise", rows.get(0).getNormText());
        assertEquals("me", rows.get(0).getSubject());
        assertEquals("chat", rows.get(0).getSource());
    }

    @Test
    void theSameRequestSentTwiceInARowIsOne() {
        ask(ASK, "2026-10-19T09:00:00Z");
        ask(ASK, "2026-10-19T09:00:30Z");
        assertEquals(1, rows.size());
    }

    @Test
    void aRequestWithoutATenantIsIgnored() {
        service.process(
                new Observation(AGENT, "s1", ASK, "chat", Instant.parse("2026-10-19T09:00:00Z"), TenantScope.of((TenantContext) null)));
        assertTrue(rows.isEmpty());
    }

    @Test
    void quickTaskGoalsCountToo() {
        ask(ASK, "2026-10-05T09:00:00Z");
        service.process(
                new Observation(AGENT, null, ASK, "job", Instant.parse("2026-10-12T09:00:00Z"), SCOPE));
        ask(ASK, "2026-10-19T09:00:00Z");
        verify(proposals, times(1)).propose(any());
        assertEquals("job", rows.get(1).getSource());
    }

    @Test
    void theHookQueuesNothingForARequestThatCannotBeARoutine() {
        List<Runnable> queued = new ArrayList<>();
        service.useExecutor(queued::add);
        service.onObservation(new Observation(AGENT, "s", "ok thanks", "chat", Instant.now(), SCOPE));
        service.onObservation(
                new Observation(AGENT, "s", ASK, "chat", Instant.now(), TenantScope.of((TenantContext) null)));
        assertTrue(queued.isEmpty());
        service.onObservation(new Observation(AGENT, "s", ASK, "chat", Instant.now(), SCOPE));
        assertEquals(1, queued.size());
        assertTrue(service.enabled());
    }

    @Test
    void anUnknownTimezoneFallsBackInsteadOfFailingStartup() {
        assertEquals(java.time.ZoneId.systemDefault(), RoutineLearningService.zoneOf("Mars/Olympus"));
        assertEquals(java.time.ZoneId.of("Asia/Kolkata"), RoutineLearningService.zoneOf(" Asia/Kolkata "));
        assertEquals(java.time.ZoneId.systemDefault(), RoutineLearningService.zoneOf(""));
    }
}
