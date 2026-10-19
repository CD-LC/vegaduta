package com.agenticai.core.agents.learning.routines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.LearningProposal;
import com.agenticai.core.agents.learning.LearningProposalApplyException;
import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskRequest;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.security.TenantLimitExceededException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RoutineApplierTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String BODY =
            "Every Monday at 09:05 (Asia/Kolkata).\nTask: Summarise my open pull requests";
    private static final String PAYLOAD =
            "{\"agentId\":\"" + AGENT + "\",\"name\":\"Summarise my open pull requests\","
                    + "\"prompt\":\"Summarise my open pull requests\",\"frequency\":\"weekly\","
                    + "\"time\":\"09:05\",\"day\":\"MON\",\"timezone\":\"Asia/Kolkata\"}";

    private ScheduledTaskService tasks;
    private RoutineApplier applier;

    @BeforeEach
    void setUp() {
        tasks = mock(ScheduledTaskService.class);
        when(tasks.list()).thenReturn(List.of());
        applier = new RoutineApplier(tasks);
    }

    private static LearningProposal proposal(UUID agentId, String payload) {
        LearningProposal p = mock(LearningProposal.class);
        when(p.getKind()).thenReturn(LearningProposalKind.ROUTINE);
        when(p.getAgentId()).thenReturn(agentId);
        when(p.getBody()).thenReturn(BODY);
        when(p.getPayload()).thenReturn(payload);
        return p;
    }

    private static TaskResponse existing(UUID agentId, String name, String prompt) {
        return new TaskResponse(
                UUID.randomUUID(), agentId, name, prompt, "daily", "08:00", null, null, "UTC", "", "in_app",
                null, true, null, null, List.of(), null, null, null, 0, 0, null, null, "me", null, null);
    }

    @Test
    void handlesTheRoutineKind() {
        assertEquals(LearningProposalKind.ROUTINE, applier.kind());
    }

    @Test
    void approvingAnUntouchedProposalCreatesTheScheduledTaskFromThePayload() throws Exception {
        applier.apply(proposal(AGENT, PAYLOAD), BODY);

        ArgumentCaptor<TaskRequest> sent = ArgumentCaptor.forClass(TaskRequest.class);
        verify(tasks).create(sent.capture());
        TaskRequest r = sent.getValue();
        assertEquals(AGENT, r.agentId());
        assertEquals("Summarise my open pull requests", r.prompt());
        assertEquals("Summarise my open pull requests", r.name());
        assertEquals("weekly", r.frequency());
        assertEquals("09:05", r.time());
        assertEquals("MON", r.day());
        assertEquals("Asia/Kolkata", r.timezone());
        assertEquals("in_app", r.delivery());
        assertNull(r.cron());
        assertNull(r.deliveryTo());
    }

    @Test
    void anEditedTaskLineBecomesThePromptAndKeepsTheSchedule() throws Exception {
        applier.apply(
                proposal(AGENT, PAYLOAD),
                "Every Monday at 09:05 (Asia/Kolkata).\nTask: Summarise my open pull requests and list blockers");

        ArgumentCaptor<TaskRequest> sent = ArgumentCaptor.forClass(TaskRequest.class);
        verify(tasks).create(sent.capture());
        assertEquals("Summarise my open pull requests and list blockers", sent.getValue().prompt());
        assertEquals("MON", sent.getValue().day());
    }

    @Test
    void anEditWithoutTheMarkerIsTheWholePrompt() throws Exception {
        applier.apply(proposal(AGENT, PAYLOAD), "List my calendar for the day");

        ArgumentCaptor<TaskRequest> sent = ArgumentCaptor.forClass(TaskRequest.class);
        verify(tasks).create(sent.capture());
        assertEquals("List my calendar for the day", sent.getValue().prompt());
    }

    @Test
    void theAgentFallsBackToThePayload() throws Exception {
        applier.apply(proposal(null, PAYLOAD), BODY);

        ArgumentCaptor<TaskRequest> sent = ArgumentCaptor.forClass(TaskRequest.class);
        verify(tasks).create(sent.capture());
        assertEquals(AGENT, sent.getValue().agentId());
    }

    @Test
    void noAgentAnywhereIsRefused() {
        String noAgent = PAYLOAD.replace("\"agentId\":\"" + AGENT + "\",", "");
        LearningProposalApplyException e =
                assertThrows(LearningProposalApplyException.class, () -> applier.apply(proposal(null, noAgent), BODY));
        assertEquals("This routine does not say which agent should run it.", e.getMessage());
        verify(tasks, never()).create(any());
    }

    @Test
    void aProposalWithoutAScheduleIsRefused() {
        assertThrows(
                LearningProposalApplyException.class,
                () -> applier.apply(proposal(AGENT, "{\"prompt\":\"x\"}"), BODY));
        verify(tasks, never()).create(any());
    }

    @Test
    void anExistingTaskThatDoesTheSameThingIsRefusedByName() {
        when(tasks.list())
                .thenReturn(List.of(existing(AGENT, "PR digest", "Please summarise the open pull requests")));
        LearningProposalApplyException e =
                assertThrows(LearningProposalApplyException.class, () -> applier.apply(proposal(AGENT, PAYLOAD), BODY));
        assertEquals("You already have a scheduled task for this (\"PR digest\").", e.getMessage());
        verify(tasks, never()).create(any());
    }

    @Test
    void aTaskOfAnotherAgentDoesNotBlock() throws Exception {
        when(tasks.list())
                .thenReturn(
                        List.of(existing(UUID.randomUUID(), "PR digest", "Summarise my open pull requests")));
        applier.apply(proposal(AGENT, PAYLOAD), BODY);
        verify(tasks).create(any());
    }

    @Test
    void theServicesOwnReasonIsShownToThePerson() {
        doThrow(new IllegalArgumentException("The prompt is too long.")).when(tasks).create(any());
        assertEquals(
                "The prompt is too long.",
                assertThrows(LearningProposalApplyException.class, () -> applier.apply(proposal(AGENT, PAYLOAD), BODY))
                        .getMessage());

        doThrow(new TenantLimitExceededException("Upgrade for more tasks.")).when(tasks).create(any());
        assertEquals(
                "Upgrade for more tasks.",
                assertThrows(LearningProposalApplyException.class, () -> applier.apply(proposal(AGENT, PAYLOAD), BODY))
                        .getMessage());
    }

    @Test
    void scheduledTasksSwitchedOffIsSaidPlainly() {
        when(tasks.list()).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertEquals(
                "Scheduled tasks are not enabled on this install.",
                assertThrows(LearningProposalApplyException.class, () -> applier.apply(proposal(AGENT, PAYLOAD), BODY))
                        .getMessage());
    }

    @Test
    void anUnusableAgentIsRefusedWithoutLeakingTheCause() {
        doThrow(new RuntimeException("jdbc: connection details")).when(tasks).create(any());
        assertEquals(
                "That agent is no longer available to you.",
                assertThrows(LearningProposalApplyException.class, () -> applier.apply(proposal(AGENT, PAYLOAD), BODY))
                        .getMessage());
    }

    @Test
    void nameIsTheFirstWordsWithoutTrailingPunctuation() {
        assertEquals("Summarise my open pull requests", RoutineApplier.nameOf("Summarise my open pull requests."));
        assertEquals(79, RoutineApplier.nameOf("word ".repeat(40)).length());
    }
}
