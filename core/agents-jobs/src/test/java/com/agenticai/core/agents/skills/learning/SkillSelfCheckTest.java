package com.agenticai.core.agents.skills.learning;

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

import com.agenticai.core.agents.skills.SkillLearningGateway;
import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.agents.skills.SkillLifecycleService;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.learning.SkillSelfCheckVerdict.Verdict;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The self-check of a drafted skill: verdict parsing, the judge call, and what the loop does with it. */
class SkillSelfCheckTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-17T09:00:00Z");

    private static final String GOOD_DRAFT =
            """
            {"worth_saving": true,
             "name": "Monthly GST Summary",
             "description": "Builds a monthly GST summary from the invoice list. Use when the user asks for a GST summary of a month.",
             "instructions": "1. Call list_invoices for the month.\\n2. Call sum_invoices with the tax field.\\n3. Reply with a table of net, tax and gross, then a one-line total. Pitfall: invoices in draft state must be excluded.",
             "rationale": "Recurring month-end request."}""";

    // ---------------------------------------------------------------- parser

    @Test
    void parsesEachVerdictCaseInsensitivelyAndWrappedInChatter() {
        assertEquals(
                Verdict.PASS,
                SkillSelfCheckVerdict.parse("Sure {\"verdict\":\"pass\",\"reason\":\"Same steps.\"} done")
                        .orElseThrow()
                        .verdict());
        assertEquals(
                Verdict.WEAK,
                SkillSelfCheckVerdict.parse("```json\n{\"verdict\": \"WEAK\", \"reason\": \"Vague.\"}\n```")
                        .orElseThrow()
                        .verdict());
        SkillSelfCheckVerdict fail =
                SkillSelfCheckVerdict.parse("{\"verdict\":\" Fail \",\"reason\":\"Skips   the\\ntotal.\"}")
                        .orElseThrow();
        assertEquals(Verdict.FAIL, fail.verdict());
        assertEquals("Skips the total.", fail.reason());
    }

    @Test
    void unreadableRepliesGiveNoVerdictRatherThanAGuess() {
        for (String reply :
                new String[] {
                    null,
                    "",
                    "looks fine to me",
                    "{ broken",
                    "{\"verdict\":\"GREAT\"}",
                    "{\"verdict\":true}",
                    "{\"reason\":\"no verdict key\"}",
                    "[\"PASS\"]"
                }) {
            assertTrue(SkillSelfCheckVerdict.parse(reply).isEmpty(), "reply: " + reply);
        }
    }

    @Test
    void theReasonIsBoundedSingleLineAndFreeOfAngleBrackets() {
        String longReason = "<b>x</b> " + "word ".repeat(200);
        SkillSelfCheckVerdict v =
                SkillSelfCheckVerdict.parse("{\"verdict\":\"PASS\",\"reason\":\"" + longReason + "\"}")
                        .orElseThrow();
        assertTrue(v.reason().length() <= SkillSelfCheckVerdict.MAX_REASON);
        assertFalse(v.reason().contains("<"));
        assertFalse(v.reason().contains(">"));
        assertFalse(v.reason().contains("\n"));
    }

    // ---------------------------------------------------------------- the judge call

    @SuppressWarnings("unchecked")
    private static SkillSelfChecker checker(AgentLabCompletionService lab) {
        ObjectProvider<SkillDraftScrubber> none = mock(ObjectProvider.class);
        return new SkillSelfChecker(lab, none);
    }

    @Test
    void theJudgeSeesTheScrubbedRequestAnswerAndDraftAndNeverTools() {
        AgentLabCompletionService lab = mock(AgentLabCompletionService.class);
        when(lab.complete(any(LabCompletionRequest.class)))
                .thenReturn("{\"verdict\":\"PASS\",\"reason\":\"Same method.\"}");

        Optional<SkillSelfCheckVerdict> v =
                checker(lab)
                        .check(
                                AGENT,
                                "s1",
                                "Summarise GST for September, mail it to alice@example.com",
                                "Done. Key sk-abcdefghijklmnopqrstuvwxyz0123456789 <script>",
                                "gst-summary",
                                "1. List invoices\n2. Sum tax",
                                "alice");

        assertEquals(Verdict.PASS, v.orElseThrow().verdict());
        ArgumentCaptor<LabCompletionRequest> req = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab).complete(req.capture());
        LabCompletionRequest r = req.getValue();
        assertEquals(AGENT, r.agentId());
        assertEquals(SkillSelfChecker.SYSTEM_PROMPT, r.systemPrompt());
        assertEquals("alice", r.subjectKey());
        String msg = r.userMessage();
        assertTrue(msg.startsWith("Untrusted data"));
        assertTrue(msg.contains("Summarise GST for September"));
        assertTrue(msg.contains("List invoices"));
        assertFalse(msg.contains("alice@example.com"), "personal data is scrubbed before the call");
        assertFalse(msg.contains("sk-abcdefghijklmnopqrstuvwxyz0123456789"), "secrets are scrubbed");
        assertFalse(msg.contains("<script>"), "angle brackets cannot imitate markup");
    }

    @Test
    void inputsAreBoundedBeforeTheCall() {
        SkillSelfChecker c = checker(mock(AgentLabCompletionService.class));
        String msg =
                c.buildMessage("q ".repeat(5_000), "a ".repeat(9_000), "n", "step\n".repeat(5_000));
        int budget = SkillSelfChecker.REQUEST_CHARS + SkillSelfChecker.ANSWER_CHARS
                + SkillSelfChecker.INSTRUCTION_CHARS + 400;
        assertTrue(msg.length() <= budget, "message was " + msg.length());
    }

    @Test
    void aFailingOrLimitedModelCallGivesNoVerdictAndNeverThrows() {
        AgentLabCompletionService lab = mock(AgentLabCompletionService.class);
        when(lab.complete(any(LabCompletionRequest.class))).thenThrow(new IllegalStateException("boom"));
        assertTrue(checker(lab).check(AGENT, "s1", "q", "a", "n", "i", "alice").isEmpty());

        AgentLabCompletionService babbling = mock(AgentLabCompletionService.class);
        when(babbling.complete(any(LabCompletionRequest.class))).thenReturn("I like it");
        assertTrue(checker(babbling).check(AGENT, "s1", "q", "a", "n", "i", "alice").isEmpty());
    }

    // ---------------------------------------------------------------- what the loop does with it

    private ToolCallLogService toolCalls;
    private AgentLabCompletionService lab;
    private SkillService skills;
    private SkillLifecycleService lifecycle;
    private SkillSelfChecker selfChecker;
    private SkillLearningService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        toolCalls = mock(ToolCallLogService.class);
        lab = mock(AgentLabCompletionService.class);
        skills = mock(SkillService.class);
        lifecycle = mock(SkillLifecycleService.class);
        selfChecker = mock(SkillSelfChecker.class);
        when(skills.settingsForTurn(AGENT)).thenReturn(new SkillService.AgentSkillSettings(true, Set.of()));
        when(skills.metadataForAgent(AGENT)).thenReturn(List.of());
        ObjectProvider<SkillDraftScrubber> none = mock(ObjectProvider.class);
        service =
                new SkillLearningService(
                        new SkillLearningProperties(true, 3, 0, 50, 6000), toolCalls, lab, skills, lifecycle, none);
        service.useExecutor(Runnable::run);
        service.setSelfChecker(selfChecker);
        List<ToolCallLog> calls =
                List.of(
                        call("list_invoices", 1),
                        call("sum_invoices", 2),
                        call("format_table", 3),
                        call("send_note", 4));
        when(toolCalls.findSince(eq(AGENT), eq("s1"), any())).thenReturn(calls);
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn(GOOD_DRAFT);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        SkillLearningGateway.install(null);
    }

    private static ToolCallLog call(String tool, int sec) {
        Instant at = T0.plusSeconds(sec);
        return new ToolCallLog(AGENT, "s1", tool, "{}", "ok", null, at, at.plusMillis(200));
    }

    private void turn() {
        service.onTurn(
                new TurnOutcome(
                        AGENT,
                        "s1",
                        "Give me the GST summary for September",
                        "Here is the summary: ...",
                        T0,
                        true,
                        false,
                        TenantScope.of(TenantContext.of("acme", "alice", Set.of()))));
    }

    private void checkerSays(Verdict v, String reason) {
        when(selfChecker.check(eq(AGENT), eq("s1"), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new SkillSelfCheckVerdict(v, reason)));
    }

    @Test
    void aFailingDraftIsDroppedAndCounted() {
        checkerSays(Verdict.FAIL, "Skips the total.");
        turn();
        verify(skills, never()).propose(anyString(), anyString(), anyString(), anyString());
        verify(lifecycle, never()).markPending(anyString(), any(), anyString());
        assertEquals(1, service.droppedBySelfCheck());
    }

    @Test
    void aPassingOrWeakDraftIsQueuedWithItsVerdictStoredAfterItIsPending() {
        checkerSays(Verdict.WEAK, "Vague on the output.");
        turn();
        verify(skills)
                .propose(eq("monthly-gst-summary"), anyString(), anyString(), eq("agent:learning:" + AGENT));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(lifecycle);
        order.verify(lifecycle).markPending(eq("monthly-gst-summary"), eq(AGENT), anyString());
        order.verify(lifecycle).recordSelfCheck("monthly-gst-summary", "WEAK", "Vague on the output.");
        assertEquals(0, service.droppedBySelfCheck());
    }

    @Test
    void theCheckSeesTheOriginatingRequestAnswerAndTheScrubbedDraft() {
        checkerSays(Verdict.PASS, "Same method.");
        turn();
        verify(selfChecker)
                .check(
                        eq(AGENT),
                        eq("s1"),
                        eq("Give me the GST summary for September"),
                        eq("Here is the summary: ..."),
                        eq("monthly-gst-summary"),
                        org.mockito.ArgumentMatchers.contains("sum_invoices"),
                        eq("alice"));
        verify(lifecycle).recordSelfCheck("monthly-gst-summary", "PASS", "Same method.");
    }

    @Test
    void noVerdictQueuesTheDraftUncheckedAndACrashingCheckerNeverBlocksIt() {
        when(selfChecker.check(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        turn();
        verify(skills).propose(eq("monthly-gst-summary"), anyString(), anyString(), anyString());
        verify(lifecycle, never()).recordSelfCheck(anyString(), anyString(), anyString());

        when(selfChecker.check(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        // the cooldown is 0 and the name is taken, so propose is called again; it must still be reached
        turn();
        verify(skills, org.mockito.Mockito.times(2))
                .propose(eq("monthly-gst-summary"), anyString(), anyString(), anyString());
    }

    @Test
    void withoutACheckerTheLoopBehavesAsBefore() {
        service.setSelfChecker(null);
        turn();
        verify(skills).propose(eq("monthly-gst-summary"), anyString(), anyString(), anyString());
        verify(lifecycle, never()).recordSelfCheck(anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- wiring

    @Configuration
    @Import({SkillLearningProperties.class, SkillLearningService.class, SkillSelfChecker.class})
    static class Wiring {
        @Bean
        ToolCallLogService toolCallLogService() {
            return mock(ToolCallLogService.class);
        }

        @Bean
        AgentLabCompletionService agentLabCompletionService() {
            return mock(AgentLabCompletionService.class);
        }

        @Bean
        SkillService skillService() {
            return mock(SkillService.class);
        }

        @Bean
        SkillLifecycleService skillLifecycleService() {
            return mock(SkillLifecycleService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    void theCheckerExistsOnlyWhenItsOwnFlagIsOn() {
        runner.withPropertyValues("app.skills.learning.enabled=true")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(SkillSelfChecker.class).length));
        runner.withPropertyValues(
                        "app.skills.learning.enabled=true", "app.skills.learning.self-check.enabled=true")
                .run(ctx -> assertEquals(1, ctx.getBeanNamesForType(SkillSelfChecker.class).length));
    }
}
