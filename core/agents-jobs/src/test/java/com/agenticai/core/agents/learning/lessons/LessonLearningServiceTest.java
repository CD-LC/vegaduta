package com.agenticai.core.agents.learning.lessons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.skills.LessonLearningGateway;
import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class LessonLearningServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-18T09:00:00Z");

    private ToolCallLogService toolCalls;
    private AgentLabCompletionService lab;
    private LearningProposalService proposals;
    private LessonLearningService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        toolCalls = mock(ToolCallLogService.class);
        lab = mock(AgentLabCompletionService.class);
        proposals = mock(LearningProposalService.class);
        when(proposals.claimSlot(anyString(), any())).thenReturn(true);
        when(proposals.runOffThread(any()))
                .thenAnswer(
                        inv -> {
                            ((Runnable) inv.getArgument(0)).run();
                            return true;
                        });
        when(proposals.propose(any()))
                .thenReturn(new LearningProposalService.Result(LearningProposalService.Outcome.DUPLICATE, null));
        ObjectProvider<LearningProposalService> svcProvider = mock(ObjectProvider.class);
        when(svcProvider.getIfAvailable()).thenReturn(proposals);
        ObjectProvider<com.agenticai.core.agents.skills.learning.SkillDraftScrubber> scrub = mock(ObjectProvider.class);
        service = new LessonLearningService(svcProvider, toolCalls, lab, scrub);
    }

    @AfterEach
    void tearDown() {
        LessonLearningGateway.install(null);
    }

    private static ToolCallLog call(String tool, String input, String output, String error, int sec) {
        Instant at = T0.plusSeconds(sec);
        return new ToolCallLog(AGENT, "s1", tool, input, output, error, at, at.plusMillis(100));
    }

    private static TurnOutcome turn(String user, String answer, boolean completed) {
        return new TurnOutcome(
                AGENT, "s1", user, answer, T0, completed, false,
                TenantScope.of(TenantContext.of("acme", "alice", Set.of())));
    }

    private void toolLog(List<ToolCallLog> calls) {
        when(toolCalls.findSince(eq(AGENT), eq("s1"), any())).thenReturn(calls);
    }

    // ---------------------------------------------------------------- the loop is quiet by default

    @Test
    void anOrdinaryTurnCostsNoModelCallAndTakesNoSlot() {
        toolLog(List.of(call("search", "{}", "ok", null, 1)));
        service.onTurn(turn("What is on my calendar today?", "Two meetings.", true));
        service.onTurn(turn("Thanks! And tomorrow?", "One meeting.", true));
        verify(lab, never()).complete(any());
        verify(proposals, never()).claimSlot(anyString(), any());
        verify(proposals, never()).propose(any());
    }

    @Test
    void aCorrectionAsTheFirstMessageOfASessionHasNothingToCorrect() {
        toolLog(List.of());
        service.onTurn(turn("No, I meant the Friday one", "Okay.", true));
        verify(lab, never()).complete(any());
    }

    // ---------------------------------------------------------------- signal b: user correction

    @Test
    void aUserCorrectionFilesALessonProposal() {
        toolLog(List.of());
        when(lab.complete(any(LabCompletionRequest.class)))
                .thenReturn("{\"lesson\": \"Show invoice totals in euros, not dollars.\"}");

        service.onTurn(turn("Total my invoices for October", "Your total is $1,200.", true));
        service.onTurn(turn("No, I meant in euros, not dollars", "Sorry: EUR 1,100.", true));

        verify(proposals).claimSlot(LessonLearningService.LOOP, AGENT);
        ArgumentCaptor<LabCompletionRequest> req = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab).complete(req.capture());
        assertEquals(AGENT, req.getValue().agentId());
        assertEquals(LessonLearningService.SYSTEM_PROMPT, req.getValue().systemPrompt());
        assertTrue(req.getValue().userMessage().contains("Your total is $1,200."));
        assertTrue(req.getValue().userMessage().contains("No, I meant in euros"));
        assertEquals("alice", req.getValue().subjectKey());

        ArgumentCaptor<Proposal> p = ArgumentCaptor.forClass(Proposal.class);
        verify(proposals).propose(p.capture());
        assertEquals("alice", p.getValue().subject());
        assertEquals(AGENT, p.getValue().agentId());
        assertEquals(LearningProposalKind.LESSON, p.getValue().kind());
        assertEquals("Show invoice totals in euros, not dollars.", p.getValue().body());
        assertEquals("user-correction", p.getValue().evidence().get("signal"));
        assertEquals("s1", p.getValue().evidence().get("sessionId"));
    }

    // ---------------------------------------------------------------- signal a: retried tool call

    @Test
    void aToolFailureFixedByRetryFilesALessonProposal() {
        toolLog(
                List.of(
                        call("book_slot", "{\"cal\":\"primary\"}", null, "404 calendar not found", 1),
                        call("book_slot", "{\"cal\":\"work\"}", "booked", null, 2)));
        when(lab.complete(any(LabCompletionRequest.class)))
                .thenReturn("Here you go:\n{\"lesson\":\"Book meetings on the work calendar, not the primary one.\"}");

        service.onTurn(turn("Book a slot with Dana", "Booked.", true));

        ArgumentCaptor<LabCompletionRequest> req = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab).complete(req.capture());
        String evidence = req.getValue().userMessage();
        assertTrue(evidence.contains("404 calendar not found"));
        assertTrue(evidence.contains("\"cal\":\"work\""));
        ArgumentCaptor<Proposal> p = ArgumentCaptor.forClass(Proposal.class);
        verify(proposals).propose(p.capture());
        assertEquals("tool-retry", p.getValue().evidence().get("signal"));
    }

    @Test
    void aFailedTurnDoesNotLearnFromToolCalls() {
        toolLog(
                List.of(
                        call("book_slot", "{\"cal\":\"primary\"}", null, "404", 1),
                        call("book_slot", "{\"cal\":\"work\"}", "booked", null, 2)));
        service.onTurn(turn("Book a slot", "", false));
        verify(lab, never()).complete(any());
    }

    // ---------------------------------------------------------------- bounds

    @Test
    void noFreeSlotMeansNoModelCall() {
        Mockito.doReturn(false).when(proposals).claimSlot(anyString(), any());
        toolLog(
                List.of(
                        call("book_slot", "{\"cal\":\"primary\"}", null, "404", 1),
                        call("book_slot", "{\"cal\":\"work\"}", "booked", null, 2)));
        service.onTurn(turn("Book a slot", "Done.", true));
        verify(lab, never()).complete(any());
        verify(proposals, never()).propose(any());
    }

    @Test
    void aFullWorkerQueueSkipsTheTurn() {
        Mockito.doReturn(false).when(proposals).runOffThread(any());
        service.onTurn(turn("Total my invoices", "x", true));
        service.onTurn(turn("No, I meant euros", "y", true));
        verify(toolCalls, never()).findSince(any(), anyString(), any());
        verify(lab, never()).complete(any());
    }

    @Test
    void theModelDecliningOrAnsweringBadlyFilesNothing() {
        toolLog(List.of());
        for (String reply :
                List.of(
                        "{\"lesson\": \"\"}",
                        "I cannot think of anything",
                        "{\"lesson\": \"ok\"}",
                        "{\"lesson\": \"" + "x".repeat(400) + "\"}",
                        "{not json}")) {
            Mockito.clearInvocations(proposals);
            when(lab.complete(any(LabCompletionRequest.class))).thenReturn(reply);
            service.onTurn(turn("First request", "First answer", true));
            service.onTurn(turn("No, I meant something else entirely", "Second answer", true));
            verify(proposals, never()).propose(any());
        }
    }

    @Test
    void aLimitedOrFailingModelCallIsSwallowed() {
        toolLog(List.of());
        when(lab.complete(any(LabCompletionRequest.class))).thenThrow(new TenantLimitExceededException("monthly token quota"));
        service.onTurn(turn("First request", "First answer", true));
        service.onTurn(turn("No, I meant something else entirely", "x", true));
        when(lab.complete(any(LabCompletionRequest.class))).thenThrow(new IllegalStateException("provider down"));
        service.onTurn(turn("No, that is wrong, use the other one", "y", true));
        verify(proposals, never()).propose(any());
    }

    // ---------------------------------------------------------------- scrubbing

    @Test
    void evidenceIsScrubbedBeforeItLeavesAndTheLessonAfterwards() {
        toolLog(List.of());
        when(lab.complete(any(LabCompletionRequest.class)))
                .thenReturn("{\"lesson\": \"Send reports to <addr> instead of ops@example.com.\"}");

        service.onTurn(turn("Mail the report to ops@example.com using api_key=sk-abcdefghijklmnopqrstuvwxyz123456", "Sent.", true));
        service.onTurn(turn("No, I meant the finance list", "Okay.", true));

        ArgumentCaptor<LabCompletionRequest> req = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab).complete(req.capture());
        String sent = req.getValue().userMessage();
        assertFalse(sent.contains("ops@example.com"), sent);
        assertFalse(sent.contains("sk-abcdefghijklmnopqrstuvwxyz123456"), sent);
        ArgumentCaptor<Proposal> p = ArgumentCaptor.forClass(Proposal.class);
        verify(proposals).propose(p.capture());
        assertFalse(p.getValue().body().contains("ops@example.com"), p.getValue().body());
    }

    // ---------------------------------------------------------------- parsing

    @Test
    void parseLessonAcceptsOnlyAShortWellFormedSentence() {
        assertEquals(
                "Use the work calendar.",
                LessonLearningService.parseLesson("```json\n{\"lesson\":\"  Use the work\\ncalendar. \"}\n```").orElseThrow());
        assertTrue(LessonLearningService.parseLesson(null).isEmpty());
        assertTrue(LessonLearningService.parseLesson("no json").isEmpty());
        assertTrue(LessonLearningService.parseLesson("{\"other\":\"Use the work calendar.\"}").isEmpty());
    }

    // ---------------------------------------------------------------- hook lifecycle

    @Test
    void installsAndRemovesTheGatewayHook() {
        assertFalse(LessonLearningGateway.active());
        service.install();
        assertTrue(service.enabled());
        assertTrue(LessonLearningGateway.active());
        service.shutdown();
        assertFalse(LessonLearningGateway.active());
    }
}
